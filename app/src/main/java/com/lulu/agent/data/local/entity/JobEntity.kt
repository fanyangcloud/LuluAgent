package com.lulu.agent.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "jobs",
    indices = [
        Index(value = ["job_id"], unique = true),
        Index(value = ["company_name"]),
        Index(value = ["status"]),
        Index(value = ["is_chat_closed"]),
        Index(value = ["updated_at"])
    ]
)
data class JobEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "job_id")
    val jobId: String,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "company_name")
    val companyName: String,

    @ColumnInfo(name = "salary_text")
    val salaryText: String,

    @ColumnInfo(name = "salary_min")
    val salaryMin: Int = 0,

    @ColumnInfo(name = "salary_max")
    val salaryMax: Int = 0,

    @ColumnInfo(name = "city")
    val city: String = "",

    @ColumnInfo(name = "hr_name")
    val hrName: String = "",

    @ColumnInfo(name = "hr_title")
    val hrTitle: String = "",

    @ColumnInfo(name = "hr_active_status")
    val hrActiveStatus: String = "",

    @ColumnInfo(name = "job_description")
    val jobDescription: String = "",

    @ColumnInfo(name = "match_score")
    val matchScore: Int = -1,

    @ColumnInfo(name = "eval_reason")
    val evalReason: String = "",

    @ColumnInfo(name = "suggested_greeting")
    val suggestedGreeting: String = "",

    @ColumnInfo(name = "status")
    val status: String = STATUS_DISCOVERED,

    @ColumnInfo(name = "is_chat_closed")
    val isChatClosed: Boolean = false, // 是否触发熔断或人工标记关闭（关闭后 Agent 不再介入此对话）

    @ColumnInfo(name = "last_chat_timestamp")
    val lastChatTimestamp: Long = 0L, // 最近一次对话交互时间

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATUS_DISCOVERED = "DISCOVERED"         // 列表刚扫描发现
        const val STATUS_FILTERED_OUT = "FILTERED_OUT"     // 触发本地规则被硬过滤(不耗Token)
        const val STATUS_EVALUATED = "EVALUATED"           // 模型评估完毕
        const val STATUS_COMMUNICATED = "COMMUNICATED"     // 已经成功破冰打招呼
        const val STATUS_CHATTING = "CHATTING"             // 【新增】正在深入多轮沟通中
        const val STATUS_DELIVERED = "DELIVERED"           // 已投递/发送附件简历
        const val STATUS_REJECTED = "REJECTED"             // 评估不合适或沟通后触发熔断婉拒
    }
}