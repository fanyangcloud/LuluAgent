package com.lulu.agent.accessibility.task

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.lulu.agent.accessibility.util.AccessibilityNodeUtil
import com.lulu.agent.accessibility.util.GestureEngine

/**
 * 打招呼与个性化破冰发送闭环任务 (支持双重送达校验：消除 Hint 占位符误判与气泡上屏校验)
 */
class SendGreetingTask(
    private val greetingText: String? = null,
    private val autoNavigateBack: Boolean = true,
    private val onComplete: (() -> Unit)? = null,
    private val onError: ((String) -> Unit)? = null
) : BaseAutomationTask() {

    private val tag = "SendGreetingTask"
    private var sendClickAttempts = 0
    private var waitWindowAttempts = 0

    companion object {
        private const val STEP_INIT_AND_CLICK_CHAT = 1
        private const val STEP_WAIT_WINDOW_OR_POPUP = 2
        private const val STEP_INPUT_GREETING_TEXT = 3
        private const val STEP_CLICK_SEND_BUTTON = 4
        private const val STEP_VERIFY_AND_FINISH = 5
        private const val STEP_HANDLE_NAVIGATE_BACK = 6

        // 🌟【新增】：设计基准分辨率 (1220 x 2712) 及基准偏移常量
        private const val BASE_SCREEN_WIDTH = 1220f
        private const val BASE_SCREEN_HEIGHT = 2712f
        private const val BASE_OFFSET_X = 85f
        private const val BASE_OFFSET_Y = 42f

        private const val ID_BTN_CHAT = "com.hpbr.bosszhipin:id/btn_chat"
        private const val ID_EDIT_TEXT = "com.hpbr.bosszhipin:id/editText_with_scrollbar"
        private const val ID_EMOTION_VIEW = "com.hpbr.bosszhipin:id/mEmotionView"
        private const val ID_BACK_BUTTON = "com.hpbr.bosszhipin:id/iv_back"
    }

    override fun onStep(step: Int) {
        when (step) {
            STEP_INIT_AND_CLICK_CHAT -> handleInitAndClickChat()
            STEP_WAIT_WINDOW_OR_POPUP -> handleWaitWindowOrPopup()
            STEP_INPUT_GREETING_TEXT -> handleInputGreetingText()
            STEP_CLICK_SEND_BUTTON -> handleClickSendButton()
            STEP_VERIFY_AND_FINISH -> handleVerifyAndFinish()
            STEP_HANDLE_NAVIGATE_BACK -> handleNavigateBack()
        }
    }

    private fun handleInitAndClickChat() {
        val root = service.rootInActiveWindow
        if (root == null) {
            retryCurrentStep(400L, "根节点未就绪")
            return
        }

        val editNodes = root.findAccessibilityNodeInfosByViewId(ID_EDIT_TEXT)
        if (!editNodes.isNullOrEmpty()) {
            editNodes.forEach { it.recycle() }
            root.recycle()
            if (greetingText.isNullOrBlank()) {
                sendTaskMessage("已处于聊天界面，无需输入定制内容，直接退回...")
                sendStep(STEP_HANDLE_NAVIGATE_BACK, 300L)
            } else {
                sendStep(STEP_INPUT_GREETING_TEXT, 150L)
            }
            return
        }

        val chatBtnNodes = root.findAccessibilityNodeInfosByViewId(ID_BTN_CHAT)
        val chatBtn = chatBtnNodes?.firstOrNull()

        if (chatBtn != null) {
            val btnText = chatBtn.text?.toString() ?: ""
            // 如果已是“继续沟通”，且不需要输入定制内容，直接完成
            if (greetingText.isNullOrBlank() && btnText.contains("继续沟通")) {
                chatBtnNodes.forEach { it.recycle() }
                root.recycle()
                sendTaskMessage("岗位已处于沟通状态，无需定制回复")
                sendStep(STEP_HANDLE_NAVIGATE_BACK, 200L)
                return
            }

            sendTaskMessage("点击【立即沟通】按钮...")
            val clicked = GestureEngine.performClick(
                service = service,
                node = chatBtn,
                onSuccess = {
                    chatBtnNodes.forEach { it.recycle() }
                    root.recycle()
                    nextStep(850L)
                },
                onFail = {
                    chatBtnNodes.forEach { it.recycle() }
                    root.recycle()
                    retryCurrentStep(400L, "点击沟通按钮失败")
                }
            )
            if (!clicked) {
                chatBtnNodes.forEach { it.recycle() }
                root.recycle()
                retryCurrentStep(500L, "无法派发立即沟通点击手势")
            }
        } else {
            root.recycle()
            retryCurrentStep(500L, "未检测到聊天输入框或立即沟通按钮")
        }
    }

    private fun handleWaitWindowOrPopup() {
        waitWindowAttempts++
        val root = service.rootInActiveWindow
        if (root == null) {
            retryCurrentStep(400L, "等待界面渲染")
            return
        }

        val editNodes = root.findAccessibilityNodeInfosByViewId(ID_EDIT_TEXT)
        if (!editNodes.isNullOrEmpty()) {
            editNodes.forEach { it.recycle() }
            root.recycle()
            if (greetingText.isNullOrBlank()) {
                sendTaskMessage("已进入沟通窗口，直接安全退回...")
                sendStep(STEP_HANDLE_NAVIGATE_BACK, 300L)
            } else {
                sendStep(STEP_INPUT_GREETING_TEXT, 150L)
            }
            return
        }

        val safeBounds = AccessibilityNodeUtil.getSafeScreenBounds(service)
        val confirmPopupBtn = AccessibilityNodeUtil.dfsFindNode(root, safeBounds) { node ->
            val txt = node.text?.toString() ?: ""
            txt == "发招呼" || txt == "确定" || txt == "确认"
        }

        if (confirmPopupBtn != null) {
            sendTaskMessage("检测到确认弹窗，点击推进...")
            GestureEngine.performClick(
                service = service,
                node = confirmPopupBtn,
                onSuccess = {
                    confirmPopupBtn.recycle()
                    root.recycle()
                    if (greetingText.isNullOrBlank()) {
                        sendTaskMessage("已点击确认发招呼，正在安全退回...")
                        sendStep(STEP_HANDLE_NAVIGATE_BACK, 850L)
                    } else {
                        nextStep(850L)
                    }
                },
                onFail = {
                    confirmPopupBtn.recycle()
                    root.recycle()
                    retryCurrentStep(400L, "点击弹窗确认失败")
                }
            )
            return
        }

        // 🌟 关闭自动生成岗位回答时：若点击沟通后未跳出弹窗且未跳聊天页（如直接沟通生效），在检查按钮状态后安全完成
        if (greetingText.isNullOrBlank() && waitWindowAttempts >= 2) {
            val chatBtnNodes = root.findAccessibilityNodeInfosByViewId(ID_BTN_CHAT)
            val btnText = chatBtnNodes?.firstOrNull()?.text?.toString() ?: ""
            chatBtnNodes?.forEach { it.recycle() }
            root.recycle()

            if (btnText.contains("继续沟通") || waitWindowAttempts >= 3) {
                sendTaskMessage("立即沟通点击生效，无需生成个性化内容")
                sendStep(STEP_HANDLE_NAVIGATE_BACK, 300L)
                return
            }
        }

        root.recycle()
        retryCurrentStep(500L, "等待进入聊天窗口...")
    }

    private fun handleInputGreetingText() {
        if (greetingText.isNullOrBlank()) {
            sendStep(STEP_HANDLE_NAVIGATE_BACK, 150L)
            return
        }
        val root = service.rootInActiveWindow
        if (root == null) {
            retryCurrentStep(400L, "窗口未就绪")
            return
        }

        val editNodes = root.findAccessibilityNodeInfosByViewId(ID_EDIT_TEXT)
        val editNode = editNodes?.firstOrNull()

        if (editNode == null) {
            root.recycle()
            retryCurrentStep(400L, "未找到输入框节点")
            return
        }

        sendTaskMessage("正在填入定制破冰语...")
        sendClickAttempts = 0

        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, greetingText)
        }
        val setTextSuccess = editNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)

        editNodes.forEach { it.recycle() }
        root.recycle()

        if (setTextSuccess) {
            Log.i(tag, "成功填入文本，预留 850ms 等待多行文本重排与发送按钮激活...")
            nextStep(850L)
        } else {
            retryCurrentStep(300L, "输入框填词失败")
        }
    }

    private fun handleClickSendButton() {
        sendClickAttempts++
        val root = service.rootInActiveWindow
        if (root == null) {
            retryCurrentStep(400L, "无法获取发送界面根节点")
            return
        }

        val editNodes = root.findAccessibilityNodeInfosByViewId(ID_EDIT_TEXT)
        val editNode = editNodes?.firstOrNull()

        if (editNode == null) {
            root.recycle()
            retryCurrentStep(400L, "定位发送按钮时丢失输入框锚点")
            return
        }

        val editBounds = Rect()
        editNode.getBoundsInScreen(editBounds)

        val emotionNodes = root.findAccessibilityNodeInfosByViewId(ID_EMOTION_VIEW)
        val emotionNode = emotionNodes?.firstOrNull()
        val emotionBounds = Rect()
        val hasEmotionNode = emotionNode != null
        if (hasEmotionNode) {
            emotionNode?.getBoundsInScreen(emotionBounds)
        }

        var sendNodeClicked = false
        if (hasEmotionNode) {
            val safeBounds = AccessibilityNodeUtil.getSafeScreenBounds(service)
            val sendNode = AccessibilityNodeUtil.dfsFindNode(root, safeBounds) { node ->
                val r = Rect()
                node.getBoundsInScreen(r)
                r.left >= emotionBounds.right &&
                        Math.abs(r.centerY() - emotionBounds.centerY()) < 50 &&
                        (node.isClickable || node.className?.contains("ImageView") == true)
            }
            if (sendNode != null) {
                Log.d(tag, "命中发送按钮底层节点，尝试无障碍直触 performAction")
                sendNodeClicked = sendNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                sendNode.recycle()
            }
        }

        emotionNodes?.forEach { it.recycle() }
        editNodes?.forEach { it.recycle() }
        root.recycle()

        if (sendNodeClicked) {
            nextStep(650L)
            return
        }

        // 🌟【修改后】：获取屏幕真实物理尺寸，并基于 1220x2712 基准等比缩放
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

        sendTaskMessage("🎯 点击发送按钮 -> 标定坐标: ($targetX, $targetY) [第 $sendClickAttempts 次]")
        Log.i(tag, "执行精准发送点击: x=$targetX, y=$targetY")

        val clicked = GestureEngine.clickAt(
            service = service,
            x = targetX,
            y = targetY,
            onSuccess = {
                nextStep(650L)
            },
            onFail = {
                retryCurrentStep(400L, "物理坐标点击发送手势派发失败")
            }
        )

        if (!clicked) {
            retryCurrentStep(400L, "无法派发发送手势")
        }
    }

    /**
     * 步骤 5：智能双重送达校验 (彻底解决 Hint 提示词导致的误判死循环)
     */
    private fun handleVerifyAndFinish() {
        if (greetingText.isNullOrBlank()) {
            sendStep(STEP_HANDLE_NAVIGATE_BACK, 200L)
            return
        }
        val root = service.rootInActiveWindow
        val editNodes = root?.findAccessibilityNodeInfosByViewId(ID_EDIT_TEXT)
        val editNode = editNodes?.firstOrNull()

        // 强刷节点缓存，防止读到内存旧快照
        editNode?.refresh()
        val currentInputText = editNode?.text?.toString()?.trim() ?: ""

        // 取问候语的前 8 个字作为特征指纹
        val greetingFingerprint = if (greetingText.length > 8) greetingText.substring(0, 8) else greetingText

        // 判定 1：当前输入框内的文字已不再包含我们填入的话术指纹（即已经清空恢复为了灰字 Hint 占位符）
        val isInputTextCleared = currentInputText.isEmpty() || !currentInputText.contains(greetingFingerprint)

        // 判定 2：上方聊天记录流中已经出现了该条消息的气泡
        val chatBubbleNodes = root?.findAccessibilityNodeInfosByText(greetingFingerprint)
        val isMessageBubbleSent = chatBubbleNodes?.any { it != editNode } == true

        chatBubbleNodes?.forEach { it.recycle() }
        editNodes?.forEach { it.recycle() }
        root?.recycle()

        Log.d(tag, "送达研判 -> 输入框残留: '$currentInputText', 指纹清空: $isInputTextCleared, 气泡上屏: $isMessageBubbleSent")

        // 只要满足任意一项，均视为 100% 成功送达！
        if (isInputTextCleared || isMessageBubbleSent) {
            sendTaskMessage("✉️ 消息确认已送达！")
            Log.i(tag, "✅ 验证通过：破冰语已成功送出！")
            nextStep(500L) // 确认送达，从容进入步骤 6 安全返回
        } else {
            if (sendClickAttempts < 3) {
                Log.w(tag, "⚠️ 确实未检测到送达证据，重试点击发送 (尝试 $sendClickAttempts/3)...")
                sendStep(STEP_CLICK_SEND_BUTTON, 350L)
            } else {
                Log.e(tag, "❌ 连续重试均未生效，判定发送失败")
                failedTask("点击发送未生效，消息未送出")
            }
        }
    }

    private fun handleNavigateBack() {
        if (!autoNavigateBack) {
            sendTaskMessage("🎉 打招呼闭环完成")
            finishTask()
            return
        }

        sendTaskMessage("沟通完毕，正在安全退回...")
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
                    finishTask()
                },
                onFail = {
                    backNodes.forEach { it.recycle() }
                    root.recycle()
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    finishTask()
                }
            )
        } else {
            root?.recycle()
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            finishTask()
        }
    }

    override fun finishTask() {
        super.finishTask()
        onComplete?.invoke()
    }

    override fun failedTask(reason: String) {
        super.failedTask(reason)
        onError?.invoke(reason)
    }

    // 🌟【新增：被抢占强杀时释放 Deferred，防止 TaskDispatcher 死等】
    override fun onForceStopped() {
        onError?.invoke("打招呼任务被强制终止")
    }
}