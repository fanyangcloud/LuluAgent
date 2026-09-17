package com.lulu.agent.dispatcher.fsm

import android.util.Log

class StateMachine(initialState: EngineState = EngineState.IDLE) {

    private val tag = "StateMachine"

    @Volatile
    private var currentState: EngineState = initialState

    private val listeners = mutableListOf<(old: EngineState, new: EngineState, reason: String) -> Unit>()
    private val stateLock = Any()

    fun getCurrentState(): EngineState = currentState

    /**
     * 判定是否允许跃迁到目标状态
     */
    fun canTransitionTo(target: EngineState): Boolean {
        synchronized(stateLock) {
            if (currentState == target) return false

            // 规则 1：任何状态都可以无条件切换到 EMERGENCY_STOP（风控最高优先级熔断）
            if (target == EngineState.EMERGENCY_STOP) return true

            // 规则 2：处于 EMERGENCY_STOP 时，只能人工重置回 IDLE
            if (currentState == EngineState.EMERGENCY_STOP) {
                return target == EngineState.IDLE
            }

            // 规则 3：处于 PAUSED 时，只能恢复到工作态或归位 IDLE / 触发 EMERGENCY_STOP
            if (currentState == EngineState.PAUSED) {
                return target == EngineState.SCANNING || target == EngineState.IDLE
            }

            // 规则 4：通用状态流转白名单 (优化防御性流转)
            return when (currentState) {
                EngineState.IDLE -> target == EngineState.SCANNING
                EngineState.SCANNING -> target == EngineState.INSPECTING || target == EngineState.PAUSED || target == EngineState.IDLE
                EngineState.INSPECTING -> target == EngineState.THINKING || target == EngineState.SCANNING || target == EngineState.PAUSED
                // 🌟 容错：允许评估后直接进入下一个卡片的提取，或重置
                EngineState.THINKING -> target == EngineState.COMMUNICATING || target == EngineState.INSPECTING || target == EngineState.SCANNING || target == EngineState.PAUSED
                // 🌟 容错：沟通完成后，既允许回 SCANNING，也允许极端情况下直接处理下一个卡片的 INSPECTING
                EngineState.COMMUNICATING -> target == EngineState.SCANNING || target == EngineState.INSPECTING || target == EngineState.PAUSED || target == EngineState.IDLE
                else -> false
            }
        }
    }

    /**
     * 执行状态跃迁并通知监听者
     */
    fun transitionTo(target: EngineState, reason: String = ""): Boolean {
        synchronized(stateLock) {
            if (!canTransitionTo(target)) {
                Log.w(tag, "非法状态跃迁拦截: $currentState -> $target (原因: $reason)")
                return false
            }

            val oldState = currentState
            currentState = target
            Log.i(tag, "状态流转成功: $oldState -> $target (原因: $reason)")

            // 触发所有监听器
            val callbacks = ArrayList(listeners)
            callbacks.forEach { it.invoke(oldState, target, reason) }
            return true
        }
    }

    fun addListener(listener: (old: EngineState, new: EngineState, reason: String) -> Unit) {
        synchronized(stateLock) {
            if (!listeners.contains(listener)) {
                listeners.add(listener)
            }
        }
    }

    fun removeListener(listener: (old: EngineState, new: EngineState, reason: String) -> Unit) {
        synchronized(stateLock) {
            listeners.remove(listener)
        }
    }

    fun reset() {
        synchronized(stateLock) {
            transitionTo(EngineState.IDLE, "系统重置")
        }
    }
}
