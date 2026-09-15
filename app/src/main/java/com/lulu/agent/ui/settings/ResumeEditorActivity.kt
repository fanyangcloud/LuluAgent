package com.lulu.agent.ui.settings

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.lulu.agent.data.pref.AppSettings
import com.lulu.agent.data.pref.EncryptedDataStore
import com.lulu.agent.data.repository.ConfigRepository

/**
 * 求职者核心简历与背景知识库编辑器 (Markdown 格式 · 鹿鹿治愈温润风)
 */
class ResumeEditorActivity : AppCompatActivity() {

    private lateinit var configRepository: ConfigRepository
    private lateinit var resumeEdit: EditText

    // 🌟 鹿鹿统一治愈系色盘
    private val colorBg = Color.parseColor("#F8F9FB")
    private val colorCard = Color.WHITE
    private val colorCardStroke = Color.parseColor("#F0F2F5")
    private val colorPrimary = Color.parseColor("#FA6542")       // 鹿鹿治愈暖杏橙
    private val colorPrimarySoft = Color.parseColor("#FFF4F0")   // 极淡微桃粉
    private val colorTextMain = Color.parseColor("#1F2329")      // 高阶石板黑
    private val colorTextSub = Color.parseColor("#8F959E")       // 优雅次级灰
    private val colorInputBg = Color.parseColor("#F4F6F9")       // 输入框浅底色

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide() // 隐藏安卓原生 ActionBar
        configRepository = ConfigRepository(
            EncryptedDataStore.getInstance(this),
            AppSettings.getInstance(this)
        )
        buildUi()
        loadResume()
    }

    private fun buildUi() {
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colorBg)
        }

        // 1. 顶部自定义温润标题栏
        rootLayout.addView(createTopBar())

        // 2. 内容滑动区域
        val rootScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp2px(16)
            setPadding(p, dp2px(4), p, dp2px(32))
        }

        // 顶部功能提示卡片
        val descCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createCardDrawable()
            val cp = dp2px(16)
            setPadding(cp, cp, cp, cp)
        }

        val descTv = TextView(this).apply {
            text = "📝 请填入你的个人简历（建议 Markdown 格式）。DeepSeek 会根据你的真实技术栈与项目成果对目标岗位进行契合度打分，并构思针对性的破冰开场白。"
            textSize = 12f
            setTextColor(colorTextSub)
            setLineSpacing(dp2px(3).toFloat(), 1.0f)
            includeFontPadding = false
        }
        descCard.addView(descTv)
        container.addView(descCard)

        // 辅助操作按钮栏
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp2px(14) }
        }

        val templateBtn = TextView(this).apply {
            text = "📋 填入参考模板"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(colorPrimary)
            background = GradientDrawable().apply {
                cornerRadius = dp2px(12).toFloat()
                setColor(colorPrimarySoft)
            }
            val bp = dp2px(11)
            setPadding(bp, bp, bp, bp)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp2px(10)
            }
            setOnClickListener { insertTemplate() }
        }

        val saveBtn = TextView(this).apply {
            text = "💾 保存简历"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp2px(12).toFloat()
                setColor(colorPrimary)
            }
            val bp = dp2px(11)
            setPadding(bp, bp, bp, bp)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { saveResume() }
        }

        btnRow.addView(templateBtn)
        btnRow.addView(saveBtn)
        container.addView(btnRow)

        // Markdown 编辑区卡片
        val editorCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createCardDrawable()
            val ep = dp2px(12)
            setPadding(ep, ep, ep, ep)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp2px(14) }
        }

        resumeEdit = EditText(this).apply {
            hint = "# 个人简历\n\n## 核心技术栈\n- 精通 Android Framework 与 Jetpack...\n\n## 工作经历\n- 深度参与端侧 Agent 架构设计..."
            setHintTextColor(colorTextSub)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(colorTextMain)
            gravity = Gravity.TOP or Gravity.START
            minLines = 18
            background = GradientDrawable().apply {
                cornerRadius = dp2px(10).toFloat()
                setColor(colorInputBg)
            }
            val pad = dp2px(12)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        editorCard.addView(resumeEdit)
        container.addView(editorCard)

        rootScroll.addView(container)
        rootLayout.addView(rootScroll)
        setContentView(rootLayout)
    }

    private fun createTopBar(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp2px(16), dp2px(12), dp2px(20), dp2px(10))
            setBackgroundColor(colorBg)

            val backBtn = TextView(this@ResumeEditorActivity).apply {
                text = "‹"
                textSize = 28f
                setTextColor(colorTextMain)
                gravity = Gravity.CENTER
                includeFontPadding = false
                layoutParams = LinearLayout.LayoutParams(dp2px(36), dp2px(36))
                setOnClickListener { finish() }
            }

            val titleCol = LinearLayout(this@ResumeEditorActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp2px(4)
                }
            }

            val title = TextView(this@ResumeEditorActivity).apply {
                text = "个人简历与背景设定 📝"
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorTextMain)
                includeFontPadding = false
            }

            val subtitle = TextView(this@ResumeEditorActivity).apply {
                text = "用于岗位契合度打分与破冰开场白定制"
                textSize = 11f
                setTextColor(colorTextSub)
                includeFontPadding = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp2px(2) }
            }

            titleCol.addView(title)
            titleCol.addView(subtitle)

            addView(backBtn)
            addView(titleCol)
        }
    }

    private fun loadResume() {
        val currentResume = configRepository.getResumeMarkdown()
        if (currentResume.isNotBlank()) {
            resumeEdit.setText(currentResume)
        }
    }

    private fun insertTemplate() {
        val template = """# 资深 Android 开发工程师

## 基本信息
- 经验: 6年 | 学历: 本科 | 意向城市: 远程 / 全国
- 期望薪资: 25K - 40K

## 核心专业优势
1. 深入掌握 Kotlin 协程/Flow 异步响应式编程，精通 Jetpack 架构组件与 MVVM/MVI 架构。
2. 具备大型 App 性能调优经验（启动优化、内存抖动与 OOM 治理、卡顿监控、APK 瘦身）。
3. 熟悉 Android Framework 核心机制（Handler、Binder IPC、WMS/AMS、View 绘制流程）。
4. 拥有无障碍服务 (AccessibilityService) 深度开发经验，擅长拟人化手势仿真与节点安全生命周期管理。

## 代表项目经历
- **大型电商/社交 App 核心架构演进**：主导模块化与组件化拆分，启动时间降低 35%，线上崩溃率控制在 0.05% 以下。
- **端侧 AI 与自动化智能体研发**：主导 LLM 与客户端协同架构，实现端侧自主视觉感知与语义推演。"""
        resumeEdit.setText(template.trimIndent())
        Toast.makeText(this, "已载入标准简历模板", Toast.LENGTH_SHORT).show()
    }

    private fun saveResume() {
        val text = resumeEdit.text.toString().trim()
        configRepository.setResumeMarkdown(text)
        Toast.makeText(this, "简历已安全加密更新", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun createCardDrawable(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp2px(16).toFloat()
            setColor(colorCard)
            setStroke(dp2px(1), colorCardStroke)
        }
    }

    private fun dp2px(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density + 0.5f).toInt()
    }
}