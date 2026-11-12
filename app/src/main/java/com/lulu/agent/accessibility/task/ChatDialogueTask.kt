package com.lulu.agent.accessibility.task

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.lulu.agent.accessibility.model.UnreadChatSession
import com.lulu.agent.accessibility.util.AccessibilityNodeUtil
import com.lulu.agent.accessibility.util.GestureEngine
import kotlin.math.abs

/**
 * 深入单聊会话交互任务
 * 专在 PageScene.CHAT_WINDOW 运行：
 * 1. 模拟填入大模型/熔断回复
 * 2. 派发发送手势并做送达双重校验
 * 3. (按需) 点击顶部【发简历】，通过 GestureEngine 点击弹窗中的首个 mResumeName 或 mResumeInfo 直接发送
 * 4. 【核心防御】：强制执行返回手势，严格确保退回到 PageScene.MESSAGE_LIST
 */
class ChatDialogueTask(
    private val session: UnreadChatSession? = null,
    private val replyText: String,
    private val sendResume: Boolean = false,
    private val onMessageSent: (() -> Unit)? = null,
    private val onResumeSent: (() -> Unit)? = null,
    private val onBackCompleted: (() -> Unit)? = null,
    private val onError: ((String) -> Unit)? = null
) : BaseAutomationTask() {

    private val tag = "ChatDialogueTask"
    private var sendClickAttempts = 0

    companion object {
        private const val STEP_WAIT_CHAT_INPUT = 1
        private const val STEP_INPUT_REPLY = 2
        private const val STEP_CLICK_SEND = 3
        private const val STEP_VERIFY_DELIVERY = 4
        private const val STEP_CLICK_SEND_RESUME = 5
        private const val STEP_SELECT_FIRST_RESUME = 6
        private const val STEP_NAVIGATE_BACK = 7

        // 基准缩放坐标
        private const val BASE_SCREEN_WIDTH = 1220f
        private const val BASE_SCREEN_HEIGHT = 2712f
        private const val BASE_OFFSET_X = 85f
        private const val BASE_OFFSET_Y = 42f

        private const val BOSS_PKG = "com.hpbr.bosszhipin"
        private const val ID_EDIT_TEXT = "com.hpbr.bosszhipin:id/editText_with_scrollbar"
        private const val ID_EMOTION_VIEW = "com.hpbr.bosszhipin:id/mEmotionView"
        private const val ID_BACK_BUTTON = "com.hpbr.bosszhipin:id/iv_back"

        // 发简历及附件弹窗节点 ID
        private const val ID_RESUME_TOP_TEXT = "com.hpbr.bosszhipin:id/mTextView"
        private const val ID_RESUME_NAME = "com.hpbr.bosszhipin:id/mResumeName"
        private const val ID_RESUME_INFO = "com.hpbr.bosszhipin:id/mResumeInfo"
    }

    override fun onStep(step: Int) {
        when (step) {
            STEP_WAIT_CHAT_INPUT -> handleWaitChatInput()
            STEP_INPUT_REPLY -> handleInputReply()
            STEP_CLICK_SEND -> handleClickSend()
            STEP_VERIFY_DELIVERY -> handleVerifyDelivery()
            STEP_CLICK_SEND_RESUME -> handleClickSendResume()
            STEP_SELECT_FIRST_RESUME -> handleSelectFirstResume()
            STEP_NAVIGATE_BACK -> handleNavigateBack()
        }
    }

    private fun handleWaitChatInput() {
        val root = service.rootInActiveWindow
        if (root == null) {
            retryCurrentStep(400L, "等待单聊窗口渲染")
            return
        }

        val editNodes = root.findAccessibilityNodeInfosByViewId(ID_EDIT_TEXT)
        val hasEdit = !editNodes.isNullOrEmpty()
        editNodes?.forEach { it.recycle() }
        root.recycle()

        if (hasEdit) {
            sendClickAttempts = 0
            sendStep(STEP_INPUT_REPLY, 150L)
        } else {
            retryCurrentStep(400L, "等待输入框就位")
        }
    }

    private fun handleInputReply() {
        val root = service.rootInActiveWindow
        if (root == null) {
            retryCurrentStep(400L, "获取输入框根节点失败")
            return
        }

        val editNodes = root.findAccessibilityNodeInfosByViewId(ID_EDIT_TEXT)
        val editNode = editNodes?.firstOrNull()

        if (editNode == null) {
            editNodes?.forEach { it.recycle() }
            root.recycle()
            retryCurrentStep(400L, "未找到聊天输入框")
            return
        }

        sendTaskMessage("正在回复 HR【${session?.hrName ?: "招聘者"}】...")
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, replyText)
        }
        val success = editNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)

        editNodes.forEach { it.recycle() }
        root.recycle()

        if (success) {
            Log.i(tag, "已填入答复文本: $replyText")
            nextStep(800L) // 预留多行文字重排渲染时长
        } else {
            retryCurrentStep(300L, "输入框填词失败")
        }
    }

    private fun handleClickSend() {
        sendClickAttempts++
        val root = service.rootInActiveWindow
        if (root == null) {
            retryCurrentStep(400L, "根节点丢失")
            return
        }

        val editNodes = root.findAccessibilityNodeInfosByViewId(ID_EDIT_TEXT)
        val editNode = editNodes?.firstOrNull()
        val editBounds = Rect()
        editNode?.getBoundsInScreen(editBounds)

        val emotionNodes = root.findAccessibilityNodeInfosByViewId(ID_EMOTION_VIEW)
        val emotionNode = emotionNodes?.firstOrNull()
        val emotionBounds = Rect()
        val hasEmotionNode = emotionNode != null
        if (hasEmotionNode) {
            emotionNode?.getBoundsInScreen(emotionBounds)
        }

        var clickedNode = false
        if (hasEmotionNode) {
            val safeBounds = AccessibilityNodeUtil.getSafeScreenBounds(service)
            val sendNode = AccessibilityNodeUtil.dfsFindNode(root, safeBounds) { node ->
                val r = Rect()
                node.getBoundsInScreen(r)
                r.left >= emotionBounds.right &&
                        abs(r.centerY() - emotionBounds.centerY()) < 50 &&
                        (node.isClickable || node.className?.contains("ImageView") == true)
            }
            if (sendNode != null) {
                clickedNode = sendNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                sendNode.recycle()
            }
        }

        emotionNodes?.forEach { it.recycle() }
        editNodes?.forEach { it.recycle() }
        root.recycle()

        if (clickedNode) {
            nextStep(650L)
            return
        }

        // 物理坐标点击计算
        val screenBounds = AccessibilityNodeUtil.getScreenBounds(service)
        val screenWidth = if (screenBounds.width() > 0) screenBounds.width() else 1220
        val screenHeight = if (screenBounds.height() > 0) screenBounds.height() else 2712

        val dynamicOffsetX = ((screenWidth.toFloat() / BASE_SCREEN_WIDTH) * BASE_OFFSET_X).toInt()
        val dynamicOffsetY = ((screenHeight.toFloat() / BASE_SCREEN_HEIGHT) * BASE_OFFSET_Y).toInt()

        val targetX = if (hasEmotionNode && emotionBounds.right > 0) {
            emotionBounds.right + (screenWidth - emotionBounds.right) / 2
        } else {
            screenWidth - dynamicOffsetX
        }

        val targetY = if (hasEmotionNode && emotionBounds.centerY() > 0) {
            emotionBounds.centerY()
        } else {
            editBounds.bottom - dynamicOffsetY
        }

        Log.d(tag, "坐标点击发送: ($targetX, $targetY) [第 $sendClickAttempts 次]")
        val sent = GestureEngine.clickAt(
            service = service,
            x = targetX,
            y = targetY,
            onSuccess = { nextStep(650L) },
            onFail = { retryCurrentStep(400L, "点击发送失败") }
        )

        if (!sent) {
            retryCurrentStep(400L, "无法派发发送手势")
        }
    }

    private fun handleVerifyDelivery() {
        val root = service.rootInActiveWindow
        val editNodes = root?.findAccessibilityNodeInfosByViewId(ID_EDIT_TEXT)
        val editNode = editNodes?.firstOrNull()

        editNode?.refresh()
        val currentInput = editNode?.text?.toString()?.trim() ?: ""

        val fingerprint = if (replyText.length > 6) replyText.substring(0, 6) else replyText
        val isCleared = currentInput.isEmpty() || !currentInput.contains(fingerprint)

        val bubbleNodes = root?.findAccessibilityNodeInfosByText(fingerprint)
        val isSentBubble = bubbleNodes?.any { it != editNode } == true

        bubbleNodes?.forEach { it.recycle() }
        editNodes?.forEach { it.recycle() }
        root?.recycle()

        if (isCleared || isSentBubble) {
            onMessageSent?.invoke()
            if (sendResume) {
                sendTaskMessage("✉️ 对话送达确认！准备点击顶部【发简历】...")
                sendStep(STEP_CLICK_SEND_RESUME, 500L)
            } else {
                sendTaskMessage("✉️ 对话送达确认！准备后退...")
                sendStep(STEP_NAVIGATE_BACK, 450L)
            }
        } else {
            if (sendClickAttempts < 3) {
                sendStep(STEP_CLICK_SEND, 350L)
            } else {
                failedTask("答复消息发送未生效")
            }
        }
    }

    /**
     * 步骤 5：通过文本定位顶部“发简历”按钮并点击
     */
    private fun handleClickSendResume() {
        val resumeBtn = findSendResumeButton()
        if (resumeBtn == null) {
            if (retryCount >= 2) {
                Log.w(tag, "未找到顶部【发简历】按钮，跳过发简历直接返回")
                sendTaskMessage("⚠️ 未找到【发简历】按钮，准备返回")
                sendStep(STEP_NAVIGATE_BACK, 300L)
            } else {
                retryCurrentStep(400L, "等待定位顶部【发简历】按钮")
            }
            return
        }

        sendTaskMessage("📎 点击顶部【发简历】...")
        val clicked = GestureEngine.performClick(
            service = service,
            node = resumeBtn,
            onSuccess = { nextStep(850L) }, // 留足底部简历列表弹窗展开动画时长
            onFail = { retryCurrentStep(400L, "点击【发简历】手势失败") }
        )
        resumeBtn.recycle()

        if (!clicked) {
            retryCurrentStep(400L, "无法派发【发简历】点击手势")
        }
    }

    /**
     * 步骤 6：直接查找首个 mResumeName 或 mResumeInfo，通过 GestureEngine 点击发送
     */
    private fun handleSelectFirstResume() {
        val targetNode = findFirstResumeNode()
        if (targetNode == null) {
            if (retryCount >= 3) {
                Log.w(tag, "未检测到简历附件列表弹窗（可能已发送过），直接返回")
                sendTaskMessage("⚠️ 未弹出简历列表，准备退回消息列表")
                sendStep(STEP_NAVIGATE_BACK, 300L)
            } else {
                retryCurrentStep(450L, "等待简历附件列表弹窗")
            }
            return
        }

        val label = targetNode.text?.toString()?.trim().orEmpty().ifBlank { "首份简历附件" }
        Log.i(tag, "点击发送首个简历附件: $label")
        sendTaskMessage("📄 点击发送简历: $label")

        val clicked = GestureEngine.performClick(
            service = service,
            node = targetNode,
            onSuccess = {
                onResumeSent?.invoke()
                nextStep(650L) // 点击后直接发送并自动收起弹窗，稍作等待后退回消息列表
            },
            onFail = {
                retryCurrentStep(400L, "点击简历附件手势失败")
            }
        )
        targetNode.recycle()

        if (!clicked) {
            retryCurrentStep(400L, "无法派发简历附件点击手势")
        }
    }

    /**
     * 步骤 7：绝对安全后退，必须回归消息列表
     */
    private fun handleNavigateBack() {
        sendTaskMessage("↩️ 回退至消息会话列表...")
        val root = service.rootInActiveWindow
        val backNodes = root?.findAccessibilityNodeInfosByViewId(ID_BACK_BUTTON)
        val backBtn = backNodes?.firstOrNull()

        if (backBtn != null) {
            GestureEngine.performClick(
                service = service,
                node = backBtn,
                onSuccess = {
                    backNodes.forEach { it.recycle() }
                    root.recycle()
                    onBackCompleted?.invoke()
                    finishTask()
                },
                onFail = {
                    backNodes.forEach { it.recycle() }
                    root.recycle()
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    onBackCompleted?.invoke()
                    finishTask()
                }
            )
        } else {
            backNodes?.forEach { it.recycle() }
            root?.recycle()
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            onBackCompleted?.invoke()
            finishTask()
        }
    }

    // ==================== 辅助节点检索 ====================

    /**
     * 按 text="发简历" 查找顶部按钮（优先匹配 mTextView，兼容纯文本精确查找）
     */
    private fun findSendResumeButton(): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        val safeBounds = AccessibilityNodeUtil.getSafeScreenBounds(service)
        val candidates = root.findAccessibilityNodeInfosByText("发简历")

        var exactIdMatch: AccessibilityNodeInfo? = null
        var textOnlyFallback: AccessibilityNodeInfo? = null

        candidates?.forEach { node ->
            val isExact = node.text?.toString()?.trim() == "发简历" &&
                    AccessibilityNodeUtil.isCenterInBounds(node, safeBounds)
            when {
                isExact && node.viewIdResourceName == ID_RESUME_TOP_TEXT && exactIdMatch == null -> {
                    exactIdMatch = node
                }
                isExact && textOnlyFallback == null -> {
                    textOnlyFallback = node
                }
                else -> node.recycle()
            }
        }

        val chosen = if (exactIdMatch != null) {
            textOnlyFallback?.recycle()
            exactIdMatch
        } else {
            textOnlyFallback
        }

        if (root != chosen) {
            root.recycle()
        }
        return chosen
    }

    /**
     * 查找弹窗中第一个可见的 mResumeName 或 mResumeInfo（兼容独立 Dialog / BottomSheet 窗口）
     */
    private fun findFirstResumeNode(): AccessibilityNodeInfo? {
        service.rootInActiveWindow?.let { activeRoot ->
            val found = extractFirstResumeFromRoot(activeRoot)
            if (activeRoot != found) activeRoot.recycle()
            if (found != null) return found
        }

        for (win in service.windows) {
            val root = win.root ?: continue
            if (root.packageName == BOSS_PKG) {
                val found = extractFirstResumeFromRoot(root)
                if (root != found) root.recycle()
                if (found != null) return found
            } else {
                root.recycle()
            }
        }
        return null
    }

    private fun extractFirstResumeFromRoot(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (viewId in listOf(ID_RESUME_NAME, ID_RESUME_INFO)) {
            val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
            if (!nodes.isNullOrEmpty()) {
                var target: AccessibilityNodeInfo? = null
                val rect = Rect()
                for (node in nodes) {
                    node.getBoundsInScreen(rect)
                    if (target == null && rect.width() > 0 && rect.height() > 0) {
                        target = node
                    } else {
                        node.recycle()
                    }
                }
                if (target != null) return target
            }
        }
        return null
    }

    override fun failedTask(reason: String) {
        super.failedTask(reason)
        onError?.invoke(reason)
    }

    override fun onForceStopped() {
        onError?.invoke("对话回复任务被强制终止")
    }
}