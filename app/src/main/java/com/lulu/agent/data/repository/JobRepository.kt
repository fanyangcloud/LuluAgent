package com.lulu.agent.data.repository

import com.lulu.agent.data.local.AppDatabase
import com.lulu.agent.data.local.entity.ChatMessageEntity
import com.lulu.agent.data.local.entity.CompanyEntity
import com.lulu.agent.data.local.entity.JobEntity
import com.lulu.agent.data.local.entity.LLMAuditLogEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.Calendar

class JobRepository(private val db: AppDatabase) {

    private val jobDao = db.jobDao()
    private val companyDao = db.companyDao()
    private val llmAuditDao = db.llmAuditDao()
    private val chatMessageDao = db.chatMessageDao() // 🌟 注入聊天 DAO

    // ==================== 1. 岗位发现与状态流转 ====================

    /**
     * 判定该岗位是否可以投递（既未沟通过，公司也不在黑名单）
     */
    suspend fun isJobEligible(jobId: String, companyName: String): Boolean = withContext(Dispatchers.IO) {
        if (jobDao.isJobCommunicated(jobId)) {
            return@withContext false
        }
        if (companyDao.isBlacklisted(companyName)) {
            return@withContext false
        }
        true
    }

    suspend fun recordDiscoveredJob(job: JobEntity): Boolean = withContext(Dispatchers.IO) {
        val rowId = jobDao.insertOrIgnore(job)
        rowId > 0
    }

    suspend fun markJobFilteredOut(jobId: String, reason: String) = withContext(Dispatchers.IO) {
        jobDao.updateStatus(jobId, JobEntity.STATUS_FILTERED_OUT)
    }

    suspend fun saveEvaluationResult(
        jobId: String,
        score: Int,
        reason: String,
        suggestedGreeting: String,
        status: String
    ) = withContext(Dispatchers.IO) {
        jobDao.updateEvaluationResult(
            jobId = jobId,
            score = score,
            reason = reason,
            greeting = suggestedGreeting,
            status = status
        )
    }

    suspend fun markJobCommunicated(jobId: String) = withContext(Dispatchers.IO) {
        jobDao.updateStatus(jobId, JobEntity.STATUS_COMMUNICATED)
    }

    suspend fun markJobDelivered(jobId: String) = withContext(Dispatchers.IO) {
        jobDao.updateStatus(jobId, JobEntity.STATUS_DELIVERED)
    }

    suspend fun getTodayCommunicatedCount(): Int = withContext(Dispatchers.IO) {
        val (start, end) = getTodayTimeRange()
        jobDao.getCommunicatedCountBetween(start, end)
    }

    suspend fun canGreetMoreToday(maxDailyLimit: Int): Boolean = withContext(Dispatchers.IO) {
        getTodayCommunicatedCount() < maxDailyLimit
    }

    suspend fun isCompanyBlacklisted(companyName: String): Boolean = withContext(Dispatchers.IO) {
        companyDao.isBlacklisted(companyName)
    }

    suspend fun addCompanyToBlacklist(companyName: String, reason: String) = withContext(Dispatchers.IO) {
        companyDao.insertOrUpdate(
            CompanyEntity(
                companyName = companyName,
                isBlacklisted = true,
                blacklistReason = reason
            )
        )
    }

    // ==================== 2. 多轮对话与上下文记忆（新增） ====================

    /**
     * 登记单条对话气泡记录（内置去重，防止每次进入房间重复写入旧消息）
     */
    suspend fun recordChatMessage(
        jobId: String,
        senderRole: String,
        messageText: String,
        timestamp: Long = System.currentTimeMillis()
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanText = messageText.trim()
        if (cleanText.isBlank()) return@withContext false

        val exists = chatMessageDao.isMessageExists(jobId, senderRole, cleanText)
        if (!exists) {
            chatMessageDao.insertMessage(
                ChatMessageEntity(
                    jobId = jobId,
                    senderRole = senderRole,
                    messageText = cleanText,
                    timestamp = timestamp,
                    isProcessed = false
                )
            )
            // 同步刷新岗位的最后活跃时间
            jobDao.updateChatActivity(jobId = jobId, timestamp = timestamp)
            true
        } else {
            false
        }
    }

    /**
     * 获取按时间正序排列的最近 N 轮对话历史（直接可注入 Prompt 作为上下文）
     */
    suspend fun getRecentChatHistory(jobId: String, limit: Int = 10): List<ChatMessageEntity> = withContext(Dispatchers.IO) {
        chatMessageDao.getRecentMessages(jobId, limit).reversed()
    }

    /**
     * 校验当前会话是否已被熔断关闭
     */
    suspend fun isChatClosed(jobId: String): Boolean = withContext(Dispatchers.IO) {
        jobDao.isChatClosed(jobId) == true
    }

    /**
     * 触发熔断：拉黑并关闭当前会话，避免后续 Agent 再次处理
     */
    suspend fun closeChatSession(jobId: String, reason: String) = withContext(Dispatchers.IO) {
        jobDao.markChatClosed(jobId, reason)
    }

    /**
     * 更新为正在深入沟通状态
     */
    suspend fun updateChatActivity(jobId: String) = withContext(Dispatchers.IO) {
        jobDao.updateChatActivity(jobId)
    }

    /**
     * 根据聊天界面顶部 Job Card 获取或动态创建 JobEntity
     * 应对 HR 主动发起的沟通（数据库此前无记录的情况）
     */
    suspend fun findOrCreateJobFromChat(
        title: String,
        companyName: String,
        salaryText: String
    ): JobEntity = withContext(Dispatchers.IO) {
        val cleanTitle = title.trim()
        val cleanCompany = companyName.trim()
        val cleanSalary = salaryText.trim()
        val generatedJobId = md5("${cleanTitle}_${cleanCompany}_$cleanSalary")

        var job = jobDao.findByJobId(generatedJobId)
        if (job == null) {
            job = JobEntity(
                jobId = generatedJobId,
                title = cleanTitle,
                companyName = cleanCompany,
                salaryText = cleanSalary,
                status = JobEntity.STATUS_CHATTING,
                lastChatTimestamp = System.currentTimeMillis()
            )
            jobDao.insertOrIgnore(job)
        }
        job
    }

    // ==================== 3. 大模型审计流水 ====================

    suspend fun recordLLMAudit(
        scene: String,
        jobId: String,
        modelName: String,
        prompt: String,
        response: String,
        promptTokens: Int,
        completionTokens: Int,
        totalTokens: Int,
        durationMs: Long,
        isSuccess: Boolean,
        errorMessage: String = ""
    ) = withContext(Dispatchers.IO) {
        llmAuditDao.insertLog(
            LLMAuditLogEntity(
                scene = scene,
                jobId = jobId,
                modelName = modelName,
                promptSnapshot = prompt,
                responseRaw = response,
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                totalTokens = totalTokens,
                durationMs = durationMs,
                isSuccess = isSuccess,
                errorMessage = errorMessage
            )
        )
    }

    suspend fun getTodayTokenCost(): Int = withContext(Dispatchers.IO) {
        val (start, end) = getTodayTimeRange()
        llmAuditDao.getTodayTotalTokens(start, end)
    }

    private fun getTodayTimeRange(): Pair<Long, Long> {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val start = calendar.timeInMillis

        calendar.set(Calendar.HOUR_OF_DAY, 23)
        calendar.set(Calendar.MINUTE, 59)
        calendar.set(Calendar.SECOND, 59)
        calendar.set(Calendar.MILLISECOND, 999)
        val end = calendar.timeInMillis
        return Pair(start, end)
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}