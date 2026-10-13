package com.lulu.agent.dispatcher

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.lulu.agent.accessibility.BossAccessibilityService
import com.lulu.agent.accessibility.model.PageScene
import com.lulu.agent.accessibility.model.ScrapedRawJob
import com.lulu.agent.accessibility.model.UnreadChatSession
import com.lulu.agent.accessibility.task.InspectMessageListTask
import com.lulu.agent.accessibility.util.GestureEngine
import com.lulu.agent.accessibility.util.TabNavHelper
import com.lulu.agent.agent.communicator.CommunicatorAgent
import com.lulu.agent.agent.evaluator.EvaluatorAgent
import com.lulu.agent.agent.scout.ScoutAgent
import com.lulu.agent.agent.supervisor.SupervisorAgent
import com.lulu.agent.data.repository.ConfigRepository
import com.lulu.agent.data.repository.JobRepository
import com.lulu.agent.dispatcher.contract.DispatcherBroadcasts
import com.lulu.agent.dispatcher.fsm.EngineState
import com.lulu.agent.dispatcher.fsm.StateMachine
import com.lulu.agent.dispatcher.receiver.AccessibilityFeedbackReceiver
import com.lulu.agent.dispatcher.receiver.SupervisorControlReceiver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ThreadLocalRandom

/**
 * 任务调度中枢大脑 (双循环优先级抢占：消息高优先级响应 + 推荐低优先级巡查)
 */
