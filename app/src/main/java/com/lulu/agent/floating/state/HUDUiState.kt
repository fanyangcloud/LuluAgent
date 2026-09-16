package com.lulu.agent.floating.state

/**
 * 悬浮窗响应式 UI 状态实体
 */
data class HUDUiState(
    val stateTitle: String = "空闲就绪",
    val indicatorColorHex: String = "#9E9E9E",
    val currentTargetJob: String = "等待探寻岗位",
    val deepSeekThought: String = "模型思考引擎就绪...",
    val todayCommunicatedCount: Int = 0,
    val isOperating: Boolean = false,
    val isPaused: Boolean = false,
    val recentLogs: List<String> = emptyList()
) {
    fun appendLog(newLog: String, maxLogs: Int = 30): HUDUiState {
        val updatedList = (recentLogs + newLog).takeLast(maxLogs)
        return copy(recentLogs = updatedList)
    }
}
