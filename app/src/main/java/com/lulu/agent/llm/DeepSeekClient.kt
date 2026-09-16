package com.lulu.agent.llm

import android.util.Log
import com.lulu.agent.accessibility.model.ScrapedRawJob
import com.lulu.agent.data.local.entity.LLMAuditLogEntity
import com.lulu.agent.data.repository.ConfigRepository
import com.lulu.agent.data.repository.JobRepository
import com.lulu.agent.llm.api.OpenAiCompatibleTransport
import com.lulu.agent.llm.config.LlmConfig
import com.lulu.agent.llm.limiter.DeduplicationCache
import com.lulu.agent.llm.limiter.TokenUsageTracker
import com.lulu.agent.llm.model.request.DeepSeekChatRequest
import com.lulu.agent.llm.model.response.ChatGenerationResponse
import com.lulu.agent.llm.model.response.JDEvalResponse
import com.lulu.agent.llm.model.response.LlmOutputParser
import com.lulu.agent.llm.prompt.PromptManager
import kotlinx.coroutines.CancellationException

/**
 * 可配置双协议大模型网络门面单例（保留原类名以兼容调用方）
 *
 * 核心指标：
 * - 60s 完整超时门限
 * - 最多 3 次异常重试与指数退避
 * - 纯非流式结构化输出
 */
