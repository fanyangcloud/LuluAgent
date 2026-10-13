package com.lulu.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.lulu.agent.data.local.entity.JobEntity

@Dao
interface JobDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(job: JobEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(job: JobEntity): Long

    @Update
    suspend fun update(job: JobEntity)

    @Query("SELECT * FROM jobs WHERE job_id = :jobId LIMIT 1")
    suspend fun findByJobId(jobId: String): JobEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM jobs WHERE job_id = :jobId)")
    suspend fun isJobExists(jobId: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM jobs WHERE job_id = :jobId AND status IN ('COMMUNICATED', 'DELIVERED', 'CHATTING'))")
    suspend fun isJobCommunicated(jobId: String): Boolean

    @Query("SELECT is_chat_closed FROM jobs WHERE job_id = :jobId LIMIT 1")
    suspend fun isChatClosed(jobId: String): Boolean?

    @Query("SELECT COUNT(1) FROM jobs WHERE status IN ('COMMUNICATED', 'DELIVERED', 'CHATTING') AND updated_at >= :startTime AND updated_at <= :endTime")
    suspend fun getCommunicatedCountBetween(startTime: Long, endTime: Long): Int

    @Query("SELECT COUNT(1) FROM jobs WHERE status IN ('DELIVERED', 'RESUME_SENT') AND updated_at >= :startTime AND updated_at <= :endTime")
    suspend fun getDeliveredCountBetween(startTime: Long, endTime: Long): Int

    /**
     * 统计指定时间范围内触发熔断关闭的岗位数量
     */
    @Query("SELECT COUNT(1) FROM jobs WHERE is_chat_closed = 1 AND updated_at >= :startTime AND updated_at <= :endTime")
    suspend fun getCircuitBrokenCountBetween(startTime: Long, endTime: Long): Int

    @Query("UPDATE jobs SET status = :status, updated_at = :updatedAt WHERE job_id = :jobId")
    suspend fun updateStatus(jobId: String, status: String, updatedAt: Long = System.currentTimeMillis())

    @Query("""
        UPDATE jobs 
        SET is_chat_closed = 1, 
            status = 'REJECTED', 
            eval_reason = :closeReason, 
            updated_at = :updatedAt 
        WHERE job_id = :jobId
    """)
    suspend fun markChatClosed(
        jobId: String,
        closeReason: String,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("""
        UPDATE jobs 
        SET status = :status, 
            last_chat_timestamp = :timestamp, 
            updated_at = :updatedAt 
        WHERE job_id = :jobId
    """)
    suspend fun updateChatActivity(
        jobId: String,
        status: String = JobEntity.STATUS_CHATTING,
        timestamp: Long = System.currentTimeMillis(),
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("""
        UPDATE jobs 
        SET match_score = :score, 
            eval_reason = :reason, 
            suggested_greeting = :greeting, 
            status = :status, 
            updated_at = :updatedAt 
        WHERE job_id = :jobId
    """)
    suspend fun updateEvaluationResult(
        jobId: String,
        score: Int,
        reason: String,
        greeting: String,
        status: String,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("SELECT * FROM jobs ORDER BY updated_at DESC LIMIT :limit OFFSET :offset")
    suspend fun getRecentJobs(limit: Int, offset: Int = 0): List<JobEntity>
}