package com.lulu.agent.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.lulu.agent.accessibility.model.PageScene
import com.lulu.agent.accessibility.task.BaseAutomationTask
import com.lulu.agent.accessibility.task.InspectDetailTask
import com.lulu.agent.accessibility.task.ScrollFeedTask
import com.lulu.agent.accessibility.task.SendGreetingTask
import com.lulu.agent.accessibility.task.TaskCallback
import com.lulu.agent.dispatcher.contract.DispatcherBroadcasts

/**
 * Boss直聘专属无障碍核心感知与执行服务 (支持双流场景研判版)
 */
class BossAccessibilityService : AccessibilityService(), TaskCallback {

    private val tag = "BossAccessibility"
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var currentRunningTask: BaseAutomationTask? = null

    private var lastKnownScene: PageScene = PageScene.UNKNOWN
    private var lastSceneCheckTimestamp: Long = 0L

    companion object {
        @Volatile
        var instance: BossAccessibilityService? = null
            private set

        fun isConnected(): Boolean = instance != null

        private const val TARGET_PACKAGE = "com.hpbr.bosszhipin"

        // 关键页面特征 ID
        private const val ID_FEED_LIST = "com.hpbr.bosszhipin:id/rv_list"
        private const val ID_DETAIL_CHAT_BTN = "com.hpbr.bosszhipin:id/btn_chat"
        private const val ID_DETAIL_JOB_NAME = "com.hpbr.bosszhipin:id/tv_job_name"
        private const val ID_CHAT_EDIT_TEXT = "com.hpbr.bosszhipin:id/editText_with_scrollbar"

        // 消息列表专属特征 ID
        private const val ID_TAB_3_TEXT = "com.hpbr.bosszhipin:id/tv_tab_3"
        private const val ID_CONTACT_VP = "com.hpbr.bosszhipin:id/contact_vp"
        private const val ID_CONTACT_RECYCLER = "com.hpbr.bosszhipin:id/recyclerView"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(tag, "✅ 无障碍通道已建立并激活")
        registerCommandReceiver()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterCommandReceiver()
        currentRunningTask?.forceStopSilently()
        currentRunningTask = null
        instance = null
        Log.w(tag, "🛑 无障碍服务已销毁")
    }

    override fun onInterrupt() {
        Log.w(tag, "⚠️ 无障碍服务被打断")
        currentRunningTask?.forceStopSilently()
        currentRunningTask = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val pkgName = event.packageName?.toString() ?: return
        if (pkgName != TARGET_PACKAGE) return

        val now = System.currentTimeMillis()
        if (now - lastSceneCheckTimestamp < 300L) return
        lastSceneCheckTimestamp = now

        val root = rootInActiveWindow ?: return
        val currentScene = detectCurrentScene(root)
        root.recycle()

        if (currentScene != lastKnownScene && currentScene != PageScene.UNKNOWN) {
            Log.d(tag, "视觉场景迁移: $lastKnownScene -> $currentScene")
            lastKnownScene = currentScene
            broadcastSceneChange(currentScene)

            if (currentScene.isRisk) {
                Log.e(tag, "🚨 检测到风控验证场景，广播警报！")
                broadcastRiskTriggered("CAPTCHA_INTERCEPT", "检测到滑动验证码或安全验证弹窗")
            }
        }
    }

    private fun detectCurrentScene(root: AccessibilityNodeInfo): PageScene {
        // 1. 优先判定风控与安全弹窗
        if (hasNodeByText(root, "安全验证") ||
            hasNodeByText(root, "拖动滑块") ||
            hasNodeByText(root, "人脸识别") ||
            hasNodeByText(root, "操作过于频繁")
        ) {
            return PageScene.CAPTCHA_RISK
        }

        // 2. 判定聊天对话窗口
        if (hasNodeById(root, ID_CHAT_EDIT_TEXT)) {
            return PageScene.CHAT_WINDOW
        }

        // 3. 判定岗位详情页
        if (hasNodeById(root, ID_DETAIL_CHAT_BTN) || hasNodeById(root, ID_DETAIL_JOB_NAME)) {
            return PageScene.JOB_DETAIL
        }

        // 4. 判定消息列表会话流 (核心更新：检测底部 Tab 3 选中态，或联系人专属 ViewPager)
        val tab3Nodes = root.findAccessibilityNodeInfosByViewId(ID_TAB_3_TEXT)
        val isTab3Selected = tab3Nodes?.firstOrNull()?.isSelected == true
        tab3Nodes?.forEach { it.recycle() }

        val hasContactVp = hasNodeById(root, ID_CONTACT_VP)
        if (isTab3Selected || hasContactVp) {
            return PageScene.MESSAGE_LIST
        }

        // 5. 判定职位列表推荐流
        if (hasNodeById(root, ID_FEED_LIST)) {
            return PageScene.RECOMMEND_LIST
        }

        return PageScene.UNKNOWN
    }