class DeepSeekClient(
    private val configRepository: ConfigRepository,
    private val jobRepository: JobRepository,
    private val tokenUsageTracker: TokenUsageTracker,
    private val promptManager: PromptManager
) {

    private val tag = "DeepSeekClient"

    private val transport = OpenAiCompatibleTransport()
    private var cacheConfig: LlmConfig? = null
    private var cacheGeneration = 0L

    @Synchronized
    private fun cacheScope(config: LlmConfig): String {
        if (cacheConfig != config) {
            cacheConfig = config
            cacheGeneration++
        }
        return cacheGeneration.toString()
    }

    private suspend fun <T> apiResult(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        Result.failure(e)
    }

    companion object {

        @Volatile
        private var instance: DeepSeekClient? = null

        fun getInstance(
            configRepository: ConfigRepository,
            jobRepository: JobRepository,
            tokenUsageTracker: TokenUsageTracker,
            promptManager: PromptManager
        ): DeepSeekClient {
            return instance ?: synchronized(this) {
                instance ?: DeepSeekClient(
                    configRepository,
                    jobRepository,
                    tokenUsageTracker,
                    promptManager
                ).also { instance = it }
            }
        }
    }

    // ==================== 核心业务暴露 API ====================

    /**
     * 评估岗位契合度（包含缓存拦截、预算校验、3次重试与审计日志）
     */
    suspend fun evaluateJob(rawJob: ScrapedRawJob): Result<JDEvalResponse> {
        val config = try { configRepository.getLlmConfig().validated() } catch (e: IllegalArgumentException) {
            return Result.failure(e)
        }
        val scope = cacheScope(config)
        val jdText = rawJob.jobDescription

        // 1. 指纹缓存前置拦截 (0 Token 消耗)
        val cached = DeduplicationCache.getCachedEvaluation(jdText, scope)
        if (cached != null) {
            Log.i(tag, "🎯 命中内存指纹缓存，复用前序评估结果: ${cached.matchScore}分")
            return Result.success(cached)
        }

        // 2. 每日预算熔断校验
        if (tokenUsageTracker.isBudgetExceeded()) {
            val msg = "今日模型预算已超出限额，自动化熔断保护"
            Log.e(tag, msg)
            return Result.failure(IllegalStateException(msg))
        }

        // 3. 构建请求体（强制非流式与 JSON 输出）
        val request = promptManager.buildEvaluationRequest(rawJob, model = config.model).copy(stream = false)
        val startTime = System.currentTimeMillis()

        // 4. 执行 3 次重试网络调用
        val networkResult = apiResult {
            transport.generate(config, request.messages.first().content, request.messages.last().content, request.temperature)
        }

        val duration = System.currentTimeMillis() - startTime

        return networkResult.fold(
            onSuccess = { response ->
                val rawJson = response.text
                val promptTokens = response.inputTokens
                val completionTokens = response.outputTokens
                val totalTokens = response.totalTokens

                try {
                    val evalResponse = LlmOutputParser.evaluation(rawJson)

                    // 写入指纹缓存
                    DeduplicationCache.putEvaluation(jdText, evalResponse, scope)

                    // 记录 Token 与数据库审计流水
                    tokenUsageTracker.recordTokens(promptTokens, completionTokens)
                    jobRepository.recordLLMAudit(
                        scene = LLMAuditLogEntity.SCENE_EVALUATE,
                        jobId = rawJob.resolveJobId(),
                        modelName = request.model,
                        prompt = request.messages.lastOrNull()?.content ?: "",
                        response = rawJson,
                        promptTokens = promptTokens,
                        completionTokens = completionTokens,
                        totalTokens = totalTokens,
                        durationMs = duration,
                        isSuccess = true
                    )

                    Log.i(tag, "✅ 岗位评估成功 | 评分: ${evalResponse.matchScore} | 耗时: ${duration}ms")
                    Result.success(evalResponse)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    val parseErr = "岗位评估结果处理失败"
                    Log.e(tag, parseErr)
                    recordFailedAudit(rawJob.resolveJobId(), request, rawJson, duration, parseErr)
                    Result.failure(e)
                }
            },
            onFailure = { error ->
                Log.e(tag, "❌ 评估网络请求重试耗尽失败: ${error.message}")
                recordFailedAudit(rawJob.resolveJobId(), request, "", duration, error.message ?: "未知网络异常")
                Result.failure(error)
            }
        )
    }

    /**
     * 生成定制破冰问候语（同样配置 60s 超时与 3 次重试）
     */
    suspend fun generateGreeting(
        rawJob: ScrapedRawJob,
        highlights: List<String>
    ): Result<ChatGenerationResponse> {
        val config = try { configRepository.getLlmConfig().validated() } catch (e: IllegalArgumentException) {
            return Result.failure(e)
        }
        if (tokenUsageTracker.isBudgetExceeded()) {
            return Result.failure(IllegalStateException("今日大模型预算超标，已终止话术生成"))
        }

        val request = promptManager.buildGreetingRequest(rawJob, highlights, model = config.model).copy(stream = false)
        val startTime = System.currentTimeMillis()

        val networkResult = apiResult {
            transport.generate(config, request.messages.first().content, request.messages.last().content, request.temperature)
        }

        val duration = System.currentTimeMillis() - startTime

        return networkResult.fold(
            onSuccess = { response ->
                val rawJson = response.text
                val promptTokens = response.inputTokens
                val completionTokens = response.outputTokens
                val totalTokens = response.totalTokens

                try {
                    val chatResponse = LlmOutputParser.greeting(rawJson)

                    tokenUsageTracker.recordTokens(promptTokens, completionTokens)
                    jobRepository.recordLLMAudit(
                        scene = LLMAuditLogEntity.SCENE_GREETING,
                        jobId = rawJob.resolveJobId(),
                        modelName = request.model,
                        prompt = request.messages.lastOrNull()?.content ?: "",
                        response = rawJson,
                        promptTokens = promptTokens,
                        completionTokens = completionTokens,
                        totalTokens = totalTokens,
                        durationMs = duration,
                        isSuccess = true
                    )

                    Log.i(tag, "✅ 问候语生成成功: ${chatResponse.greetingText}")
                    Result.success(chatResponse)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    val parseErr = "问候语结果处理失败"
                    Log.e(tag, parseErr)
                    recordFailedAudit(rawJob.resolveJobId(), request, rawJson, duration, parseErr)
                    Result.failure(e)
                }
            },
            onFailure = { error ->
                recordFailedAudit(rawJob.resolveJobId(), request, "", duration, error.message ?: "网络异常")
                Result.failure(error)
            }
        )
    }

    /**
     * 设置页面连通性测试
     */
    suspend fun testConnection(config: LlmConfig): Result<Boolean> = apiResult {
        val response = transport.generate(
            config.validated(),
            "你是一个连通性测试助手，必须输出合法 JSON: {\"status\": \"ok\"}",
            "ping"
        )
        LlmOutputParser.ping(response.text)
    }

    private suspend fun recordFailedAudit(
        jobId: String,
        request: DeepSeekChatRequest,
        rawResponse: String,
        durationMs: Long,
        error: String
    ) {
        jobRepository.recordLLMAudit(
            scene = "API_REQUEST_FAILED",
            jobId = jobId,
            modelName = request.model,
            prompt = request.messages.lastOrNull()?.content ?: "",
            response = rawResponse,
            promptTokens = 0,
            completionTokens = 0,
            totalTokens = 0,
            durationMs = durationMs,
            isSuccess = false,
            errorMessage = error
        )
    }

}
