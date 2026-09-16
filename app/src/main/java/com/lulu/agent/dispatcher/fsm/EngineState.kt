package com.lulu.agent.dispatcher.fsm

/**
 * 自动化引擎有限状态机 (FSM) 核心状态
 */
enum class EngineState(
    val title: String,
    val isHalted: Boolean,
    val indicatorColorHex: String
) {
    /** 初始就绪/空闲状态 (灰灯) */
    IDLE("空闲就绪", false, "#9E9E9E"),

    /** 侦察中：在推荐列表滚动翻页搜寻 (蓝灯) */
    SCANNING("巡查检索中", false, "#2196F3"),

    /** 详查中：已进入详情页，正在抓取全量 JD (青灯) */
    INSPECTING("提取岗位详情", false, "#00BCD4"),

    /** 思考中：DeepSeek 大模型正在进行语义打分与话术构建 (紫灯) */
    THINKING("模型思考中", false, "#9C27B0"),

    /** 交互中：正在自动点击沟通并填入破冰语 (绿灯) */
    COMMUNICATING("拟人化沟通过程", false, "#4CAF50"),

    /** 暂停态：求职者主动点击暂停或等待冷却 (黄灯) */
    PAUSED("流水线挂起", true, "#FFC107"),

    /** 熔断急停：命中滑块验证码或风控限制 (红灯闪烁) */
    EMERGENCY_STOP("风控紧急熔断", true, "#F44336");

    fun isOperating(): Boolean = !isHalted && this != IDLE
}
