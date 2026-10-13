package com.lulu.agent.agent.communicator

import android.content.Context
import android.util.Log
import com.lulu.agent.accessibility.BossAccessibilityService
import com.lulu.agent.accessibility.model.ScrapedRawJob
import com.lulu.agent.accessibility.model.UnreadChatSession
import com.lulu.agent.accessibility.task.ChatDialogueTask
import com.lulu.agent.accessibility.task.SendGreetingTask
import com.lulu.agent.agent.base.BaseAgent
import com.lulu.agent.agent.evaluator.EvaluatorResult
import com.lulu.agent.data.local.entity.ChatMessageEntity
import com.lulu.agent.data.repository.ConfigRepository
import com.lulu.agent.data.repository.JobRepository
import com.lulu.agent.llm.DeepSeekClient
import com.lulu.agent.llm.model.response.DialogueReplyResponse
import kotlinx.coroutines.CompletableDeferred

/**
 * 谈判公关代表：负责初次破冰打招呼与多轮深入沟通应答（含智能熔断）
 */
class CommunicatorAgent(
    context: Context,
    private val deepSeekClient: DeepSeekClient,
    private val jobRepository: JobRepository,
    private val configRepository: ConfigRepository
) : BaseAgent(context, "CommunicatorAgent") {

    // 本地 0-Token 快速熔断敏感词库
    private val quickRejectKeywords = listOf(
        "外包", "劳务派遣", "驻场", "外派", "单休", "大小周",
        "无底薪", "试岗期无薪", "收费培训", "招转培"
    )

    override fun start() {
        super.start()
        sendAgentLog("🤝 谈判公关代表已就位，开启全双工沟通体系")
    }

    // ==================== 1. 初次破冰打招呼 (Proactive) ====================

    suspend fun executeGreeting(
        rawJob: ScrapedRawJob,
        evaluatorResult: EvaluatorResult,
        onSuccess: () -> Unit,
        onFailed: (String) -> Unit
    ) {
        val jobId = rawJob.resolveJobId()
        val company = rawJob.companyName
        val title = rawJob.title

        val maxDailyLimit = configRepository.getMaxDailyGreetings()
        val canGreet = jobRepository.canGreetMoreToday(maxDailyLimit)
        if (!canGreet) {
            val limitMsg = "今日主动沟通已达上限 ($maxDailyLimit 次)，启动防风控休眠"
            sendAgentLog("🛑 $limitMsg", isHighlight = true)
            onFailed(limitMsg)
            return
        }

        sendAgentLog("✍️ 正在让模型结合技术契合点构思破冰语...")
        val greetingResult = deepSeekClient.generateGreeting(rawJob, evaluatorResult.highlights)

        val greetingText = greetingResult.fold(
            onSuccess = { it.getCleanGreeting() },
            onFailure = { err ->
                Log.w(tag, "LLM话术生成降级: ${err.message}")
                "您好！关注到贵团队正在招聘${title}，我在该领域有深入实战与架构经验，与岗位要求非常契合，希望能与您交流探讨！"
            }
        )

        sendAgentLog("💬 敲定破冰语: $greetingText")

        val service = BossAccessibilityService.instance
        if (service == null) {
            val errMsg = "无障碍通道未连接"
            sendAgentLog("❌ $errMsg")
            onFailed(errMsg)
            return
        }

        val taskDeferred = CompletableDeferred<Boolean>()
        val task = SendGreetingTask(
            greetingText = greetingText,
            autoNavigateBack = true,
            onComplete = { taskDeferred.complete(true) },
            onError = { taskDeferred.complete(false) }
        )

        service.executeTask(task)
        val isTaskSuccess = taskDeferred.await()

        if (isTaskSuccess) {
            jobRepository.markJobDelivered(jobId)
            // 记入初次破冰对话记录
            jobRepository.recordChatMessage(jobId, ChatMessageEntity.ROLE_ME, greetingText)
            val todayTotal = jobRepository.getTodayCommunicatedCount()
            sendAgentLog("🎉 成功向【$company - $title】打招呼！今日累计: $todayTotal 次", isHighlight = true)
            onSuccess()
        } else {
            sendAgentLog("❌ 打招呼未送达")
            onFailed("SendGreetingTask 执行失败")
        }
    }

    // ==================== 2. 多轮深入消息答复与熔断 (Reactive) ====================

    suspend fun handleIncomingMessage(
        session: UnreadChatSession,
        onSuccess: () -> Unit,
        onFailed: (String) -> Unit
    ) {
        val jobId = session.resolveJobId()
        val hrName = session.hrName
        val compAndTitle = "${session.companyName} - ${session.jobTitle}"

        sendAgentLog("📨 正在处理【$hrName ($compAndTitle)】的消息...")

        // 1. 会话熔断排查：若此前已拉黑关闭，直接跳过不浪费 Token
        if (jobRepository.isChatClosed(jobId)) {
            sendAgentLog("⚠️ 该会话此前已触发熔断拉黑，跳过回复")
            onSuccess()
            return
        }

        // 2. 绑定或动态补全本地 JobEntity
        jobRepository.findOrCreateJobFromChat(session.jobTitle, session.companyName, session.salaryText)

        // 3. 登记 HR 最新发来的留言
        jobRepository.recordChatMessage(jobId, ChatMessageEntity.ROLE_HR, session.lastMessage)

        // 4. 第一层：本地 0-Token 快速敏感词熔断检测
        val matchedRejectWord = checkQuickRejectKeywords("${session.companyName} ${session.jobTitle} ${session.lastMessage}")
        var finalDecision = DialogueReplyResponse.DECISION_CONTINUE
        var finalReplyText = ""
        var rejectReason = ""

        if (matchedRejectWord != null) {
            finalDecision = DialogueReplyResponse.DECISION_REJECT
            rejectReason = "命中求职底线敏感词: 【$matchedRejectWord】"
            finalReplyText = "感谢您的沟通！了解到岗位有${matchedRejectWord}相关要求，与我目前的职业发展规划不太契合，非常遗憾不能合作，祝团队早日招到合适人才！"
            sendAgentLog("⛔ 本地规则触发熔断: $rejectReason (0 Token 拦截)", isHighlight = true)
        } else {
            // 5. 第二层：加载前序历史并调用 DeepSeek 深度研判
            val history = jobRepository.getRecentChatHistory(jobId, limit = 8)
            sendAgentLog("🧠 正在结合前序对话上下文推演答复...")

            val llmResult = deepSeekClient.generateDialogueReply(session, history)
            llmResult.fold(
                onSuccess = { resp ->
                    finalDecision = resp.decision
                    finalReplyText = resp.getCleanReply()
                    rejectReason = resp.reason
                    Log.i(tag, "模型答复生成完成: decision=${resp.decision}, tag=${resp.intentTag}")
                },
                onFailure = { err ->
                    Log.w(tag, "LLM回复生成降级: ${err.message}")
                    finalReplyText = "好的，收到您的信息，非常期待与您及团队进一步探讨岗位细节！"
                }
            )
        }

        // 6. 派发无障碍单聊任务 ChatDialogueTask
        val service = BossAccessibilityService.instance
        if (service == null) {
            val errMsg = "无障碍通道断开"
            sendAgentLog("❌ $errMsg")
            onFailed(errMsg)
            return
        }

        val sendDeferred = CompletableDeferred<Boolean>()
        val dialogueTask = ChatDialogueTask(
            session = session,
            replyText = finalReplyText,
            onMessageSent = { sendDeferred.complete(true) },
            onBackCompleted = { /* 成功后退 */ },
            onError = { sendDeferred.complete(false) }
        )

        service.executeTask(dialogueTask)
        val isSent = sendDeferred.await()

        if (isSent) {
            // 7. 落库自己发出的消息
            jobRepository.recordChatMessage(jobId, ChatMessageEntity.ROLE_ME, finalReplyText)

            if (finalDecision == DialogueReplyResponse.DECISION_REJECT) {
                // 执行数据库拉黑熔断
                jobRepository.closeChatSession(jobId, rejectReason)
                sendAgentLog("🚨 岗位已执行熔断拉黑！原因: $rejectReason", isHighlight = true)
            } else {
                jobRepository.updateChatActivity(jobId)
                sendAgentLog("💬 成功答复【$hrName】: $finalReplyText")
            }
            onSuccess()
        } else {
            sendAgentLog("❌ 答复消息未能成功送出")
            onFailed("ChatDialogueTask 失败")
        }
    }

    private fun checkQuickRejectKeywords(text: String): String? {
        return quickRejectKeywords.firstOrNull { text.contains(it) }
    }
}