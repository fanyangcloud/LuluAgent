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
import com.lulu.agent.accessibility.util.GestureEngine
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
 * 任务调度中枢大脑 (强化自愈守护、WelcomeActivity显式唤醒与前置去重版)
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

    // 会话内存去重集合 (记录 "公司名_职位名")
    private val processedJobKeys = mutableSetOf<String>()

    // 当前感知的视觉场景
    @Volatile
    private var currentScene: PageScene = PageScene.UNKNOWN

    companion object {
        private const val BOSS_PKG = "com.hpbr.bosszhipin"
        // 目标公开导出的 WelcomeActivity
        private const val BOSS_WELCOME_ACTIVITY = "com.hpbr.bosszhipin.module.launcher.WelcomeActivity"

        // 真实 UI 锚点 ID
        private const val ID_TAB_JOB = "com.hpbr.bosszhipin:id/cl_tab_1"
        private const val ID_TAB_LABEL = "com.hpbr.bosszhipin:id/tv_tab_label"
        private const val ID_FEED_LIST = "com.hpbr.bosszhipin:id/rv_list"
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
            Log.d(tag, "感知到视觉场景变化: $scene")
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

        Log.i(tag, "🚀 启动 TaskDispatcher 工作流...")
        dispatcherScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        supervisorAgent.start()
        scoutAgent.start()
        evaluatorAgent.start()
        communicatorAgent.start()

        safeTransitionTo(EngineState.SCANNING, "流水线启动")
        configRepository.setPaused(false)

        orchestratorJob = dispatcherScope.launch {
            // 启动时显式拉起 WelcomeActivity 并复位到 Boss 直聘首页推荐列表
            resetToRecommendFeed()

            if (warmUpDelayMs > 0) {
                val totalSeconds = (warmUpDelayMs / 1000).toInt()
                for (s in totalSeconds downTo 1) {
                    broadcastStepLog("🚀 正在就绪 Boss 推荐流，将在 ${s}s 后开启寻岗...")
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
        if (stateMachine.transitionTo(EngineState.SCANNING, "恢复执行")) {
            configRepository.setPaused(false)
            broadcastStepLog("▶️ 流水线已恢复")
        }
    }

    fun emergencyStop(riskType: String, reason: String) {
        safeTransitionTo(EngineState.EMERGENCY_STOP, "安全风控熔断: $reason")
        stop()
    }

    private suspend fun runOrchestrationLoop() {
        while (dispatcherScope.isActive) {
            if (stateMachine.getCurrentState() == EngineState.PAUSED) {
                delay(1000L)
                continue
            }

            if (!stateMachine.getCurrentState().isOperating()) {
                break
            }

            // 1. 每日配额校验
            val maxLimit = configRepository.getMaxDailyGreetings()
            if (!jobRepository.canGreetMoreToday(maxLimit)) {
                broadcastStepLog("今日打招呼已达最大上限 ($maxLimit 次)，任务圆满完成！")
                stop()
                break
            }

            // 2. 核心守护：扫描前必须确保处于【推荐职位列表】
            ensureInRecommendList()
            safeTransitionTo(EngineState.SCANNING, "扫描当前屏幕卡片")

            // 3. 扫描屏幕可见卡片
            var cardAnchors = emptyList<ScoutAgent.CardAnchor>()
            var waitListSeconds = 0
            while (dispatcherScope.isActive && waitListSeconds < 8) {
                cardAnchors = scoutAgent.scanCurrentFeedCards()
                if (cardAnchors.isNotEmpty()) break
                broadcastStepLog("等待职位列表卡片呈现 (${waitListSeconds + 1}s)...")
                delay(1000L)
                waitListSeconds++
            }

            // 4. 若当前屏幕没有卡片，上滑翻页
            if (cardAnchors.isEmpty()) {
                broadcastStepLog("未发现有效卡片，上滑加载新内容...")
                performScrollFeed(allowTabSwitch = false)
                humanDelay(1500L, 300L)
                continue
            }

            // 5. 前置秒级去重：在点击前直接过滤已看过的岗位
            val unvisitedCards = cardAnchors.filter { card ->
                val key = buildJobKey(card.previewCompany, card.previewTitle)
                !processedJobKeys.contains(key)
            }

            if (unvisitedCards.isEmpty()) {
                broadcastStepLog("当前屏幕卡片均已检视，上滑浏览下一页...")
                performScrollFeed(allowTabSwitch = true)
                humanDelay(1500L, 300L)
                continue
            }

            // 6. 逐个处理未访问的卡片
            for (card in unvisitedCards) {
                if (!dispatcherScope.isActive || !stateMachine.getCurrentState().isOperating()) break

                while (stateMachine.getCurrentState() == EngineState.PAUSED) {
                    delay(1000L)
                }

                val cardKey = buildJobKey(card.previewCompany, card.previewTitle)
                processedJobKeys.add(cardKey)

                ensureInRecommendList()

                // 阶段一：进入详情页抓取 JD
                safeTransitionTo(EngineState.INSPECTING, "查看岗位详情")
                val scrapeDeferred = CompletableDeferred<ScrapedRawJob?>()

                scoutAgent.inspectJobDetail(
                    cardBounds = card.bounds,
                    onSuccess = { scrapedJob -> scrapeDeferred.complete(scrapedJob) },
                    onFailed = { scrapeDeferred.complete(null) }
                )

                val job = scrapeDeferred.await()
                if (job == null) {
                    broadcastStepLog("⚠️ 提取岗位详情失败，安全回退")
                    ensureInRecommendList()
                    continue
                }

                processedJobKeys.add(job.resolveJobId())

                // 阶段二：DeepSeek 评估
                safeTransitionTo(EngineState.THINKING, "模型评估中")
                broadcastStepLog("🧠 模型正在评估【${job.companyName} - ${job.title}】...")
                val evalResult = evaluatorAgent.evaluate(job)

                humanDelay(1000L, 200L)

                // 阶段三：决策闭环分流
                if (evalResult.isApproved) {
                    safeTransitionTo(EngineState.COMMUNICATING, "发起打招呼破冰")
                    broadcastStepLog("🎯 契合度达标 (${evalResult.matchScore}分)，立即发起破冰沟通...")

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
                    broadcastStepLog("⏭️ 契合度不足 (${evalResult.matchScore}分)，跳过该岗位")
                }

                // 阶段四：安全退回推荐列表
                ensureInRecommendList()
                safeTransitionTo(EngineState.SCANNING, "已回退列表，继续巡查")
                humanDelay(800L, 200L)
            }

            // 7. 当前屏所有新卡片消费完毕，上滑翻页
            if (dispatcherScope.isActive && stateMachine.getCurrentState().isOperating()) {
                broadcastStepLog("当前一屏已巡查完毕，上滑刷新职位流...")
                performScrollFeed(allowTabSwitch = true)
                humanDelay(1500L, 300L)
            }
        }
    }

    /**
     * 自愈场景守卫：精准识别真实页面，严防将 JD 详情页误判为推荐流
     */
    private suspend fun ensureInRecommendList(maxAttempts: Int = 3) {
        val service = BossAccessibilityService.instance ?: return
        var attempts = 0

        while (dispatcherScope.isActive && attempts < maxAttempts) {
            val root = service.rootInActiveWindow
            if (root != null) {
                // 1. 详情页排查：如果有【立即沟通】按钮，说明绝对还在 JD 详情页，绝不能停！
                val chatBtnNodes = root.findAccessibilityNodeInfosByViewId(ID_DETAIL_CHAT_BTN)
                val isStillInDetail = !chatBtnNodes.isNullOrEmpty()
                chatBtnNodes?.forEach { it.recycle() }

                // 2. 聊天页排查：如果有输入框，说明还在聊天窗口
                val chatInputNodes = root.findAccessibilityNodeInfosByViewId(ID_CHAT_EDIT_TEXT)
                val isStillInChat = !chatInputNodes.isNullOrEmpty()
                chatInputNodes?.forEach { it.recycle() }

                // 3. 首页特征校验：必须有底部导航栏【职位】Tab
                val tabJobNodes = root.findAccessibilityNodeInfosByViewId(ID_TAB_JOB)
                val isTabJobVisible = !tabJobNodes.isNullOrEmpty()
                tabJobNodes?.forEach { it.recycle() }

                root.recycle()

                // 🌟 核心防线：只有【没有沟通按钮】、【没有聊天框】且【底部职位Tab就位】时，才算真正回到首页！
                if (!isStillInDetail && !isStillInChat && isTabJobVisible) {
                    currentScene = PageScene.RECOMMEND_LIST
                    return
                }
            }

            Log.w(tag, "检测到未在推荐列表（仍在详情页或聊天中），执行安全后退 (第 ${attempts + 1} 次)...")
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            humanDelay(900L, 150L) // 留足 900ms 保证页面退回到位
            attempts++
        }

        if (currentScene != PageScene.RECOMMEND_LIST) {
            Log.e(tag, "⚠️ 连续后退未恢复，启动 WelcomeActivity 显式复位至推荐首页")
            resetToRecommendFeed()
        }
    }

    /**
     * 【显式组件复位】：通过 ComponentName 直接唤醒 WelcomeActivity 并校准 Tab
     */
    private suspend fun resetToRecommendFeed() {
        broadcastStepLog("🔄 正在执行基准线复位：定向拉起 WelcomeActivity...")

        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = ComponentName(BOSS_PKG, BOSS_WELCOME_ACTIVITY)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        }

        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(tag, "显式拉起 WelcomeActivity 失败: ${e.message}")
        }

        humanDelay(1200L, 200L)

        val service = BossAccessibilityService.instance ?: return

        var backPopTries = 0
        while (dispatcherScope.isActive && backPopTries < 3) {
            val root = service.rootInActiveWindow
            if (root != null) {
                val hasDetail = root.findAccessibilityNodeInfosByViewId(ID_DETAIL_CHAT_BTN).isNotEmpty()
                val hasChat = root.findAccessibilityNodeInfosByViewId(ID_CHAT_EDIT_TEXT).isNotEmpty()
                root.recycle()
                if (hasDetail || hasChat) {
                    Log.d(tag, "复位过程清理顶层页面，执行返回")
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    humanDelay(600L, 100L)
                    backPopTries++
                    continue
                }
            }
            break
        }

        // UI 锚点校准：校准底部 Tab 1【职位】
        val root = service.rootInActiveWindow ?: return
        try {
            val tab1Nodes = root.findAccessibilityNodeInfosByViewId(ID_TAB_JOB)
            val tab1 = tab1Nodes?.firstOrNull()
            if (tab1 != null && !tab1.isSelected) {
                Log.d(tag, "校准底部导航：点击【职位】Tab")
                GestureEngine.performClick(service, tab1)
                humanDelay(600L, 100L)
            }
            tab1Nodes?.forEach { it.recycle() }

            // UI 锚点校准：校准顶部子 Tab【推荐】
            val freshRoot = service.rootInActiveWindow ?: return
            val tabLabels = freshRoot.findAccessibilityNodeInfosByViewId(ID_TAB_LABEL)
            val recommendTab = tabLabels?.firstOrNull { it.text?.toString() == "推荐" }
            if (recommendTab != null && !recommendTab.isSelected) {
                Log.d(tag, "校准顶部子导航：点击【推荐】")
                GestureEngine.performClick(service, recommendTab)
                humanDelay(800L, 150L)
            }
            tabLabels?.forEach { it.recycle() }
            freshRoot.recycle()

            Log.i(tag, "✅ 成功复位并锚定在 Boss 直聘【职位 -> 推荐】流！")
        } finally {
            root.recycle()
        }
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

    private fun buildJobKey(company: String, title: String): String {
        return "${company.trim()}_${title.trim()}"
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
            Log.e(tag, "注销中枢广播异常: ${e.message}")
        }
        dispatcherScope.cancel()
    }
}