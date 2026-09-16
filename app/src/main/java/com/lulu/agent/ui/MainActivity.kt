package com.lulu.agent.ui

import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.lulu.agent.LuluApp
import com.lulu.agent.accessibility.BossAccessibilityService
import com.lulu.agent.dispatcher.contract.DispatcherBroadcasts
import com.lulu.agent.dispatcher.fsm.EngineState
import com.lulu.agent.floating.FloatingHUDService
import com.lulu.agent.ui.dashboard.HistoryRecordActivity
import com.lulu.agent.ui.settings.ApiKeyConfigActivity
import com.lulu.agent.ui.settings.ResumeEditorActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * 鹿鹿 (Lulu) - 主控制台 (极简纯净 · 治愈温润风)
 */
class MainActivity : AppCompatActivity() {

    private val bossPackage = "com.hpbr.bosszhipin"

    // UI 引用
    private lateinit var accessibilityBtn: TextView
    private lateinit var overlayBtn: TextView
    private lateinit var statusTextTv: TextView
    private lateinit var percentageTv: TextView
    private lateinit var mainActionButton: TextView

    // 数据看板四个计数器
    private lateinit var interviewCountTv: TextView
    private lateinit var chatCountTv: TextView
    private lateinit var deliveryCountTv: TextView
    private lateinit var contactCountTv: TextView

