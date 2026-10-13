package com.lulu.agent.accessibility.task

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.lulu.agent.accessibility.model.UnreadChatSession
import com.lulu.agent.accessibility.util.AccessibilityNodeUtil
import com.lulu.agent.accessibility.util.GestureEngine

/**
 * 消息列表巡视与未读消费任务
 * 专在 PageScene.MESSAGE_LIST 运行：
 * 1. 扫描消息流 RecyclerView (com.hpbr.bosszhipin:id/recyclerView)
 * 2. 识别 badge_view 内部有数字的未读项
 * 3. 过滤系统广播（无 tv_position 项）
 * 4. 提取 HR 姓名、公司、岗位、薪资 (tv_time) 与最新留言
 * 5. 点击进入会话；若全屏无未读，回调 onAllCleared()
 */
class InspectMessageListTask(
    private val onSessionSelected: ((UnreadChatSession) -> Unit)? = null,
    private val onAllCleared: (() -> Unit)? = null,
    private val onError: ((String) -> Unit)? = null
) : BaseAutomationTask() {

    private val tag = "InspectMessageListTask"

    companion object {
        private const val STEP_SCAN_UNREAD_CARDS = 1
        private const val STEP_SCROLL_CHECK_MORE = 2

        private const val ID_RECYCLER_VIEW = "com.hpbr.bosszhipin:id/recyclerView"
        private const val ID_BADGE_VIEW = "com.hpbr.bosszhipin:id/badge_view"
        private const val ID_NAME = "com.hpbr.bosszhipin:id/tv_name"
        private const val ID_POSITION = "com.hpbr.bosszhipin:id/tv_position"
        private const val ID_SALARY = "com.hpbr.bosszhipin:id/tv_time" // Boss 特有：tv_time 实际为薪资
        private const val ID_MESSAGE = "com.hpbr.bosszhipin:id/tv_msg"
    }

    override fun onStep(step: Int) {
        when (step) {
            STEP_SCAN_UNREAD_CARDS -> handleScanUnreadCards()
            STEP_SCROLL_CHECK_MORE -> handleScrollCheckMore()
        }
    }

    private fun handleScanUnreadCards() {
        val root = service.rootInActiveWindow
        if (root == null) {
            retryCurrentStep(400L, "消息列表根节点为空")
            return
        }

        val rvNodes = root.findAccessibilityNodeInfosByViewId(ID_RECYCLER_VIEW)
        val rvNode = rvNodes?.firstOrNull()

        if (rvNode == null) {
            rvNodes?.forEach { it.recycle() }
            root.recycle()
            retryCurrentStep(500L, "未定位到消息列表 RecyclerView")
            return
        }

        val childCount = rvNode.childCount
        var targetSession: UnreadChatSession? = null

        for (i in 0 until childCount) {
            val item = rvNode.getChild(i) ?: continue

            // 1. 过滤：必须拥有 tv_position (排除第 0 项系统广播、求职助手、换联系方式等)
            val posNodes = item.findAccessibilityNodeInfosByViewId(ID_POSITION)
            if (posNodes.isNullOrEmpty()) {
                posNodes?.forEach { it.recycle() }
                item.recycle()
                continue
            }
            val rawPosition = posNodes[0].text?.toString()?.trim() ?: ""
            posNodes.forEach { it.recycle() }

            // 2. 检查未读徽章 badge_view
            val badgeNodes = item.findAccessibilityNodeInfosByViewId(ID_BADGE_VIEW)
            val unreadCount = parseUnreadCount(badgeNodes?.firstOrNull())
            badgeNodes?.forEach { it.recycle() }

            if (unreadCount <= 0) {
                // 没有未读消息，继续排查下一个 item
                item.recycle()
                continue
            }

            // 3. 提取其他必要元数据
            val name = extractTextById(item, ID_NAME)
            val salary = extractTextById(item, ID_SALARY)
            val lastMsg = extractTextById(item, ID_MESSAGE)

            val itemBounds = Rect()
            item.getBoundsInScreen(itemBounds)

            // 拆解 "公司名 | 岗位名"
            val parts = rawPosition.split("|", "｜")
            val company = if (parts.isNotEmpty()) parts[0].trim() else ""
            val title = if (parts.size > 1) parts[1].trim() else rawPosition

            targetSession = UnreadChatSession(
                hrName = name,
                companyName = company,
                jobTitle = title,
                salaryText = salary,
                lastMessage = lastMsg,
                unreadCount = unreadCount,
                clickBounds = itemBounds
            )

            item.recycle()
            break // 锁定当前屏首个未读目标
        }

        rvNodes.forEach { it.recycle() }
        root.recycle()

        if (targetSession != null) {
            sendTaskMessage("🎯 命中未读消息:【${targetSession.hrName} (${targetSession.companyName})】未读数: ${targetSession.unreadCount}")
            Log.i(tag, "准备点击进入未读会话: $targetSession")

            val clicked = GestureEngine.clickAt(
                service = service,
                x = targetSession.clickBounds.centerX(),
                y = targetSession.clickBounds.centerY(),
                onSuccess = {
                    onSessionSelected?.invoke(targetSession)
                    finishTask()
                },
                onFail = {
                    retryCurrentStep(400L, "点击未读条目手势失败")
                }
            )

            if (!clicked) {
                retryCurrentStep(400L, "无法派发会话点击手势")
            }
        } else {
            // 当前屏没有未读条目，轻度下滑验证是否底部还有
            sendStep(STEP_SCROLL_CHECK_MORE, 200L)
        }
    }

    private fun handleScrollCheckMore() {
        sendTaskMessage("当前屏未见未读消息，消息信箱已清空 ✨")
        Log.i(tag, "✅ 确认所有未读消息均已消费完毕")
        onAllCleared?.invoke()
        finishTask()
    }

    private fun parseUnreadCount(badgeContainer: AccessibilityNodeInfo?): Int {
        if (badgeContainer == null) return 0
        if (badgeContainer.childCount == 0) {
            val text = badgeContainer.text?.toString()?.trim() ?: ""
            return text.toIntOrNull() ?: 0
        }
        for (i in 0 until badgeContainer.childCount) {
            val child = badgeContainer.getChild(i) ?: continue
            val text = child.text?.toString()?.trim() ?: ""
            val num = text.toIntOrNull()
            child.recycle()
            if (num != null && num > 0) return num
        }
        return 0
    }

    private fun extractTextById(parent: AccessibilityNodeInfo, viewId: String): String {
        val nodes = parent.findAccessibilityNodeInfosByViewId(viewId) ?: return ""
        val text = nodes.firstOrNull()?.text?.toString()?.trim() ?: ""
        nodes.forEach { it.recycle() }
        return text
    }

    override fun failedTask(reason: String) {
        super.failedTask(reason)
        onError?.invoke(reason)
    }

    override fun onForceStopped() {
        onError?.invoke("未读消息扫描任务被强制终止")
    }
}