class TaskDispatcher(
    private val context: Context,
    private val stateMachine: StateMachine,
    private val supervisorAgent: SupervisorAgent,
    private val scoutAgent: ScoutAgent,
    private val evaluatorAgent: EvaluatorAgent,
    private val communicatorAgent: CommunicatorAgent,
    private val configRepository: ConfigRepository,
    private val jobRepository: JobRepository
) {

    private val tag = "TaskDispatcher"
    private var dispatcherScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var orchestratorJob: Job? = null

    private val feedbackReceiver = AccessibilityFeedbackReceiver()
    private val controlReceiver = SupervisorControlReceiver()

    private val processedJobKeys = mutableSetOf<String>()

    @Volatile
    private var currentScene: PageScene = PageScene.UNKNOWN

    // 抽取到类级别声明（解决不能作为本地类的语法报错）
    private sealed class InspectOutcome {
        data class Found(val session: UnreadChatSession) : InspectOutcome()
        object Cleared : InspectOutcome()
        data class Failed(val reason: String) : InspectOutcome()
    }

    companion object {
        private const val BOSS_PKG = "com.hpbr.bosszhipin"
        private const val BOSS_WELCOME_ACTIVITY = "com.hpbr.bosszhipin.module.launcher.WelcomeActivity"

        private const val ID_TAB_JOB = "com.hpbr.bosszhipin:id/cl_tab_1"
        private const val ID_TAB_LABEL = "com.hpbr.bosszhipin:id/tv_tab_label"
        private const val ID_DETAIL_CHAT_BTN = "com.hpbr.bosszhipin:id/btn_chat"
        private const val ID_CHAT_EDIT_TEXT = "com.hpbr.bosszhipin:id/editText_with_scrollbar"
    }

    init {
        setupStateListener()
        setupReceivers()
    }

    private fun setupStateListener() {
        stateMachine.addListener { oldState, newState, reason ->
            Log.i(tag, "引擎状态跃迁: $oldState -> $newState ($reason)")
            val intent = Intent(DispatcherBroadcasts.ACTION_ENGINE_STATE_CHANGED).apply {
                putExtra(DispatcherBroadcasts.EXTRA_OLD_STATE, oldState.name)
                putExtra(DispatcherBroadcasts.EXTRA_NEW_STATE, newState.name)
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)
        }
    }

    private fun setupReceivers() {
        feedbackReceiver.onSceneChanged = { scene ->
            currentScene = scene
        }

        controlReceiver.onStartCommand = { start() }
        controlReceiver.onStopCommand = { stop() }
        controlReceiver.onPauseCommand = { pause() }
        controlReceiver.onResumeCommand = { resume() }
        controlReceiver.onEmergencyStopCommand = { riskType, reason ->
            emergencyStop(riskType, reason)
        }

        val exportFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Context.RECEIVER_NOT_EXPORTED
        } else 0

        context.registerReceiver(feedbackReceiver, AccessibilityFeedbackReceiver.createIntentFilter(), exportFlag)
        context.registerReceiver(controlReceiver, SupervisorControlReceiver.createIntentFilter(), exportFlag)
    }

    fun start(warmUpDelayMs: Long = 4000L) {
        if (stateMachine.getCurrentState().isOperating()) {
            Log.w(tag, "调度器已经在运行中")
            return
        }

        Log.i(tag, "🚀 启动 TaskDispatcher 双循环调度工作流...")
        dispatcherScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        supervisorAgent.start()
        scoutAgent.start()
        evaluatorAgent.start()
        communicatorAgent.start()

        safeTransitionTo(EngineState.CHECKING_INBOX, "启动流水线")
        configRepository.setPaused(false)

        orchestratorJob = dispatcherScope.launch {
            resetToRecommendFeed()

            if (warmUpDelayMs > 0) {
                val totalSeconds = (warmUpDelayMs / 1000).toInt()
                for (s in totalSeconds downTo 1) {
                    broadcastStepLog("🚀 正在就绪 Boss 宿主环境，将在 ${s}s 后开启优先信箱巡查...")
                    delay(1000L)
                }
            }

            runOrchestrationLoop()
        }
    }

    fun stop() {
        Log.w(tag, "🛑 停止 TaskDispatcher 工作流")
        orchestratorJob?.cancel()
        orchestratorJob = null

        supervisorAgent.stop()
        scoutAgent.stop()
        evaluatorAgent.stop()
        communicatorAgent.stop()

        safeTransitionTo(EngineState.IDLE, "终止任务")
        processedJobKeys.clear()
    }

    fun pause() {
        if (stateMachine.transitionTo(EngineState.PAUSED, "暂停指令")) {
            configRepository.setPaused(true)
            broadcastStepLog("⏸️ 流水线已挂起")
        }
    }

    fun resume() {
        if (stateMachine.transitionTo(EngineState.CHECKING_INBOX, "恢复执行")) {
            configRepository.setPaused(false)
            broadcastStepLog("▶️ 流水线已恢复")
        }
    }

    fun emergencyStop(riskType: String, reason: String) {
        safeTransitionTo(EngineState.EMERGENCY_STOP, "安全风控熔断: $reason")
        stop()
    }

    // ==================== 核心双循环优先级调度总线 ====================

    private suspend fun runOrchestrationLoop() {
        while (dispatcherScope.isActive) {
            if (stateMachine.getCurrentState() == EngineState.PAUSED) {
                delay(1000L)
                continue
            }

            if (!stateMachine.getCurrentState().isOperating()) break

            // 🌟【循环第一阶段】：高优先级！切往消息信箱，消费清空所有未读会话
            drainAllUnreadMessages()

            if (!dispatcherScope.isActive || !stateMachine.getCurrentState().isOperating()) break

            // 检查今日打招呼配额
            val maxLimit = configRepository.getMaxDailyGreetings()
            if (!jobRepository.canGreetMoreToday(maxLimit)) {
                broadcastStepLog("今日打招呼已达最大上限 ($maxLimit 次)，转为【仅消息值守模式】(5s后复查)...")
                delay(5000L)
                continue // 配额用尽时，不打新招呼，但持续值守新回复！
            }

            // 🌟【循环第二阶段】：低优先级！切往职位推荐流，巡查当前屏幕的岗位并破冰
            executeProactiveGreetingBatch()

            humanDelay(1500L, 300L)
        }
    }

    /**
     * 高优先级：巡查并清空全部未读消息
     */
    private suspend fun drainAllUnreadMessages() {
        val service = BossAccessibilityService.instance ?: return

        // 1. 确保切到底部 Tab 3【消息】
        ensureInMessageTab(service)

        var hasMoreUnread = true
        var loopCount = 0

        while (dispatcherScope.isActive && hasMoreUnread && loopCount < 15) {
            if (stateMachine.getCurrentState() == EngineState.PAUSED) {
                delay(1000L)
                continue
            }

            safeTransitionTo(EngineState.CHECKING_INBOX, "扫描未读消息项")
            loopCount++



            val inspectDeferred = CompletableDeferred<InspectOutcome>()
            val inspectTask = InspectMessageListTask(
                onSessionSelected = { session -> inspectDeferred.complete(InspectOutcome.Found(session)) },
                onAllCleared = { inspectDeferred.complete(InspectOutcome.Cleared) },
                onError = { reason -> inspectDeferred.complete(InspectOutcome.Failed(reason)) }
            )

            service.executeTask(inspectTask)
            val outcome = inspectDeferred.await()

            when (outcome) {
                is InspectOutcome.Found -> {
                    safeTransitionTo(EngineState.REPLYING_CHAT, "处理与【${outcome.session.hrName}】的会话")
                    val dialogueDeferred = CompletableDeferred<Boolean>()

                    communicatorAgent.handleIncomingMessage(
                        session = outcome.session,
                        onSuccess = { dialogueDeferred.complete(true) },
                        onFailed = { dialogueDeferred.complete(false) }
                    )

                    dialogueDeferred.await()
                    humanDelay(1000L, 200L)
                    // 确保退回到消息列表，继续寻找下一个
                    ensureInMessageTab(service)
                }

                is InspectOutcome.Cleared -> {
                    hasMoreUnread = false
                    broadcastStepLog("✨ 消息信箱全部处理完毕，准备切往职位探索流")
                }

                is InspectOutcome.Failed -> {
                    Log.w(tag, "巡查消息列表失败: ${outcome.reason}")
                    hasMoreUnread = false
                }
            }
        }
    }

    /**
     * 低优先级：在职位推荐流中巡检一屏并打招呼
     */
    private suspend fun executeProactiveGreetingBatch() {
        val service = BossAccessibilityService.instance ?: return

        // 1. 确保切换并对齐到推荐流首页
        ensureInRecommendList()
        safeTransitionTo(EngineState.SCANNING, "巡检当前推荐岗位")

        // 2. 扫描当前屏幕卡片
        var cardAnchors = scoutAgent.scanCurrentFeedCards()
        if (cardAnchors.isEmpty()) {
            broadcastStepLog("当前屏未扫描到卡片，上滑加载...")
            performScrollFeed(allowTabSwitch = false)
            return
        }

        val unvisitedCards = cardAnchors.filter { card ->
            val key = "${card.previewCompany.trim()}_${card.previewTitle.trim()}"
            !processedJobKeys.contains(key)
        }

        if (unvisitedCards.isEmpty()) {
            broadcastStepLog("当前屏卡片均已看毕，上滑加载下一屏...")
            performScrollFeed(allowTabSwitch = true)
            return
        }

        // 3. 逐个检视并打招呼
        for (card in unvisitedCards) {
            if (!dispatcherScope.isActive || !stateMachine.getCurrentState().isOperating()) break

            val cardKey = "${card.previewCompany.trim()}_${card.previewTitle.trim()}"
            processedJobKeys.add(cardKey)

            ensureInRecommendList()

            // 阶段一：抓取 JD 详情
            safeTransitionTo(EngineState.INSPECTING, "查看岗位详情")
            val scrapeDeferred = CompletableDeferred<ScrapedRawJob?>()

            scoutAgent.inspectJobDetail(
                cardBounds = card.bounds,
                onSuccess = { scrapeDeferred.complete(it) },
                onFailed = { scrapeDeferred.complete(null) }
            )

            val job = scrapeDeferred.await()
            if (job == null) {
                ensureInRecommendList()
                continue
            }

            processedJobKeys.add(job.resolveJobId())

            // 阶段二：大模型评估
            safeTransitionTo(EngineState.THINKING, "模型评估契合度")
            broadcastStepLog("🧠 模型正在推演【${job.companyName} - ${job.title}】...")
            val evalResult = evaluatorAgent.evaluate(job)

            humanDelay(1000L, 200L)

            // 阶段三：决策破冰打招呼
            if (evalResult.isApproved) {
                safeTransitionTo(EngineState.COMMUNICATING, "发起打招呼")
                val greetDeferred = CompletableDeferred<Boolean>()

                communicatorAgent.executeGreeting(
                    rawJob = job,
                    evaluatorResult = evalResult,
                    onSuccess = { greetDeferred.complete(true) },
                    onFailed = { greetDeferred.complete(false) }
                )

                greetDeferred.await()
                humanDelay(1500L, 300L)
            } else {
                broadcastStepLog("⏭️ 契合度未达标 (${evalResult.matchScore}分)，跳过")
            }

            ensureInRecommendList()
            safeTransitionTo(EngineState.SCANNING, "回退列表")
            humanDelay(600L, 150L)
        }

        // 4. 一屏处理完毕，顺带做一次拟人化翻页
        if (dispatcherScope.isActive && stateMachine.getCurrentState().isOperating()) {
            performScrollFeed(allowTabSwitch = true)
        }
    }

    // ==================== 场景防线与页面自愈 ====================

    /**
     * 场景守卫：确保处于【消息】列表 Tab
     */
    private suspend fun ensureInMessageTab(service: AccessibilityService) {
        var attempts = 0
        while (dispatcherScope.isActive && attempts < 3) {
            val root = service.rootInActiveWindow
            if (root != null) {
                val hasDetail = root.findAccessibilityNodeInfosByViewId(ID_DETAIL_CHAT_BTN).isNotEmpty()
                val hasChat = root.findAccessibilityNodeInfosByViewId(ID_CHAT_EDIT_TEXT).isNotEmpty()
                root.recycle()
                if (hasDetail || hasChat) {
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    humanDelay(800L, 100L)
                    attempts++
                    continue
                }
            }
            break
        }

        if (!TabNavHelper.isMessageTabSelected(service)) {
            TabNavHelper.switchToMessageTab(service)
            humanDelay(800L, 150L)
        }
    }

    /**
     * 场景守卫：确保处于【职位】推荐列表 Tab
     */
    private suspend fun ensureInRecommendList(maxAttempts: Int = 3) {
        val service = BossAccessibilityService.instance ?: return
        var attempts = 0

        while (dispatcherScope.isActive && attempts < maxAttempts) {
            val root = service.rootInActiveWindow
            if (root != null) {
                val hasDetail = root.findAccessibilityNodeInfosByViewId(ID_DETAIL_CHAT_BTN).isNotEmpty()
                val hasChat = root.findAccessibilityNodeInfosByViewId(ID_CHAT_EDIT_TEXT).isNotEmpty()
                root.recycle()

                if (hasDetail || hasChat) {
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    humanDelay(900L, 150L)
                    attempts++
                    continue
                }
            }
            break
        }

        if (!TabNavHelper.isJobTabSelected(service)) {
            TabNavHelper.switchToJobTab(service)
            humanDelay(800L, 150L)
        }
    }

    private suspend fun resetToRecommendFeed() {
        broadcastStepLog("🔄 正在唤醒 Boss 直聘主界面...")
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = ComponentName(BOSS_PKG, BOSS_WELCOME_ACTIVITY)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(tag, "唤醒失败: ${e.message}")
        }
        humanDelay(1500L, 200L)

        val service = BossAccessibilityService.instance ?: return
        ensureInRecommendList()
    }

    private suspend fun performScrollFeed(allowTabSwitch: Boolean) {
        val scrollDeferred = CompletableDeferred<Boolean>()
        scoutAgent.scrollNextPage(
            allowTabSwitch = allowTabSwitch,
            onCompleted = { scrollDeferred.complete(true) },
            onFailed = { scrollDeferred.complete(false) }
        )
        scrollDeferred.await()
    }

    private fun safeTransitionTo(targetState: EngineState, reason: String) {
        if (stateMachine.getCurrentState() != targetState) {
            stateMachine.transitionTo(targetState, reason)
        }
    }

    private fun broadcastStepLog(message: String) {
        val intent = Intent(DispatcherBroadcasts.ACTION_TASK_STEP_MESSAGE).apply {
            putExtra(DispatcherBroadcasts.EXTRA_TASK_NAME, "TaskDispatcher")
            putExtra(DispatcherBroadcasts.EXTRA_STEP_INFO, message)
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
    }

    private suspend fun humanDelay(baseMs: Long, varianceMs: Long = 300L) {
        val speedFactor = configRepository.getSpeedFactor()
        val adjustedBase = (baseMs * speedFactor).toLong()
        val jitter = ThreadLocalRandom.current().nextLong(-varianceMs, varianceMs)
        val finalDelay = maxOf(300L, adjustedBase + jitter)
        delay(finalDelay)
    }

    fun destroy() {
        stop()
        try {
            context.unregisterReceiver(feedbackReceiver)
            context.unregisterReceiver(controlReceiver)
        } catch (e: Exception) {
            Log.e(tag, "注销异常: ${e.message}")
        }
        dispatcherScope.cancel()
    }
}