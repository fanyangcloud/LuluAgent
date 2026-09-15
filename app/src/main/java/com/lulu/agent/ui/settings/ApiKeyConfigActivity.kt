package com.lulu.agent.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.lulu.agent.data.local.AppDatabase
import com.lulu.agent.data.pref.AppSettings
import com.lulu.agent.data.pref.EncryptedDataStore
import com.lulu.agent.data.repository.ConfigRepository
import com.lulu.agent.data.repository.JobRepository
import com.lulu.agent.llm.DeepSeekClient
import com.lulu.agent.llm.limiter.TokenUsageTracker
import com.lulu.agent.llm.prompt.PromptManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * DeepSeek API Key 凭证配置 (高质感精炼版)
 */
class ApiKeyConfigActivity : AppCompatActivity() {

    private lateinit var configRepository: ConfigRepository
    private lateinit var deepSeekClient: DeepSeekClient

    private lateinit var keyInput: EditText
    private lateinit var statusBadgeLayout: LinearLayout
    private lateinit var statusDot: View
    private lateinit var testStatusTv: TextView
    private lateinit var progressBar: ProgressBar

    // 规范视觉色盘
    private val colorBg = Color.parseColor("#F8F9FB")
    private val colorCard = Color.WHITE
    private val colorCardStroke = Color.parseColor("#EAECEF")
    private val colorPrimary = Color.parseColor("#FA6542")         // 鹿鹿暖杏橙
    private val colorPrimaryDark = Color.parseColor("#DE4F2C")     // 高对比深橙
    private val colorPrimarySoft = Color.parseColor("#FFF4F0")     // 极淡微桃粉
    private val colorTextMain = Color.parseColor("#1F2329")        // 高阶石板黑
    private val colorTextSub = Color.parseColor("#646A73")         // 次级深灰
    private val colorTextTip = Color.parseColor("#8F959E")         // 浅灰说明
    private val colorSuccess = Color.parseColor("#2BA471")         // 成功绿
    private val colorSuccessSoft = Color.parseColor("#EBF6F1")
    private val colorError = Color.parseColor("#E05244")           // 警戒红
    private val colorErrorSoft = Color.parseColor("#FEECE8")
    private val colorInputBg = Color.parseColor("#F4F6F9")         // 输入框底色

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        initDependencies()
        buildUi()
        loadCurrentKey()
    }

    private fun initDependencies() {
        val encryptedDataStore = EncryptedDataStore.getInstance(this)
        val appSettings = AppSettings.getInstance(this)
        configRepository = ConfigRepository(encryptedDataStore, appSettings)

        val db = AppDatabase.getInstance(this)
        val jobRepo = JobRepository(db)
        val tokenTracker = TokenUsageTracker(jobRepo)
        val promptManager = PromptManager(configRepository)

        deepSeekClient = DeepSeekClient.getInstance(
            configRepository,
            jobRepo,
            tokenTracker,
            promptManager
        )
    }

    private fun buildUi() {
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colorBg)
        }

        // 1. 顶部标题栏
        rootLayout.addView(createTopBar())

        // 2. 主体滚动区
        val rootScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp2px(16)
            setPadding(p, dp2px(6), p, dp2px(32))
        }

        // 3. 精炼复制指引卡片
        container.addView(createGuideCard())

        // 4. API Key 配置核心卡片
        val inputCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createCardDrawable()
            val cp = dp2px(16)
            setPadding(cp, cp, cp, cp)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp2px(12) }
        }

        val inputLabel = TextView(this).apply {
            text = "API Key"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextMain)
            includeFontPadding = false
        }
        inputCard.addView(inputLabel)

        keyInput = EditText(this).apply {
            hint = "请输入 sk- 开头的密钥"
            setHintTextColor(colorTextTip)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(colorTextMain)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            background = GradientDrawable().apply {
                cornerRadius = dp2px(10).toFloat()
                setColor(colorInputBg)
                setStroke(dp2px(1), Color.parseColor("#E5E8EC"))
            }
            val ep = dp2px(12)
            setPadding(ep, ep, ep, ep)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp2px(10) }
        }
        inputCard.addView(keyInput)

        // 状态胶囊徽章 (美化重构核心)
        statusBadgeLayout = createStatusBadge()
        inputCard.addView(statusBadgeLayout)

        // 深度测试连接按钮 (清晰高对比度轮廓按钮)
        val pingBtn = TextView(this).apply {
            text = "测试连通性"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(colorPrimaryDark)
            includeFontPadding = false
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp2px(10).toFloat()
                setColor(Color.WHITE)
                setStroke(dp2px(1.5f), colorPrimary)
            }
            val bp = dp2px(11)
            setPadding(bp, bp, bp, bp)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp2px(14) }
            setOnClickListener { performPingTest() }
        }
        inputCard.addView(pingBtn)

        container.addView(inputCard)

        // 5. 底部主色保存按钮
        val saveBtn = TextView(this).apply {
            text = "保存配置"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            includeFontPadding = false
            background = GradientDrawable().apply {
                cornerRadius = dp2px(12).toFloat()
                setColor(colorPrimary)
            }
            val bp = dp2px(13)
            setPadding(bp, bp, bp, bp)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp2px(16) }
            setOnClickListener { saveKey() }
        }
        container.addView(saveBtn)

        // 6. 安全说明脚注
        val tipFooter = TextView(this).apply {
            text = "密钥经 Android Keystore (AES-256) 本地加密，直接请求官方节点。"
            textSize = 11f
            setTextColor(colorTextTip)
            gravity = Gravity.CENTER
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp2px(14) }
        }
        container.addView(tipFooter)

        rootScroll.addView(container)
        rootLayout.addView(rootScroll)
        setContentView(rootLayout)
    }

    /**
     * 顶部标题栏
     */
    private fun createTopBar(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp2px(16), dp2px(12), dp2px(16), dp2px(12))
            setBackgroundColor(colorBg)

            val backBtn = TextView(this@ApiKeyConfigActivity).apply {
                text = "‹"
                textSize = 28f
                setTextColor(colorTextMain)
                gravity = Gravity.CENTER
                includeFontPadding = false
                layoutParams = LinearLayout.LayoutParams(dp2px(36), dp2px(36))
                setOnClickListener { finish() }
            }

            val title = TextView(this@ApiKeyConfigActivity).apply {
                text = "DeepSeek 凭证配置"
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorTextMain)
                includeFontPadding = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp2px(4) }
            }

            addView(backBtn)
            addView(title)
        }
    }

    /**
     * 精炼获取指引卡片
     */
    private fun createGuideCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createCardDrawable()
            val p = dp2px(14)
            setPadding(p, p, p, p)
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val guideTitle = TextView(this).apply {
            text = "获取途径"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextMain)
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val copyLinkBtn = TextView(this).apply {
            text = "复制官网地址"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorPrimaryDark)
            includeFontPadding = false
            background = GradientDrawable().apply {
                cornerRadius = dp2px(6).toFloat()
                setColor(colorPrimarySoft)
            }
            setPadding(dp2px(8), dp2px(4), dp2px(8), dp2px(4))
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("DeepSeek Console", "https://platform.deepseek.com")
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this@ApiKeyConfigActivity, "已复制官网地址到剪贴板", Toast.LENGTH_SHORT).show()
            }
        }

        topRow.addView(guideTitle)
        topRow.addView(copyLinkBtn)
        card.addView(topRow)

        val stepsText = TextView(this).apply {
            text = "1. 浏览器访问 platform.deepseek.com 并登录\n2. 点击左侧「API keys」→「创建 API key」并复制填入下方"
            textSize = 12f
            setTextColor(colorTextSub)
            setLineSpacing(dp2px(3).toFloat(), 1.0f)
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp2px(8) }
        }
        card.addView(stepsText)

        return card
    }

    /**
     * 状态指示胶囊 (Pill Badge)
     */
    private fun createStatusBadge(): LinearLayout {
        val badge = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = createPillDrawable("#F0F2F5")
            setPadding(dp2px(10), dp2px(5), dp2px(12), dp2px(5))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp2px(10) }
        }

        statusDot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#9E9E9E"))
            }
            layoutParams = LinearLayout.LayoutParams(dp2px(6), dp2px(6)).apply {
                marginEnd = dp2px(6)
            }
        }

        progressBar = ProgressBar(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp2px(12), dp2px(12)).apply {
                marginEnd = dp2px(6)
            }
        }

        testStatusTv = TextView(this).apply {
            text = "未配置"
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(colorTextSub)
            includeFontPadding = false
        }

        badge.addView(statusDot)
        badge.addView(progressBar)
        badge.addView(testStatusTv)

        return badge
    }

    private fun updateStatusBadge(type: BadgeStatus, message: String) {
        when (type) {
            BadgeStatus.UNCONFIGURED -> {
                statusBadgeLayout.background = createPillDrawable("#F0F2F5")
                (statusDot.background as? GradientDrawable)?.setColor(Color.parseColor("#9E9E9E"))
                statusDot.visibility = View.VISIBLE
                progressBar.visibility = View.GONE
                testStatusTv.setTextColor(colorTextSub)
            }
            BadgeStatus.CONFIGURED -> {
                statusBadgeLayout.background = createPillDrawable("#EDF4FE")
                (statusDot.background as? GradientDrawable)?.setColor(Color.parseColor("#2E7BE6"))
                statusDot.visibility = View.VISIBLE
                progressBar.visibility = View.GONE
                testStatusTv.setTextColor(Color.parseColor("#2E7BE6"))
            }
            BadgeStatus.TESTING -> {
                statusBadgeLayout.background = createPillDrawable(colorPrimarySoft)
                statusDot.visibility = View.GONE
                progressBar.visibility = View.VISIBLE
                testStatusTv.setTextColor(colorPrimaryDark)
            }
            BadgeStatus.SUCCESS -> {
                statusBadgeLayout.background = createPillDrawable(colorSuccessSoft)
                (statusDot.background as? GradientDrawable)?.setColor(colorSuccess)
                statusDot.visibility = View.VISIBLE
                progressBar.visibility = View.GONE
                testStatusTv.setTextColor(colorSuccess)
            }
            BadgeStatus.FAILED -> {
                statusBadgeLayout.background = createPillDrawable(colorErrorSoft)
                (statusDot.background as? GradientDrawable)?.setColor(colorError)
                statusDot.visibility = View.VISIBLE
                progressBar.visibility = View.GONE
                testStatusTv.setTextColor(colorError)
            }
        }
        testStatusTv.text = message
    }

    private fun loadCurrentKey() {
        val currentKey = configRepository.getDeepSeekApiKey()
        if (currentKey.isNotEmpty()) {
            keyInput.setText(currentKey)
            val masked = if (currentKey.length > 12) {
                "${currentKey.take(7)}...${currentKey.takeLast(4)}"
            } else currentKey
            updateStatusBadge(BadgeStatus.CONFIGURED, "已配置 ($masked)")
        } else {
            updateStatusBadge(BadgeStatus.UNCONFIGURED, "未配置 API Key")
        }
    }

    private fun performPingTest() {
        val inputKey = keyInput.text.toString().trim()
        if (inputKey.isBlank()) {
            Toast.makeText(this, "请先输入 API Key", Toast.LENGTH_SHORT).show()
            return
        }

        updateStatusBadge(BadgeStatus.TESTING, "正在连接官方节点...")

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                deepSeekClient.testConnection(inputKey)
            }
            result.fold(
                onSuccess = {
                    updateStatusBadge(BadgeStatus.SUCCESS, "连通正常 · DeepSeek 响应就绪")
                    Toast.makeText(this@ApiKeyConfigActivity, "连通测试通过！", Toast.LENGTH_SHORT).show()
                },
                onFailure = { err ->
                    val errMsg = err.message?.take(20) ?: "网络异常"
                    updateStatusBadge(BadgeStatus.FAILED, "连通失败 ($errMsg)")
                }
            )
        }
    }

    private fun saveKey() {
        val inputKey = keyInput.text.toString().trim()
        configRepository.setDeepSeekApiKey(inputKey)
        Toast.makeText(this, "凭证已安全保存", Toast.LENGTH_SHORT).show()
        finish()
    }

    private enum class BadgeStatus {
        UNCONFIGURED, CONFIGURED, TESTING, SUCCESS, FAILED
    }

    private fun createCardDrawable(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp2px(14).toFloat()
            setColor(colorCard)
            setStroke(dp2px(1), colorCardStroke)
        }
    }

    private fun createPillDrawable(hexColor: String): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp2px(100).toFloat()
            setColor(Color.parseColor(hexColor))
        }
    }

    private fun createPillDrawable(intColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp2px(100).toFloat()
            setColor(intColor)
        }
    }

    private fun dp2px(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density + 0.5f).toInt()
    }

    private fun dp2px(dp: Float): Int {
        val density = resources.displayMetrics.density
        return (dp * density + 0.5f).toInt()
    }
}