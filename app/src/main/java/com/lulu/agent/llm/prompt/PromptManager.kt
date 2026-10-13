package com.lulu.agent.llm.prompt

import com.lulu.agent.accessibility.model.ScrapedRawJob
import com.lulu.agent.accessibility.model.UnreadChatSession
import com.lulu.agent.data.local.entity.ChatMessageEntity
import com.lulu.agent.data.repository.ConfigRepository
import com.lulu.agent.llm.model.request.DeepSeekChatRequest

/**
 * 提示词装配中枢
 */
class PromptManager(
    private val configRepository: ConfigRepository
) {

    /**
     * 组装岗位契合度深度评估请求体
     */
    fun buildEvaluationRequest(
        rawJob: ScrapedRawJob,
        model: String = DeepSeekChatRequest.MODEL_CHAT
    ): DeepSeekChatRequest {
        val resume = configRepository.getResumeMarkdown().ifBlank {
            "（求职者未配置简历，请根据一般中高级技术标准严苛评估）"
        }

        val userPrompt = JDEvaluatePrompt.buildUserPrompt(
            candidateResume = resume,
            jobTitle = rawJob.title,
            companyName = rawJob.companyName,
            salaryText = rawJob.salaryText,
            jobDescription = rawJob.jobDescription
        )

        return DeepSeekChatRequest.buildJsonRequest(
            systemPrompt = JDEvaluatePrompt.SYSTEM_PROMPT,
            userPrompt = userPrompt,
            model = model,
            temperature = 0.2 // 评估阶段使用低温度系数，保证推理严谨稳定
        )
    }

    /**
     * 组装个性化破冰打招呼语请求体
     */
    fun buildGreetingRequest(
        rawJob: ScrapedRawJob,
        highlights: List<String> = emptyList(),
        model: String = DeepSeekChatRequest.MODEL_CHAT
    ): DeepSeekChatRequest {
        val resume = configRepository.getResumeMarkdown()

        val userPrompt = IcebreakGreetingPrompt.buildUserPrompt(
            candidateResume = resume,
            jobTitle = rawJob.title,
            companyName = rawJob.companyName,
            jobDescription = rawJob.jobDescription,
            highlights = highlights
        )

        return DeepSeekChatRequest.buildJsonRequest(
            systemPrompt = IcebreakGreetingPrompt.SYSTEM_PROMPT,
            userPrompt = userPrompt,
            model = model,
            temperature = 0.5 // 生成话术使用适中温度，增强行文灵活性
        )
    }

    /**
     * 组装多轮深入沟通与熔断研判请求体
     */
    fun buildDialogueRequest(
        session: UnreadChatSession,
        chatHistory: List<ChatMessageEntity>,
        model: String = DeepSeekChatRequest.MODEL_CHAT
    ): DeepSeekChatRequest {
        val resume = configRepository.getResumeMarkdown().ifBlank {
            "（求职者未配置简历，请依据专业全栈工程师标准沉稳回答）"
        }

        val userPrompt = ChatDialoguePrompt.buildUserPrompt(
            candidateResume = resume,
            jobTitle = session.jobTitle,
            companyName = session.companyName,
            salaryText = session.salaryText,
            chatHistory = chatHistory,
            latestMessage = session.lastMessage
        )

        return DeepSeekChatRequest.buildJsonRequest(
            systemPrompt = ChatDialoguePrompt.SYSTEM_PROMPT,
            userPrompt = userPrompt,
            model = model,
            temperature = 0.4 // 答复阶段适中温度，兼顾情商与规则严谨度
        )
    }
}