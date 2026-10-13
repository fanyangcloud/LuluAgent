package com.lulu.agent.accessibility.model

/**
 * Boss 直聘页面视觉场景定义
 */
enum class PageScene(
    val description: String,
    val isRisk: Boolean = false
) {
    /** 推荐/搜索职位卡片流列表页 (主 Tab 1: 职位) */
    RECOMMEND_LIST("职位列表流"),

    /** 消息与联系人会话列表页 (主 Tab 3: 消息) */
    MESSAGE_LIST("消息会话列表"),

    /** 职位详情页（包含完整 JD 文本与立即沟通按钮） */
    JOB_DETAIL("职位详情页"),

    /** 沟通聊天窗口界面（可输入打招呼文本与发送按钮） */
    CHAT_WINDOW("沟通聊天窗口"),

    /** 风控预警场景（滑块验证码、设备校验、安全验证、操作过于频繁拦截） */
    CAPTCHA_RISK("风控验证拦截", true),

    /** 未知或过渡中的界面（如加载中骨架屏、权限弹窗等） */
    UNKNOWN("未知/过渡场景");

    fun isRecommendList(): Boolean = this == RECOMMEND_LIST
    fun isMessageList(): Boolean = this == MESSAGE_LIST
    fun isDetail(): Boolean = this == JOB_DETAIL
    fun isChat(): Boolean = this == CHAT_WINDOW
}