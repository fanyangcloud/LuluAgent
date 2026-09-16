package com.lulu.agent.agent.communicator

import android.content.Context
import android.util.Log
import com.lulu.agent.accessibility.BossAccessibilityService
import com.lulu.agent.accessibility.model.ScrapedRawJob
import com.lulu.agent.accessibility.task.SendGreetingTask
import com.lulu.agent.agent.base.BaseAgent
import com.lulu.agent.agent.evaluator.EvaluatorResult
import com.lulu.agent.data.repository.ConfigRepository
import com.lulu.agent.data.repository.JobRepository
import com.lulu.agent.llm.DeepSeekClient

/**
 * 谈判公关代表：负责生成定制话术、打招呼投递与闭环履约记录
 */
class CommunicatorAgent(
    context: Context,
    private val deepSeekClient: DeepSeekClient,
    private val jobRepository: JobRepository,
    private val configRepository: ConfigRepository
) : BaseAgent(context, "CommunicatorAgent") {

    override fun start() {
        super.start()
        sendAgentLog("🤝 谈判公关代表已就位，准备开展破冰沟通")
    }

    /**
     * 发起破冰沟通全流程
     */
    suspend fun executeGreeting(
        rawJob: ScrapedRawJob,
        evaluatorResult: EvaluatorResult,
        onSuccess: () -> Unit,
        onFailed: (String) -> Unit
    ) {
        val jobId = rawJob.resolveJobId()
        val company = rawJob.companyName
        val title = rawJob.title

        // 1. 单日主动沟通频率限额检查 (防账号被封)
        val maxDailyLimit = configRepository.getMaxDailyGreetings()
        val canGreet = jobRepository.canGreetMoreToday(maxDailyLimit)
        if (!canGreet) {
            val limitMsg = "今日主动沟通已达上限 ($maxDailyLimit 次)，启动防风控休眠"
            sendAgentLog("🛑 $limitMsg", isHighlight = true)
            onFailed(limitMsg)
            return
        }

        // 2. 调用 DeepSeek 动态构思 50~90 字的针对性开场白
        sendAgentLog("✍️ 正在让模型结合技术契合点构思破冰语...")
        val greetingResult = deepSeekClient.generateGreeting(rawJob, evaluatorResult.highlights)

        val greetingText = greetingResult.fold(
            onSuccess = { resp ->
                resp.getCleanGreeting()
            },
            onFailure = { err ->
                Log.w(tag, "LLM话术生成降级: ${err.message}")
                // 异常保底话术
                "您好！关注到贵团队正在招聘${title}，我在该领域有深入的实战与架构经验，与岗位要求非常契合，希望能与您交流探讨！"
            }
        )

        sendAgentLog("💬 最终敲定问候语: $greetingText")

        // 3. 调度无障碍原子任务 SendGreetingTask 进行点击输入与发送
        val service = BossAccessibilityService.instance
        if (service == null) {
            val errMsg = "无障碍通道未连接，无法执行手势点击"
            sendAgentLog("❌ $errMsg")
            onFailed(errMsg)
            return
        }

        sendAgentLog("▶️ 调度 SendGreetingTask 执行点击沟通与发送...")

        // 使用协程 Deferred 真正等待任务完成
        val taskDeferred = kotlinx.coroutines.CompletableDeferred<Boolean>()

        val task = SendGreetingTask(
            greetingText = greetingText,
            autoNavigateBack = true,
            onComplete = { taskDeferred.complete(true) },
            onError = { taskDeferred.complete(false) }
        )

        service.executeTask(task)

        // 核心修复：挂起协程，死等任务真正把文字发出去、并确认送达！
        val isTaskSuccess = taskDeferred.await()

        if (isTaskSuccess) {
            // 4. 只有真正送达了，才登记数据库
            // 🌟【修改】：由 markJobCommunicated 改为 markJobDelivered，推进至已投递状态
            jobRepository.markJobDelivered(jobId)
            val todayTotal = jobRepository.getTodayCommunicatedCount()
            sendAgentLog("🎉 已成功向【$company - $title】发起沟通并投递！今日累计: $todayTotal 次", isHighlight = true)
            onSuccess()
        } else {
            sendAgentLog("❌ 招呼语未能送达，取消本次履约登记")
            onFailed("SendGreetingTask 执行失败")
        }
    }
}
