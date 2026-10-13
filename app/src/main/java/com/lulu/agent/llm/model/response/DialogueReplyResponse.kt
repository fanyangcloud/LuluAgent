package com.lulu.agent.llm.model.response

import com.google.gson.annotations.SerializedName

/**
 * 大模型多轮深入对话的决策与答复结构化模型
 */
data class DialogueReplyResponse(
    @SerializedName("decision")
    val decision: String = DECISION_CONTINUE,

    @SerializedName("reply_text")
    val replyText: String = "",

    @SerializedName("reason")
    val reason: String = "",

    @SerializedName("intent_tag")
    val intentTag: String = ""
) {
    /**
     * 是否触发了求职底线熔断（外包、严重压价、套方案、不匹配等）
     */
    fun isCircuitBroken(): Boolean {
        return decision.equals(DECISION_REJECT, ignoreCase = true)
    }

    /**
     * 清理并获取纯净的答复文本（去除两端引号）
     */
    fun getCleanReply(): String {
        return replyText
            .trim()
            .removeSurrounding("\"")
            .removeSurrounding("“", "”")
    }

    fun isValid(): Boolean = getCleanReply().isNotBlank()

    companion object {
        /** 正常推进：回答技术、业务或常规问询 */
        const val DECISION_CONTINUE = "CONTINUE_CHAT"
        /** 索要简历：对方要简历，同意并告知投递 */
        const val DECISION_SEND_RESUME = "SEND_RESUME"
        /** 邀约面试：对方发起面试或电话聊聊，积极回应约定时间 */
        const val DECISION_ASK_INTERVIEW = "ASK_INTERVIEW"
        /** 熔断回绝：发现外包、大小周、薪资倒挂、严重不符，礼貌委婉回绝并关闭对话 */
        const val DECISION_REJECT = "REJECT_AND_CLOSE"
    }
}