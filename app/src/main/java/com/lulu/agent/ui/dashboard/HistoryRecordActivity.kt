package com.lulu.agent.ui.dashboard

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lulu.agent.data.local.AppDatabase
import com.lulu.agent.data.local.entity.JobEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 鹿鹿 (Lulu) - 岗位投递与评估手账 (温润手账治愈风)
 */
class HistoryRecordActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyTv: TextView
    private val records = mutableListOf<JobEntity>()
    private val adapter = HistoryAdapter(records)

    private val colorBg = Color.parseColor("#F8F9FB")
    private val colorCard = Color.WHITE
    private val colorCardStroke = Color.parseColor("#F0F2F5")
    private val colorPrimary = Color.parseColor("#FA6542")
    private val colorTextMain = Color.parseColor("#1F2329")
    private val colorTextSub = Color.parseColor("#8F959E")
    private val colorThoughtBg = Color.parseColor("#F4F6F9")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(buildContentView())
        loadData()
    }

    private fun buildContentView(): View {
        val root = RelativeLayout(this).apply {
            setBackgroundColor(colorBg)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val topBar = createTopBar().apply { id = View.generateViewId() }
        val topBarParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.WRAP_CONTENT
        ).apply { addRule(RelativeLayout.ALIGN_PARENT_TOP) }
        root.addView(topBar, topBarParams)

        val listContainer = FrameLayout(this).apply {
            val p = dp2px(4)
            setPadding(0, p, 0, dp2px(12))
        }
        val listParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.MATCH_PARENT
        ).apply {
            addRule(RelativeLayout.BELOW, topBar.id)
        }

        recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@HistoryRecordActivity)
            adapter = this@HistoryRecordActivity.adapter
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        emptyTv = TextView(this).apply {
            text = "🦌 还没有探路脚印呢\n启动小鹿后，每一次敲门和评价都会温存在这里~"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(colorTextSub)
            setLineSpacing(dp2px(4).toFloat(), 1.0f)
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }

        listContainer.addView(recyclerView)
        listContainer.addView(emptyTv)
        root.addView(listContainer, listParams)

        return root
    }

    private fun createTopBar(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp2px(16), dp2px(12), dp2px(20), dp2px(10))
            setBackgroundColor(colorBg)

            val backBtn = TextView(this@HistoryRecordActivity).apply {
                text = "‹"
                textSize = 28f
                setTextColor(colorTextMain)
                gravity = Gravity.CENTER
                includeFontPadding = false
                layoutParams = LinearLayout.LayoutParams(dp2px(36), dp2px(36))
                setOnClickListener { finish() }
            }

            val titleCol = LinearLayout(this@HistoryRecordActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp2px(4)
                }
            }

            val title = TextView(this@HistoryRecordActivity).apply {
                text = "小鹿的探路手账 🐾"
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorTextMain)
                includeFontPadding = false
            }

            val subtitle = TextView(this@HistoryRecordActivity).apply {
                text = "记录替你敲开的每一扇门与每一次权衡"
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

    private fun loadData() {
        lifecycleScope.launch {
            val db = AppDatabase.getInstance(this@HistoryRecordActivity)
            val list = withContext(Dispatchers.IO) {
                db.jobDao().getRecentJobs(limit = 100)
            }

            records.clear()
            records.addAll(list)
            adapter.notifyDataSetChanged()

            emptyTv.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    inner class HistoryAdapter(private val dataList: List<JobEntity>) :
        RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val titleTv: TextView = view.findViewWithTag("title")
            val salaryTv: TextView = view.findViewWithTag("salary")
            val companyTv: TextView = view.findViewWithTag("company")
            val statusChip: TextView = view.findViewWithTag("status")
            val reasonTv: TextView = view.findViewWithTag("reason")
            val scoreTv: TextView = view.findViewWithTag("score")
            val timeTv: TextView = view.findViewWithTag("time")
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val card = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = dp2px(16).toFloat()
                    setColor(colorCard)
                    setStroke(dp2px(1), colorCardStroke)
                }
                val p = dp2px(16)
                setPadding(p, p, p, p)
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                ).apply {
                    val m = dp2px(10)
                    setMargins(dp2px(16), dp2px(5), dp2px(16), m)
                }
            }

            val row1 = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val title = TextView(parent.context).apply {
                tag = "title"
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorTextMain)
                includeFontPadding = false
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val salary = TextView(parent.context).apply {
                tag = "salary"
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorPrimary)
                includeFontPadding = false
            }
            row1.addView(title)
            row1.addView(salary)
            card.addView(row1)

            val row2 = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp2px(6) }
            }
            val comp = TextView(parent.context).apply {
                tag = "company"
                textSize = 12f
                setTextColor(colorTextSub)
                includeFontPadding = false
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp2px(8)
                }
            }
            val status = TextView(parent.context).apply {
                tag = "status"
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
                setPadding(dp2px(8), dp2px(3), dp2px(8), dp2px(3))
            }
            row2.addView(comp)
            row2.addView(status)
            card.addView(row2)

            val bubble = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = dp2px(10).toFloat()
                    setColor(colorThoughtBg)
                }
                val bp = dp2px(10)
                setPadding(bp, bp, bp, bp)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp2px(10) }
            }

            val reason = TextView(parent.context).apply {
                tag = "reason"
                textSize = 12f
                setTextColor(Color.parseColor("#4E5969"))
                setLineSpacing(dp2px(2).toFloat(), 1.0f)
                maxLines = 4
                includeFontPadding = false
            }
            bubble.addView(reason)
            card.addView(bubble)

            val row4 = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp2px(10) }
            }
            val time = TextView(parent.context).apply {
                tag = "time"
                textSize = 11f
                setTextColor(colorTextSub)
                includeFontPadding = false
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val score = TextView(parent.context).apply {
                tag = "score"
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
                setPadding(dp2px(6), dp2px(2), dp2px(6), dp2px(2))
            }
            row4.addView(time)
            row4.addView(score)
            card.addView(row4)

            return ViewHolder(card)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = dataList[position]
            holder.titleTv.text = item.title
            holder.salaryTv.text = item.salaryText

            val compName = item.companyName.trim()
            val cityName = item.city.trim().ifBlank { "全国" }
            holder.companyTv.text = if (compName.isNotEmpty()) "$compName · $cityName" else cityName

            // 🌟 核心状态标签渲染（支持熔断与深入沟通）
            if (item.isChatClosed) {
                holder.statusChip.text = "已熔断回绝 ⛔"
                holder.statusChip.setTextColor(Color.parseColor("#E05244"))
                holder.statusChip.background = getPillDrawable("#FEECE8")
                val reason = item.evalReason.ifBlank { "触发求职底线熔断" }
                holder.reasonTv.text = "🚨 鹿鹿熔断：$reason"
            } else {
                when (item.status) {
                    JobEntity.STATUS_CHATTING -> {
                        holder.statusChip.text = "深入沟通中 💬"
                        holder.statusChip.setTextColor(Color.parseColor("#009688"))
                        holder.statusChip.background = getPillDrawable("#E0F2F1")
                        val cleanReason = item.evalReason.ifBlank { "正在与招聘方多轮交流中..." }
                        holder.reasonTv.text = "🦌 鹿鹿跟进：$cleanReason"
                    }
                    JobEntity.STATUS_DELIVERED -> {
                        holder.statusChip.text = "已投递 ✨"
                        holder.statusChip.setTextColor(Color.parseColor("#2BA471"))
                        holder.statusChip.background = getPillDrawable("#EBF6F1")
                        val cleanReason = item.evalReason.ifBlank { "已完成破冰打招呼与简历投递" }
                        holder.reasonTv.text = "🦌 鹿鹿评语：$cleanReason"
                    }
                    JobEntity.STATUS_COMMUNICATED -> {
                        holder.statusChip.text = "已打招呼 💬"
                        holder.statusChip.setTextColor(Color.parseColor("#FA6542"))
                        holder.statusChip.background = getPillDrawable("#FFF4F0")
                        val cleanReason = item.evalReason.ifBlank { "已成功发起破冰问候" }
                        holder.reasonTv.text = "🦌 鹿鹿评语：$cleanReason"
                    }
                    JobEntity.STATUS_EVALUATED -> {
                        holder.statusChip.text = "契合度高 🎯"
                        holder.statusChip.setTextColor(Color.parseColor("#2E7BE6"))
                        holder.statusChip.background = getPillDrawable("#EDF4FE")
                        val cleanReason = item.evalReason.ifBlank { "模型研判契合度高" }
                        holder.reasonTv.text = "🦌 鹿鹿评语：$cleanReason"
                    }
                    JobEntity.STATUS_REJECTED -> {
                        holder.statusChip.text = "已跳过"
                        holder.statusChip.setTextColor(Color.parseColor("#E05244"))
                        holder.statusChip.background = getPillDrawable("#FEECE8")
                        val cleanReason = item.evalReason.ifBlank { "岗位评估未达标" }
                        holder.reasonTv.text = "🦌 鹿鹿评语：$cleanReason"
                    }
                    JobEntity.STATUS_FILTERED_OUT -> {
                        holder.statusChip.text = "规则过滤"
                        holder.statusChip.setTextColor(Color.parseColor("#E05244"))
                        holder.statusChip.background = getPillDrawable("#FEECE8")
                        val cleanReason = item.evalReason.ifBlank { "命中本地过滤规则" }
                        holder.reasonTv.text = "🦌 鹿鹿评语：$cleanReason"
                    }
                    else -> {
                        holder.statusChip.text = item.status
                        holder.statusChip.setTextColor(Color.parseColor("#8F959E"))
                        holder.statusChip.background = getPillDrawable("#F0F2F5")
                        holder.reasonTv.text = "🦌 鹿鹿评语：${item.evalReason.ifBlank { "正在分析岗位..." }}"
                    }
                }
            }

            if (item.matchScore >= 70) {
                holder.scoreTv.text = "契合度: ${item.matchScore}分"
                holder.scoreTv.setTextColor(Color.parseColor("#2BA471"))
                holder.scoreTv.background = getPillDrawable("#EBF6F1")
            } else if (item.matchScore in 0..69) {
                holder.scoreTv.text = "契合度: ${item.matchScore}分"
                holder.scoreTv.setTextColor(Color.parseColor("#8F959E"))
                holder.scoreTv.background = getPillDrawable("#F0F2F5")
            } else {
                holder.scoreTv.text = "契合度: --"
                holder.scoreTv.setTextColor(colorTextSub)
                holder.scoreTv.background = null
            }

            val sdf = SimpleDateFormat("MM月dd日 HH:mm", Locale.getDefault())
            holder.timeTv.text = sdf.format(Date(item.updatedAt))
        }

        override fun getItemCount(): Int = dataList.size

        private fun getPillDrawable(bgHex: String): GradientDrawable {
            return GradientDrawable().apply {
                cornerRadius = dp2px(100).toFloat()
                setColor(Color.parseColor(bgHex))
            }
        }
    }

    private fun dp2px(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density + 0.5f).toInt()
    }
}