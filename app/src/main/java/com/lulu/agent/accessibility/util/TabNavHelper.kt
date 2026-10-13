package com.lulu.agent.accessibility.util

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 底部主导航栏 Tab 切换与校验辅助工具类
 * 基于真实 UI dump:
 * - Tab 1 [职位]: com.hpbr.bosszhipin:id/cl_tab_1, 文字 tv_tab_1, bounds: [0,2420][305,2570]
 * - Tab 3 [消息]: com.hpbr.bosszhipin:id/cl_tab_3, 文字 tv_tab_3, bounds: [610,2420][915,2570]
 */
object TabNavHelper {

    private const val TAG = "TabNavHelper"

    private const val ID_TAB_1_CONTAINER = "com.hpbr.bosszhipin:id/cl_tab_1"
    private const val ID_TAB_1_TEXT = "com.hpbr.bosszhipin:id/tv_tab_1"

    private const val ID_TAB_3_CONTAINER = "com.hpbr.bosszhipin:id/cl_tab_3"
    private const val ID_TAB_3_TEXT = "com.hpbr.bosszhipin:id/tv_tab_3"

    /**
     * 判断当前是否处于【职位】Tab
     */
    fun isJobTabSelected(service: AccessibilityService): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByViewId(ID_TAB_1_TEXT)
        val selected = nodes?.firstOrNull()?.isSelected == true
        nodes?.forEach { it.recycle() }
        root.recycle()
        return selected
    }

    /**
     * 判断当前是否处于【消息】Tab
     */
    fun isMessageTabSelected(service: AccessibilityService): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByViewId(ID_TAB_3_TEXT)
        val selected = nodes?.firstOrNull()?.isSelected == true
        nodes?.forEach { it.recycle() }
        root.recycle()
        return selected
    }

    /**
     * 切换到底部 Tab 1【职位】
     */
    fun switchToJobTab(
        service: AccessibilityService,
        onSuccess: (() -> Unit)? = null,
        onFail: (() -> Unit)? = null
    ): Boolean {
        if (isJobTabSelected(service)) {
            Log.d(TAG, "已在【职位】Tab，无需切换")
            onSuccess?.invoke()
            return true
        }

        val root = service.rootInActiveWindow
        val tabNodes = root?.findAccessibilityNodeInfosByViewId(ID_TAB_1_CONTAINER)
        val tabNode = tabNodes?.firstOrNull()

        if (tabNode != null) {
            val clicked = GestureEngine.performClick(service, tabNode, onSuccess, onFail)
            tabNodes.forEach { it.recycle() }
            root.recycle()
            return clicked
        }

        root?.recycle()
        // 物理坐标兜底 (基准区域 bounds: [0,2420][305,2570])
        val screenBounds = AccessibilityNodeUtil.getScreenBounds(service)
        val targetX = (screenBounds.width() * 0.125f).toInt()
        val targetY = (screenBounds.height() * 0.94f).toInt()
        Log.d(TAG, "未找到 cl_tab_1 节点，使用坐标点击【职位】Tab: ($targetX, $targetY)")
        return GestureEngine.clickAt(service, targetX, targetY, onSuccess, onFail)
    }

    /**
     * 切换到底部 Tab 3【消息】
     */
    fun switchToMessageTab(
        service: AccessibilityService,
        onSuccess: (() -> Unit)? = null,
        onFail: (() -> Unit)? = null
    ): Boolean {
        if (isMessageTabSelected(service)) {
            Log.d(TAG, "已在【消息】Tab，无需切换")
            onSuccess?.invoke()
            return true
        }

        val root = service.rootInActiveWindow
        val tabNodes = root?.findAccessibilityNodeInfosByViewId(ID_TAB_3_CONTAINER)
        val tabNode = tabNodes?.firstOrNull()

        if (tabNode != null) {
            val clicked = GestureEngine.performClick(service, tabNode, onSuccess, onFail)
            tabNodes.forEach { it.recycle() }
            root.recycle()
            return clicked
        }

        root?.recycle()
        // 物理坐标兜底 (基准区域 bounds: [610,2420][915,2570])
        val screenBounds = AccessibilityNodeUtil.getScreenBounds(service)
        val targetX = (screenBounds.width() * 0.625f).toInt()
        val targetY = (screenBounds.height() * 0.94f).toInt()
        Log.d(TAG, "未找到 cl_tab_3 节点，使用坐标点击【消息】Tab: ($targetX, $targetY)")
        return GestureEngine.clickAt(service, targetX, targetY, onSuccess, onFail)
    }
}