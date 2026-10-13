package com.lulu.agent.accessibility.model

import android.graphics.Rect
import java.security.MessageDigest

/**
 * 消息列表中扫描提取到的未读 HR 会话描述实体
 */
data class UnreadChatSession(
    val hrName: String,
    val companyName: String,
    val jobTitle: String,
    val salaryText: String,
    val lastMessage: String,
    val unreadCount: Int,
    val clickBounds: Rect,
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * 计算该会话绑定的 JobId 指纹
     */
    fun resolveJobId(): String {
        val raw = "${jobTitle.trim()}_${companyName.trim()}_${salaryText.trim()}"
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(raw.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    override fun toString(): String {
        return "UnreadChatSession(HR='$hrName', 岗位='$companyName - $jobTitle', 薪资='$salaryText', 未读数=$unreadCount, 最后留言='$lastMessage')"
    }
}