package com.lulu.agent.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 聊天对话气泡实体
 * 记录与招聘者（HR）来回交互的每一条消息，作为后续大模型多轮推理的上下文记忆池
 */
@Entity(
    tableName = "chat_messages",
    indices = [
        Index(value = ["job_id"]),
        Index(value = ["timestamp"]),
        Index(value = ["job_id", "timestamp"])
    ]
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "job_id")
    val jobId: String,

    @ColumnInfo(name = "sender_role")
    val senderRole: String, // 消息发送方：ROLE_HR 或 ROLE_ME

    @ColumnInfo(name = "message_text")
    val messageText: String,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "is_processed")
    val isProcessed: Boolean = false // 是否已被 LLM 读取并决策答复过
) {
    companion object {
        const val ROLE_HR = "HR"
        const val ROLE_ME = "ME"
    }
}