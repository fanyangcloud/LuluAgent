package com.lulu.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lulu.agent.data.local.entity.ChatMessageEntity

@Dao
interface ChatMessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<ChatMessageEntity>): List<Long>

    /**
     * 按时间倒序获取某岗位最近 N 条对话（用于限制 Context 窗口长度）
     */
    @Query("SELECT * FROM chat_messages WHERE job_id = :jobId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMessages(jobId: String, limit: Int): List<ChatMessageEntity>

    /**
     * 检查当前屏幕采集到的气泡文本是否已落库，防止重复插入
     */
    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM chat_messages 
            WHERE job_id = :jobId AND sender_role = :senderRole AND message_text = :text
        )
    """)
    suspend fun isMessageExists(jobId: String, senderRole: String, text: String): Boolean

    /**
     * 获取指定岗位的全量对话总数
     */
    @Query("SELECT COUNT(1) FROM chat_messages WHERE job_id = :jobId")
    suspend fun getMessageCount(jobId: String): Int

    /**
     * 统计指定时间范围内自己（求职者/Agent）发出的回复消息条数
     */
    @Query("SELECT COUNT(1) FROM chat_messages WHERE sender_role = 'ME' AND timestamp >= :startTime AND timestamp <= :endTime")
    suspend fun getRepliedCountBetween(startTime: Long, endTime: Long): Int

    /**
     * 将某岗位的所有消息标记为已消费
     */
    @Query("UPDATE chat_messages SET is_processed = 1 WHERE job_id = :jobId")
    suspend fun markAllMessagesProcessed(jobId: String)

    /**
     * 清理某个岗位的聊天记录
     */
    @Query("DELETE FROM chat_messages WHERE job_id = :jobId")
    suspend fun deleteMessagesByJobId(jobId: String)
}