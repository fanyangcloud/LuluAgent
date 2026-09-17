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
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.lulu.agent.R
import com.lulu.agent.data.local.AppDatabase
import com.lulu.agent.data.pref.AppSettings
import com.lulu.agent.data.pref.EncryptedDataStore
import com.lulu.agent.data.repository.ConfigRepository
import com.lulu.agent.data.repository.JobRepository
import com.lulu.agent.llm.DeepSeekClient
import com.lulu.agent.llm.config.ApiProtocol
import com.lulu.agent.llm.config.LlmConfig
import com.lulu.agent.llm.limiter.TokenUsageTracker
import com.lulu.agent.llm.prompt.PromptManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * OpenAI 兼容 API 配置（极简小白友好 + 高级折叠版）
 */
class ApiKeyConfigActivity : AppCompatActivity() {

    private lateinit var configRepository: ConfigRepository
    private lateinit var deepSeekClient: DeepSeekClient

    private lateinit var keyInput: EditText
    private lateinit var baseUrlInput: EditText
    private lateinit var modelInput: EditText
    private lateinit var protocolInput: Spinner
    private lateinit var advancedContainer: LinearLayout
    private lateinit var toggleAdvancedBtn: TextView
    private lateinit var pingButton: TextView

    private var testing = false
    private var inputRevision = 0L
    private lateinit var statusBadgeLayout: LinearLayout
    private lateinit var statusDot: View
    private lateinit var testStatusTv: TextView
    private lateinit var progressBar: ProgressBar

    // 本地测试状态缓存
    private val testStatusPrefs by lazy { getSharedPreferences("llm_test_record", Context.MODE_PRIVATE) }

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