    // 🌟 纯净视觉色盘：低饱和温润暖杏橙 + 极简淡雅灰
    private val colorBg = Color.parseColor("#F8F9FB")
    private val colorCard = Color.WHITE
    private val colorCardStroke = Color.parseColor("#F0F2F5")
    private val colorPrimary = Color.parseColor("#FA6542")       // 鹿鹿治愈暖杏色
    private val colorPrimarySoft = Color.parseColor("#FFF4F0")   // 极淡微桃粉
    private val colorTextMain = Color.parseColor("#1F2329")      // 高阶石板黑
    private val colorTextSub = Color.parseColor("#8F959E")       // 优雅次级灰
    private val colorSuccess = Color.parseColor("#2BA471")       // 清新薄荷绿
    private val colorSuccessSoft = Color.parseColor("#EBF6F1")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(buildContentView())
        registerStatusReceiver()
    }

    override fun onResume() {
        super.onResume()
        refreshAllStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(statusReceiver)
    }

    // ==================== 极简纯净纯代码布局 ====================

    private fun buildContentView(): View {
        val root = RelativeLayout(this).apply {
            setBackgroundColor(colorBg)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // 1. 顶部标题栏
        val topBar = createTopBar().apply { id = View.generateViewId() }
        val topBarParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.WRAP_CONTENT
        ).apply { addRule(RelativeLayout.ALIGN_PARENT_TOP) }
        root.addView(topBar, topBarParams)

        // 2. 底部轻量导航栏
        val bottomNav = createBottomNav().apply { id = View.generateViewId() }
        val bottomNavParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            dp2px(60)
        ).apply { addRule(RelativeLayout.ALIGN_PARENT_BOTTOM) }
        root.addView(bottomNav, bottomNavParams)

        // 3. 中间可滚动卡片区
        val scrollView = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val scrollParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.MATCH_PARENT
        ).apply {
            addRule(RelativeLayout.BELOW, topBar.id)
            addRule(RelativeLayout.ABOVE, bottomNav.id)
        }

        val contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp2px(18)
            setPadding(pad, dp2px(6), pad, dp2px(24))
        }

        // 核心板块装配
        contentLayout.addView(createPreparednessCard())
        contentLayout.addView(createRunningStatusCard())
        contentLayout.addView(createDataDashboardCard())

        scrollView.addView(contentLayout)
        root.addView(scrollView, scrollParams)

        return root
    }

    /**
     * 顶部标题栏：温润大标题与治愈问候
     */
    private fun createTopBar(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp2px(20), dp2px(42), dp2px(20), dp2px(12))

            val titleCol = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val title = TextView(this@MainActivity).apply {
                text = "鹿鹿 · 求职搭子 🦌"
                textSize = 21f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorTextMain)
                includeFontPadding = false
            }

            val subtitle = TextView(this@MainActivity).apply {
                text = "深呼吸，今天也会遇到懂你的好伯乐"
                textSize = 12f
                setTextColor(colorTextSub)
                includeFontPadding = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp2px(4) }
            }

            titleCol.addView(title)
            titleCol.addView(subtitle)

            // 右上角小齿轮
            val gearBtn = TextView(this@MainActivity).apply {
                text = "⚙️"
                textSize = 18f
                gravity = Gravity.CENTER
                includeFontPadding = false
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.WHITE)
                    setStroke(dp2px(1), colorCardStroke)
                }
                val sz = dp2px(36)
                layoutParams = LinearLayout.LayoutParams(sz, sz)
                setOnClickListener { showSettingsDialog() }
            }

            addView(titleCol)
            addView(gearBtn)
        }
    }

    /**
     * 【重构核心】：小鹿准备清单（将原先占地巨大的双卡片合为清爽的列表）
     */
    private fun createPreparednessCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createCardDrawable()
            setPadding(dp2px(16), dp2px(16), dp2px(16), dp2px(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp2px(10) }
        }

        // 小标题
        val headerTv = TextView(this).apply {
            text = "小鹿的出发准备"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextSub)
            includeFontPadding = false
        }
        card.addView(headerTv)

        // 1. 无障碍权限行
        val (row1, btn1) = createPermRow(
            icon = "🐾",
            title = "敲门开路",
            desc = "用于在 Boss 推荐流替你探寻新岗位",
            onAction = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        )
        accessibilityBtn = btn1

        // 浅浅的分割线
        val divider = View(this).apply {
            setBackgroundColor(colorCardStroke)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp2px(1)
            ).apply {
                topMargin = dp2px(10)
                bottomMargin = dp2px(10)
            }
        }

        // 2. 悬浮窗权限行
        val (row2, btn2) = createPermRow(
            icon = "💭",
            title = "悄悄支招",
            desc = "聊天时让小鹿在旁边温柔辅助",
            onAction = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
            }
        )
        overlayBtn = btn2

        card.addView(row1)
        card.addView(divider)
        card.addView(row2)

        return card
    }

    private fun createPermRow(
        icon: String,
        title: String,
        desc: String,
        onAction: () -> Unit
    ): Pair<View, TextView> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp2px(10) }
        }

        val iconTv = TextView(this).apply {
            text = icon
            textSize = 16f
            gravity = Gravity.CENTER
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(dp2px(26), dp2px(26))
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp2px(8)
                marginEnd = dp2px(8)
            }
        }

        val titleTv = TextView(this).apply {
            text = title
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextMain)
            includeFontPadding = false
        }

        val descTv = TextView(this).apply {
            text = desc
            textSize = 11f
            setTextColor(colorTextSub)
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp2px(2) }
        }

        textCol.addView(titleTv)
        textCol.addView(descTv)

        val actionBtn = TextView(this).apply {
            text = "去开启"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorPrimary)
            gravity = Gravity.CENTER
            includeFontPadding = false
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp2px(20).toFloat()
                setColor(colorPrimarySoft)
            }
            setPadding(dp2px(14), dp2px(6), dp2px(14), dp2px(6))
            setOnClickListener { onAction() }
        }

        row.addView(iconTv)
        row.addView(textCol)
        row.addView(actionBtn)

        return Pair(row, actionBtn)
    }

    /**
     * 运行状态与行动控制卡片 (极简干净、大圆角呼吸感)
     */
    private fun createRunningStatusCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createCardDrawable()
            setPadding(dp2px(20), dp2px(20), dp2px(20), dp2px(20))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp2px(14) }
        }

        // 上方状态小胶囊与进度
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val statusPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp2px(100).toFloat()
                setColor(colorPrimarySoft)
            }
            setPadding(dp2px(10), dp2px(4), dp2px(12), dp2px(4))
        }

        val dot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(colorPrimary)
            }
            layoutParams = LinearLayout.LayoutParams(dp2px(6), dp2px(6)).apply {
                marginEnd = dp2px(6)
            }
        }

        statusTextTv = TextView(this).apply {
            text = "小鹿待命 (就绪)"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorPrimary)
            includeFontPadding = false
        }
        statusPill.addView(dot)
        statusPill.addView(statusTextTv)

        val progressInfo = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                weight = 1f
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
        }

        val progressLabel = TextView(this).apply {
            text = "今日配额 "
            textSize = 12f
            setTextColor(colorTextSub)
            includeFontPadding = false
        }

        percentageTv = TextView(this).apply {
            text = "0%"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextMain)
            includeFontPadding = false
        }

        val progressContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(progressLabel)
            addView(percentageTv)
        }

        topRow.addView(statusPill)
        topRow.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        })
        topRow.addView(progressContainer)
        card.addView(topRow)

        // 下半段：温润圆角行动主按钮
        mainActionButton = TextView(this).apply {
            text = "🦌 启动鹿鹿 · 开始探路"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp2px(14).toFloat()
                setColor(colorPrimary)
            }
            val bp = dp2px(15)
            setPadding(bp, bp, bp, bp)
            setOnClickListener { handleMainActionClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp2px(18) }
        }

        card.addView(mainActionButton)
        return card
    }

    /**
     * 四列数据看板 (去掉了大色块 Emoji 干扰，以清爽的现代排印聚焦数据)
     */
    private fun createDataDashboardCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createCardDrawable()
            setPadding(dp2px(16), dp2px(18), dp2px(16), dp2px(18))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp2px(14) }
        }

        val headerTv = TextView(this).apply {
            text = "求职战报小结"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextSub)
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp2px(14) }
        }
        card.addView(headerTv)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val (c1, tv1) = createDataColumn("已约面试", "0")
        val (c2, tv2) = createDataColumn("今日沟通", "0")
        val (c3, tv3) = createDataColumn("今日投递", "0")
        val (c4, tv4) = createDataColumn("联系交换", "0")

        interviewCountTv = tv1
        chatCountTv = tv2
        deliveryCountTv = tv3
        contactCountTv = tv4

        row.addView(c1)
        row.addView(c2)
        row.addView(c3)
        row.addView(c4)

        card.addView(row)
        return card
    }

    private fun createDataColumn(
        label: String,
        initialValue: String
    ): Pair<View, TextView> {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val countTv = TextView(this).apply {
            text = initialValue
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextMain)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }

        val labelTv = TextView(this).apply {
            text = label
            textSize = 11f
            setTextColor(colorTextSub)
            gravity = Gravity.CENTER
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp2px(4)
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }

        col.addView(countTv)
        col.addView(labelTv)

        return Pair(col, countTv)
    }

    /**
     * 极简扁平底部导航栏
     */
    private fun createBottomNav(): View {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            // 极细微的顶边线
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(dp2px(1), colorCardStroke)
            }
        }

        val tabHome = createNavItem("首页", isSelected = true) {}
        val tabRecords = createNavItem("投递记录", isSelected = false) {
            startActivity(Intent(this, HistoryRecordActivity::class.java))
        }
        val tabMine = createNavItem("我的设置", isSelected = false) {
            showSettingsDialog()
        }

        nav.addView(tabHome)
        nav.addView(tabRecords)
        nav.addView(tabMine)

        return nav
    }

    private fun createNavItem(
        label: String,
        isSelected: Boolean,
        onClick: () -> Unit
    ): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            setOnClickListener { onClick() }

            val labelTv = TextView(this@MainActivity).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                includeFontPadding = false
                typeface = if (isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextColor(if (isSelected) colorPrimary else colorTextSub)
            }

            addView(labelTv)
        }
    }

    // ==================== 业务逻辑与状态驱动 ====================

    private fun refreshAllStatus() {
        val isAccessibilityOk = BossAccessibilityService.isConnected()
        updateButtonStatus(accessibilityBtn, isAccessibilityOk)

        val isOverlayOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        updateButtonStatus(overlayBtn, isOverlayOk)

        val app = application as LuluApp
        val state = app.stateMachine.getCurrentState()
        updateEngineStateUi(state)

        loadDashboardData(app)
    }

    private fun updateButtonStatus(btn: TextView, isGranted: Boolean) {
        if (isGranted) {
            btn.text = "已就绪 ✨"
            btn.setTextColor(colorSuccess)
            btn.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp2px(20).toFloat()
                setColor(colorSuccessSoft)
            }
            btn.isEnabled = false
        } else {
            btn.text = "去开启"
            btn.setTextColor(colorPrimary)
            btn.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp2px(20).toFloat()
                setColor(colorPrimarySoft)
            }
            btn.isEnabled = true
        }
    }

    private fun updateEngineStateUi(state: EngineState) {
        if (state == EngineState.IDLE) {
            statusTextTv.text = "小鹿待命 (随时出发)"
        } else {
            statusTextTv.text = state.title
        }

        try {
            statusTextTv.setTextColor(Color.parseColor(state.indicatorColorHex))
        } catch (e: Exception) {
            statusTextTv.setTextColor(colorPrimary)
        }

        if (state.isOperating()) {
            mainActionButton.text = "☕ 鹿鹿喝口水 (暂停中)"
            mainActionButton.background = GradientDrawable().apply {
                cornerRadius = dp2px(14).toFloat()
                setColor(Color.parseColor("#F57C00"))
            }
        } else {
            mainActionButton.text = "🦌 启动鹿鹿 · 开始探路"
            mainActionButton.background = GradientDrawable().apply {
                cornerRadius = dp2px(14).toFloat()
                setColor(colorPrimary)
            }
        }
    }

    private fun loadDashboardData(app: LuluApp) {
        lifecycleScope.launch {
            val (todayChat, todayApplied) = withContext(Dispatchers.IO) {
                val db = app.database
                val cal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                }
                val start = cal.timeInMillis
                val end = System.currentTimeMillis()

                val chats = db.jobDao().getCommunicatedCountBetween(start, end)
                val applied = db.jobDao().getDeliveredCountBetween(start, end)
                Pair(chats, applied)
            }

            chatCountTv.text = todayChat.toString()
            deliveryCountTv.text = todayApplied.toString()

            val maxCount = app.configRepository.getMaxDailyGreetings()
            val percent = if (maxCount > 0) minOf(100, (todayChat * 100) / maxCount) else 0
            percentageTv.text = "$percent%"
        }
    }

    private fun handleMainActionClick() {
        val app = application as LuluApp
        val currentState = app.stateMachine.getCurrentState()

        if (currentState.isOperating()) {
            app.taskDispatcher.pause()
            updateEngineStateUi(EngineState.PAUSED)
            return
        }

        if (currentState == EngineState.PAUSED) {
            app.taskDispatcher.resume()
            updateEngineStateUi(EngineState.SCANNING)
            return
        }

        if (!BossAccessibilityService.isConnected()) {
            Toast.makeText(this, "请先开启【敲门开路】权限", Toast.LENGTH_SHORT).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先开启【悄悄支招】权限", Toast.LENGTH_SHORT).show()
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }

        if (!app.configRepository.hasValidApiKey()) {
            Toast.makeText(this, "请先在右上角【设置】中完成模型 API 配置", Toast.LENGTH_LONG).show()
            showSettingsDialog()
            return
        }

        val bossIntent = packageManager.getLaunchIntentForPackage(bossPackage)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
        if (bossIntent != null) {
            startActivity(bossIntent)
        } else {
            Toast.makeText(this, "未检测到 Boss 直聘安装", Toast.LENGTH_SHORT).show()
        }

        FloatingHUDService.startService(this)
        app.taskDispatcher.start(warmUpDelayMs = 4000L)
        updateEngineStateUi(EngineState.SCANNING)

        Toast.makeText(this, "🚀 鹿鹿已启航，正在跳转 Boss 直聘...", Toast.LENGTH_SHORT).show()
    }

    private fun showSettingsDialog() {
        val dialog = Dialog(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp2px(18).toFloat()
                setColor(Color.WHITE)
            }
            val p = dp2px(22)
            setPadding(p, p, p, p)
        }

        val title = TextView(this).apply {
            text = "🎒 鹿鹿的百宝袋"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextMain)
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp2px(12) }
        }
        container.addView(title)

        fun createSettingItem(icon: String, text: String, onClick: () -> Unit): View {
            return LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp2px(4), dp2px(12), dp2px(4), dp2px(12))
                setOnClickListener {
                    dialog.dismiss()
                    onClick()
                }

                val iconTv = TextView(this@MainActivity).apply {
                    this.text = icon
                    textSize = 16f
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    layoutParams = LinearLayout.LayoutParams(dp2px(24), dp2px(24))
                }

                val labelTv = TextView(this@MainActivity).apply {
                    this.text = text
                    textSize = 14f
                    setTextColor(colorTextMain)
                    includeFontPadding = false
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = dp2px(10) }
                }

                addView(iconTv)
                addView(labelTv)
            }
        }

        container.addView(createSettingItem("🔑", "模型 API 配置") {
            startActivity(Intent(this, ApiKeyConfigActivity::class.java))
        })
        container.addView(createSettingItem("📝", "个人简历与背景设定") {
            startActivity(Intent(this, ResumeEditorActivity::class.java))
        })
        container.addView(createSettingItem("🔋", "忽略电池优化 (防断流)") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                    )
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
        })

        dialog.setContentView(container)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.85).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == DispatcherBroadcasts.ACTION_ENGINE_STATE_CHANGED) {
                val newState = intent.getStringExtra(DispatcherBroadcasts.EXTRA_NEW_STATE) ?: return
                try {
                    val state = EngineState.valueOf(newState)
                    updateEngineStateUi(state)
                    val app = application as LuluApp
                    loadDashboardData(app)
                } catch (e: Exception) {
                    // 容错
                }
            }
        }
    }

    private fun registerStatusReceiver() {
        val filter = IntentFilter(DispatcherBroadcasts.ACTION_ENGINE_STATE_CHANGED)
        ContextCompat.registerReceiver(
            this,
            statusReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun createCardDrawable(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp2px(18).toFloat()
            setColor(colorCard)
            setStroke(dp2px(1), colorCardStroke)
        }
    }

    private fun dp2px(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density + 0.5f).toInt()
    }
}