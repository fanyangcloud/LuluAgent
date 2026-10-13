package com.lulu.agent.floating

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.lulu.agent.accessibility.util.GestureEngine
import com.lulu.agent.dispatcher.contract.DispatcherBroadcasts
import com.lulu.agent.dispatcher.fsm.EngineState
import com.lulu.agent.floating.manager.WindowStateManager
import com.lulu.agent.floating.state.HUDUiState
import com.lulu.agent.floating.view.CapsuleView
import com.lulu.agent.floating.view.ConsoleDashboardView

/**
 * 悬浮交互前台常驻服务 (已严格适配 Android 14/15/16 的 FGS 规范)
 */
class FloatingHUDService : Service() {

    private val tag = "FloatingHUDService"

    private lateinit var windowStateManager: WindowStateManager
    private lateinit var capsuleView: CapsuleView
    private lateinit var consoleView: ConsoleDashboardView

    private var currentState = HUDUiState()

    companion object {
        private const val CHANNEL_ID = "channel_boss_hud"
        private const val NOTIFICATION_ID = 1001

        fun startService(context: Context) {
            val intent = Intent(context, FloatingHUDService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()

        windowStateManager = WindowStateManager(this)
        capsuleView = CapsuleView(this)
        consoleView = ConsoleDashboardView(this)

        windowStateManager.attachViews(capsuleView, consoleView)

        setupViewListeners()
        setupGestureBridge()
        registerStatusReceiver()

        windowStateManager.switchToCapsule()
        renderAll()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterStatusReceiver()
        windowStateManager.removeCurrent()
        GestureEngine.windowTouchListener = null
        Log.w(tag, "FloatingHUDService 已注销")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun setupViewListeners() {
        capsuleView.onExpandClicked = {
            windowStateManager.switchToConsole()
        }

        consoleView.onMinimizeClicked = {
            windowStateManager.switchToCapsule()
        }

        consoleView.onPauseResumeClicked = {
            val action = if (currentState.isPaused) {
                DispatcherBroadcasts.ACTION_TASK_RESUME
            } else {
                DispatcherBroadcasts.ACTION_TASK_PAUSE
            }
            sendBroadcast(Intent(action).setPackage(packageName))
        }

        consoleView.onEmergencyStopClicked = {
            val intent = Intent(DispatcherBroadcasts.ACTION_EMERGENCY_STOP).apply {
                putExtra(DispatcherBroadcasts.EXTRA_ERROR_MESSAGE, "用户通过控制面板手动拉停")
                setPackage(packageName)
            }
            sendBroadcast(intent)
            windowStateManager.switchToCapsule()
        }
    }

    private fun setupGestureBridge() {
        GestureEngine.windowTouchListener = { isTouchable ->
            windowStateManager.setTouchable(isTouchable)
        }
    }

    private fun renderAll() {
        capsuleView.render(currentState)
        consoleView.render(currentState)
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return

            when (action) {
                DispatcherBroadcasts.ACTION_ENGINE_STATE_CHANGED -> {
                    val newStateName = intent.getStringExtra(DispatcherBroadcasts.EXTRA_NEW_STATE) ?: return
                    try {
                        val state = EngineState.valueOf(newStateName)
                        var updated = currentState.copy(
                            stateTitle = state.title,
                            indicatorColorHex = state.indicatorColorHex,
                            isOperating = state.isOperating(),
                            isPaused = state == EngineState.PAUSED
                        )

                        // 状态专属提示文案映射
                        when (state) {
                            EngineState.CHECKING_INBOX -> updated = updated.copy(currentTargetJob = "正在巡检未读消息信箱...")
                            EngineState.REPLYING_CHAT -> updated = updated.copy(currentTargetJob = "正在组织深入答复...")
                            else -> Unit
                        }

                        currentState = updated
                        renderAll()
                    } catch (e: Exception) {
                        // 忽略解析异常
                    }
                }

                DispatcherBroadcasts.ACTION_TASK_STEP_MESSAGE -> {
                    val msg = intent.getStringExtra(DispatcherBroadcasts.EXTRA_STEP_INFO) ?: return
                    val source = intent.getStringExtra(DispatcherBroadcasts.EXTRA_TASK_NAME) ?: "系统"
                    val formatted = "[$source] $msg"

                    var updated = currentState.appendLog(formatted)

                    // 1. 推理与思考链提取
                    if (msg.contains("模型") || msg.contains("评估") || msg.contains("构思") || msg.contains("推演")) {
                        updated = updated.copy(deepSeekThought = msg)
                    }

                    // 2. 标的岗位与沟通目标提取
                    if (msg.contains("成功向") || msg.contains("命中未读消息") || msg.contains("正在处理")) {
                        updated = updated.copy(currentTargetJob = msg)
                    }

                    // 3. 熔断高亮提示
                    if (msg.contains("熔断")) {
                        updated = updated.copy(deepSeekThought = "🚨 $msg")
                    }

                    currentState = updated
                    renderAll()
                }
            }
        }
    }

    private fun registerStatusReceiver() {
        val filter = IntentFilter().apply {
            addAction(DispatcherBroadcasts.ACTION_ENGINE_STATE_CHANGED)
            addAction(DispatcherBroadcasts.ACTION_TASK_STEP_MESSAGE)
        }
        val exportFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Context.RECEIVER_NOT_EXPORTED
        } else 0
        registerReceiver(statusReceiver, filter, exportFlag)
    }

    private fun unregisterStatusReceiver() {
        try {
            unregisterReceiver(statusReceiver)
        } catch (e: Exception) {
            // 忽略解绑错误
        }
    }

    private fun startForegroundNotification() {
        val channelName = "BossAgent 运行守护"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(chan)
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("鹿鹿求职搭子正在运行")
            .setContentText("全双工智能沟通与巡查中枢已就绪")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}