        for (input in listOf(keyInput, baseUrlInput, modelInput)) {
            input.doAfterTextChanged { configurationEdited() }
        }
        var selectedProtocol = protocolInput.selectedItemPosition
        protocolInput.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position != selectedProtocol) {
                    selectedProtocol = position
                    configurationEdited()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
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

        // 3. 精炼获取指引卡片
        container.addView(createGuideCard())

        // 4. API 配置核心卡片
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
            hint = "请输入 sk- 开头的 API Key"
            setHintTextColor(colorTextTip)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(colorTextMain)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
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

        // 🌟 进阶折叠容器：默认对小白隐藏，保留 PR 的所有可配置参数
        advancedContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        baseUrlInput = createConfigInput(advancedContainer, "Base URL（API 根地址）", "https://api.deepseek.com/")
        modelInput = createConfigInput(advancedContainer, "模型名称", "deepseek-chat")

        advancedContainer.addView(TextView(this).apply {
            text = "接口协议"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextMain)
            setPadding(0, dp2px(12), 0, dp2px(4))
        })
        protocolInput = Spinner(this).apply {
            adapter = ArrayAdapter(this@ApiKeyConfigActivity, android.R.layout.simple_spinner_dropdown_item,
                ApiProtocol.entries.map { it.label })
        }
        advancedContainer.addView(protocolInput)

        // 🌟 实体化次级按钮：高级设置折叠切换开关
        toggleAdvancedBtn = TextView(this).apply {
            text = "高级设置（自定义服务商 / 模型） ⌄"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(colorTextSub)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp2px(8).toFloat()
                setColor(colorInputBg)
                setStroke(dp2px(1), Color.parseColor("#E5E8EC"))
            }
            val vPad = dp2px(9)
            val hPad = dp2px(12)
            setPadding(hPad, vPad, hPad, vPad)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp2px(12)
                bottomMargin = dp2px(4)
            }
            setOnClickListener {
                val willShow = advancedContainer.visibility == View.GONE
                advancedContainer.visibility = if (willShow) View.VISIBLE else View.GONE
                updateToggleBtnStyle(willShow)
            }
        }
        inputCard.addView(toggleAdvancedBtn)
        inputCard.addView(advancedContainer)

        // 状态胶囊徽章
        statusBadgeLayout = createStatusBadge()
        inputCard.addView(statusBadgeLayout)

        // 深度测试连接按钮
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
        pingButton = pingBtn
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
            text = getString(R.string.llm_config_footer)
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
     * 高级设置按钮视觉切换
     */
    private fun updateToggleBtnStyle(isExpanded: Boolean) {
        if (isExpanded) {
            toggleAdvancedBtn.text = "收起高级设置 ⌃"
            toggleAdvancedBtn.setTextColor(colorPrimaryDark)
            toggleAdvancedBtn.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp2px(8).toFloat()
                setColor(colorPrimarySoft)
                setStroke(dp2px(1), Color.parseColor("#F8C9BC"))
            }
        } else {
            toggleAdvancedBtn.text = "高级设置（自定义服务商 / 模型） ⌄"
            toggleAdvancedBtn.setTextColor(colorTextSub)
            toggleAdvancedBtn.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp2px(8).toFloat()
                setColor(colorInputBg)
                setStroke(dp2px(1), Color.parseColor("#E5E8EC"))
            }
        }
    }

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
                text = getString(R.string.llm_config_title)
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
            text = "配置说明"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextMain)
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val copyLinkBtn = TextView(this).apply {
            text = getString(R.string.llm_deepseek_console)
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
            text = "默认使用 DeepSeek，请前往DeepSeek后台复制Key，粘贴至下方即可。\n如使用Claude、Chatgpt、Gemini 等，可展开高级设置修改。"
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

    private fun createConfigInput(container: LinearLayout, label: String, placeholder: String): EditText {
        container.addView(TextView(this).apply {
            text = label
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextMain)
            setPadding(0, dp2px(12), 0, dp2px(4))
        })
        val input = EditText(this).apply {
            hint = placeholder
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(colorTextMain)
            setHintTextColor(colorTextTip)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            background = GradientDrawable().apply {
                cornerRadius = dp2px(10).toFloat()
                setColor(colorInputBg)
                setStroke(dp2px(1), Color.parseColor("#E5E8EC"))
            }
            val ep = dp2px(10)
            setPadding(ep, ep, ep, ep)
        }
        container.addView(input, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp2px(4)
        })
        return input
    }

    private fun configurationEdited() {
        inputRevision++
        updateStatusBadge(BadgeStatus.UNCONFIGURED, "配置已更改，请重新测试")
    }

    private fun readConfig(): LlmConfig = LlmConfig(
        baseUrlInput.text.toString(), keyInput.text.toString(), modelInput.text.toString(),
        ApiProtocol.entries[protocolInput.selectedItemPosition]
    ).validated()

    private fun loadCurrentKey() {
        val stored = runCatching { configRepository.getLlmConfig() }
        val config = stored.getOrDefault(LlmConfig())
        keyInput.setText(config.apiKey)
        baseUrlInput.setText(config.baseUrl)
        modelInput.setText(config.model)
        protocolInput.setSelection(config.protocol.ordinal)

        // 判断是否为非默认配置，若是则自动展开高级设置，否则默认折叠保持清爽
        val isCustom = config.baseUrl != "https://api.deepseek.com/" ||
                config.model != "deepseek-chat" ||
                config.protocol != ApiProtocol.CHAT_COMPLETIONS

        if (isCustom) {
            advancedContainer.visibility = View.VISIBLE
            updateToggleBtnStyle(true)
        } else {
            advancedContainer.visibility = View.GONE
            updateToggleBtnStyle(false)
        }

        if (stored.isFailure) {
            updateStatusBadge(BadgeStatus.FAILED, "配置读取失败，请重新填写")
        } else if (config.apiKey.isBlank()) {
            updateStatusBadge(BadgeStatus.UNCONFIGURED, "未配置 API Key")
        } else {
            val masked = if (config.apiKey.length > 12) {
                "${config.apiKey.take(7)}...${config.apiKey.takeLast(4)}"
            } else config.apiKey

            // 🌟 核心改进 1：若该 Key 此前测试连通成功，直接显示绿色“已就绪”，拒绝显示“尚未测试”
            val wasTested = testStatusPrefs.getBoolean("tested_${config.apiKey.trim()}", false)
            if (wasTested) {
                updateStatusBadge(BadgeStatus.SUCCESS, "连通正常 · 已就绪 ($masked)")
            } else {
                updateStatusBadge(BadgeStatus.CONFIGURED, "已配置 ($masked)")
            }
        }
    }

    private fun performPingTest() {
        if (testing) return
        val config = try { readConfig() } catch (e: IllegalArgumentException) {
            updateStatusBadge(BadgeStatus.FAILED, e.message ?: "配置无效")
            return
        }
        val revision = inputRevision
        testing = true
        pingButton.isEnabled = false
        updateStatusBadge(BadgeStatus.TESTING, "正在测试所选接口...")
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { deepSeekClient.testConnection(config) }
                if (inputRevision != revision) return@launch
                result.fold(
                    onSuccess = {
                        testStatusPrefs.edit().putBoolean("tested_${config.apiKey.trim()}", true).apply()
                        updateStatusBadge(BadgeStatus.SUCCESS, "连通正常 · 模型响应就绪")
                    },
                    onFailure = { error ->
                        testStatusPrefs.edit().putBoolean("tested_${config.apiKey.trim()}", false).apply()
                        updateStatusBadge(BadgeStatus.FAILED, error.message ?: "测试失败，请检查 API 配置")
                    }
                )
            } finally {
                testing = false
                pingButton.isEnabled = true
            }
        }
    }

    private fun saveKey() {
        val config = try { readConfig() } catch (e: IllegalArgumentException) {
            updateStatusBadge(BadgeStatus.FAILED, e.message ?: "配置无效")
            return
        }
        configRepository.setLlmConfig(config)
        Toast.makeText(this, "配置已安全保存", Toast.LENGTH_SHORT).show()
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