    private fun hasNodeById(root: AccessibilityNodeInfo, viewId: String): Boolean {
        val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
        val exists = !nodes.isNullOrEmpty()
        nodes?.forEach { it.recycle() }
        return exists
    }

    private fun hasNodeByText(root: AccessibilityNodeInfo, text: String): Boolean {
        val nodes = root.findAccessibilityNodeInfosByText(text)
        val exists = !nodes.isNullOrEmpty()
        nodes?.forEach { it.recycle() }
        return exists
    }

    private fun broadcastSceneChange(scene: PageScene) {
        val intent = Intent(DispatcherBroadcasts.ACTION_PAGE_SCENE_CHANGED).apply {
            putExtra(DispatcherBroadcasts.EXTRA_SCENE_NAME, scene.name)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun broadcastRiskTriggered(riskType: String, detail: String) {
        val intent = Intent(DispatcherBroadcasts.ACTION_RISK_TRIGGERED).apply {
            putExtra(DispatcherBroadcasts.EXTRA_RISK_TYPE, riskType)
            putExtra(DispatcherBroadcasts.EXTRA_RISK_DETAIL, detail)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    // ==================== 任务执行与回调分发 ====================

    fun executeTask(task: BaseAutomationTask) {
        mainHandler.post {
            if (currentRunningTask != null) {
                Log.w(tag, "已有任务在执行中，强杀旧任务: ${currentRunningTask?.getTaskName()}")
                currentRunningTask?.forceStopSilently()
            }
            currentRunningTask = task
            Log.i(tag, "▶️ 开始派发原子任务: ${task.getTaskName()}")
            task.execute(this, this)
        }
    }

    override fun onTaskFinished(taskName: String) {
        Log.i(tag, "任务已顺利完成: $taskName")
        currentRunningTask = null
        broadcastTaskFeedback(taskName, DispatcherBroadcasts.STATUS_SUCCESS)
    }

    override fun onTaskFailed(taskName: String, reason: String) {
        Log.e(tag, "任务执行失败: $taskName, 原因: $reason")
        currentRunningTask = null
        broadcastTaskFeedback(taskName, DispatcherBroadcasts.STATUS_FAILED, reason)
    }

    private fun broadcastTaskFeedback(taskName: String, status: String, errorMsg: String = "") {
        val intent = Intent(DispatcherBroadcasts.ACTION_TASK_FEEDBACK).apply {
            putExtra(DispatcherBroadcasts.EXTRA_TASK_NAME, taskName)
            putExtra(DispatcherBroadcasts.EXTRA_TASK_STATUS, status)
            if (errorMsg.isNotEmpty()) {
                putExtra(DispatcherBroadcasts.EXTRA_ERROR_MESSAGE, errorMsg)
            }
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                DispatcherBroadcasts.ACTION_EMERGENCY_STOP,
                DispatcherBroadcasts.ACTION_TASK_STOP -> {
                    Log.w(tag, "收到中止广播，强杀当前执行任务")
                    currentRunningTask?.forceStopSilently()
                    currentRunningTask = null
                }
                DispatcherBroadcasts.ACTION_TASK_START -> {
                    val taskName = intent.getStringExtra(DispatcherBroadcasts.EXTRA_TASK_NAME) ?: return
                    dispatchTaskByName(taskName, intent)
                }
            }
        }
    }

    private fun dispatchTaskByName(taskName: String, intent: Intent) {
        when (taskName) {
            "ScrollFeedTask" -> executeTask(ScrollFeedTask())
            "InspectDetailTask" -> executeTask(InspectDetailTask())
            "SendGreetingTask" -> {
                val greeting = intent.getStringExtra(DispatcherBroadcasts.EXTRA_GREETING_TEXT) ?: "您好！"
                executeTask(SendGreetingTask(greetingText = greeting))
            }
            else -> Log.w(tag, "未知的任务类型: $taskName")
        }
    }

    private fun registerCommandReceiver() {
        val filter = IntentFilter().apply {
            addAction(DispatcherBroadcasts.ACTION_EMERGENCY_STOP)
            addAction(DispatcherBroadcasts.ACTION_TASK_STOP)
            addAction(DispatcherBroadcasts.ACTION_TASK_START)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(commandReceiver, filter)
        }
    }

    private fun unregisterCommandReceiver() {
        try {
            unregisterReceiver(commandReceiver)
        } catch (e: Exception) {
            Log.e(tag, "解绑广播异常: ${e.message}")
        }
    }
}