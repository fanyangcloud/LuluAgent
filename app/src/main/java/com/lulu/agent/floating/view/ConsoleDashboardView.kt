package com.lulu.agent.floating.view

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.lulu.agent.floating.state.HUDUiState
import kotlin.math.abs

/**
 * 极客大控制台视图 (展示 DeepSeek 思考链、操作日志与全功能控制)
 */
class ConsoleDashboardView(context: Context) : FrameLayout(context) {

    private val statusTv: TextView
    private val targetTv: TextView
    private val thoughtTv: TextView
    private val logTv: TextView
    private val logScrollView: ScrollView
    private val pauseResumeBtn: TextView

    var onMinimizeClicked: (() -> Unit)? = null
    var onPauseResumeClicked: (() -> Unit)? = null
    var onEmergencyStopClicked: (() -> Unit)? = null
    var onPositionMoved: ((deltaX: Int, deltaY: Int) -> Unit)? = null

    private var lastRawX = 0f
    private var lastRawY = 0f
    private var isDragging = false
    private var touchDownRawX = 0f
    private var touchDownRawY = 0f

    init {
        // 大面板背景：深灰近黑玻璃拟态
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp2px(16).toFloat()
            setColor(Color.parseColor("#F216161A"))
            setStroke(2, Color.parseColor("#4D5C6BC0"))
        }

        val pad = dp2px(14)
        setPadding(pad, pad, pad, pad)

        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        // ==================== 顶部标题与控制栏 ====================
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val appTitle = TextView(context).apply {
            text = "BossAgent 深度驾驶舱"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }

        val minimizeBtn = createIconButton("➖", "#3A3A40") { onMinimizeClicked?.invoke() }
        val stopBtn = createIconButton("🛑", "#8C2626") { onEmergencyStopClicked?.invoke() }

        header.addView(appTitle)
        header.addView(minimizeBtn)
        header.addView(stopBtn)
        rootLayout.addView(header)

        // ==================== 状态与标的展示 ====================
        val metaLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val bg = GradientDrawable().apply {
                cornerRadius = dp2px(8).toFloat()
                setColor(Color.parseColor("#26262B"))
            }
            background = bg
            setPadding(dp2px(8), dp2px(6), dp2px(8), dp2px(6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp2px(8)
            }
        }

        statusTv = TextView(context).apply {
            text = "状态: 就绪"
            textSize = 11f
            setTextColor(Color.parseColor("#81C784"))
        }

        targetTv = TextView(context).apply {
            text = "标的: 等待开始"
            textSize = 11f
            setTextColor(Color.parseColor("#E0E0E0"))
            isSingleLine = true
        }

        metaLayout.addView(statusTv)
        metaLayout.addView(targetTv)
        rootLayout.addView(metaLayout)

        // ==================== DeepSeek 实时思考流面板 ====================
        val thoughtContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp2px(8).toFloat()
                setColor(Color.parseColor("#231E33")) // 微淡紫暗黑
                setStroke(1, Color.parseColor("#7E57C2"))
            }
            setPadding(dp2px(8), dp2px(8), dp2px(8), dp2px(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp2px(8)
            }
        }

        val thoughtBadge = TextView(context).apply {
            text = "🧠 模型推理思考流:"
            textSize = 10f
            setTextColor(Color.parseColor("#B388FF"))
        }

        thoughtTv = TextView(context).apply {
            text = "等待分析指令..."
            textSize = 11f
            setTextColor(Color.parseColor("#EDE7F6"))
            maxLines = 4
        }

        thoughtContainer.addView(thoughtBadge)
        thoughtContainer.addView(thoughtTv)
        rootLayout.addView(thoughtContainer)

        // ==================== 控制操作栏 ====================
        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp2px(8)
            }
        }

        pauseResumeBtn = TextView(context).apply {
            text = "⏸️ 挂起暂停"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp2px(6).toFloat()
                setColor(Color.parseColor("#3F51B5"))
            }
            setPadding(dp2px(12), dp2px(6), dp2px(12), dp2px(6))
            setOnClickListener { onPauseResumeClicked?.invoke() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        actionRow.addView(pauseResumeBtn)
        rootLayout.addView(actionRow)

        // ==================== 日志输出终端窗口 ====================
        logScrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp2px(110)
            ).apply {
                topMargin = dp2px(8)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp2px(6).toFloat()
                setColor(Color.parseColor("#0F0F12"))
            }
            setPadding(dp2px(6), dp2px(6), dp2px(6), dp2px(6))
        }

        logTv = TextView(context).apply {
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#B0BEC5"))
        }

        logScrollView.addView(logTv)
        rootLayout.addView(logScrollView)

        addView(rootLayout)
    }

    fun render(state: HUDUiState) {
        statusTv.text = "状态: ${state.stateTitle} (今日沟通: ${state.todayCommunicatedCount}次)"
        try {
            statusTv.setTextColor(Color.parseColor(state.indicatorColorHex))
        } catch (e: Exception) {
            // 颜色兜底
        }

        targetTv.text = "标的: ${state.currentTargetJob}"
        thoughtTv.text = state.deepSeekThought

        pauseResumeBtn.text = if (state.isPaused) "▶️ 恢复执行" else "⏸️ 挂起暂停"
        pauseResumeBtn.background = GradientDrawable().apply {
            cornerRadius = dp2px(6).toFloat()
            setColor(if (state.isPaused) Color.parseColor("#43A047") else Color.parseColor("#3F51B5"))
        }

        // 刷新终端日志
        val logText = state.recentLogs.joinToString("\n")
        logTv.text = logText
        logScrollView.post { logScrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun createIconButton(iconText: String, bgHex: String, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = iconText
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp2px(6).toFloat()
                setColor(Color.parseColor(bgHex))
            }
            val sz = dp2px(26)
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                marginStart = dp2px(6)
            }
            setOnClickListener { onClick() }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchDownRawX = event.rawX
                touchDownRawY = event.rawY
                lastRawX = event.rawX
                lastRawY = event.rawY
                isDragging = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - lastRawX).toInt()
                val dy = (event.rawY - lastRawY).toInt()

                if (abs(event.rawX - touchDownRawX) > 10 || abs(event.rawY - touchDownRawY) > 10) {
                    isDragging = true
                }

                if (isDragging) {
                    onPositionMoved?.invoke(dx, dy)
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun dp2px(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density + 0.5f).toInt()
    }
}
