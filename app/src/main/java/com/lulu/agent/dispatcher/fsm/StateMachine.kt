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

            // 规则 3：处于 PAUSED 时，可恢复到巡检、扫描或重置
            if (currentState == EngineState.PAUSED) {
                return target == EngineState.CHECKING_INBOX || target == EngineState.SCANNING || target == EngineState.IDLE
            }

            // 规则 4：通用状态流转白名单 (支持双循环交替调度)
            return when (currentState) {
                EngineState.IDLE -> target == EngineState.CHECKING_INBOX || target == EngineState.SCANNING

                // 消息处理环
                EngineState.CHECKING_INBOX -> target == EngineState.REPLYING_CHAT || target == EngineState.SCANNING || target == EngineState.THINKING || target == EngineState.PAUSED || target == EngineState.IDLE
                EngineState.REPLYING_CHAT -> target == EngineState.CHECKING_INBOX || target == EngineState.SCANNING || target == EngineState.THINKING || target == EngineState.PAUSED || target == EngineState.IDLE

                // 推荐探索环
                EngineState.SCANNING -> target == EngineState.INSPECTING || target == EngineState.CHECKING_INBOX || target == EngineState.PAUSED || target == EngineState.IDLE
                EngineState.INSPECTING -> target == EngineState.THINKING || target == EngineState.SCANNING || target == EngineState.CHECKING_INBOX || target == EngineState.PAUSED
                EngineState.THINKING -> target == EngineState.COMMUNICATING || target == EngineState.REPLYING_CHAT || target == EngineState.INSPECTING || target == EngineState.SCANNING || target == EngineState.CHECKING_INBOX || target == EngineState.PAUSED
                EngineState.COMMUNICATING -> target == EngineState.SCANNING || target == EngineState.CHECKING_INBOX || target == EngineState.INSPECTING || target == EngineState.PAUSED || target == EngineState.IDLE

                // 🌟 增加 else 兜底分支，解决 Kotlin 编译器穷尽性检查报错
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