package com.lulu.agent.llm.api

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lulu.agent.llm.config.ApiProtocol
import com.lulu.agent.llm.config.LlmConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class LlmResult(val text: String, val inputTokens: Int, val outputTokens: Int, val totalTokens: Int)

/** Stateless protocol adapter. Each call owns one endpoint/key/model snapshot. */
class OpenAiCompatibleTransport(client: OkHttpClient = OkHttpClient()) {
    private val client = client.newBuilder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()
    private val gson = Gson()

    suspend fun generate(config: LlmConfig, system: String, user: String, temperature: Double? = null): LlmResult {
        val messages = listOf(mapOf("role" to "system", "content" to system), mapOf("role" to "user", "content" to user))
        val payload = linkedMapOf<String, Any>("model" to config.model, "stream" to false)
        when (config.protocol) {
            ApiProtocol.CHAT_COMPLETIONS -> {
                payload["messages"] = messages
                payload["response_format"] = mapOf("type" to "json_object")
            }
            ApiProtocol.RESPONSES -> {
                payload["input"] = messages
                payload["store"] = false
                payload["text"] = mapOf("format" to mapOf("type" to "json_object"))
            }
        }
        if (temperature != null && config.endpoint().host == "api.deepseek.com" &&
            config.model == "deepseek-chat" && config.protocol == ApiProtocol.CHAT_COMPLETIONS) {
            payload["temperature"] = temperature
        }
        val request = Request.Builder().url(config.endpoint())
            .header("Authorization", "Bearer ${config.apiKey}")
            .post(gson.toJson(payload).toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        repeat(3) { attempt ->
            try {
                return parse(execute(request), config.protocol)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: IOException) {
                if (attempt == 2) throw IOException("API 网络请求失败，请检查网络和服务地址")
            } catch (e: HttpFailure) {
                if (!e.retryable || attempt == 2) throw e
            }
            delay(1000L shl attempt)
        }
        error("API 请求失败")
    }

    private class HttpFailure(code: Int) : IllegalStateException("API 请求失败（HTTP $code）") {
        val retryable = code == 429 || code in 500..599
    }

    private suspend fun execute(request: Request): String = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        if (!it.isSuccessful) throw HttpFailure(it.code)
                        it.body?.string() ?: throw IllegalStateException("API 返回空响应")
                    }
                }
                if (continuation.isActive) result.fold(continuation::resume, continuation::resumeWithException)
            }
        })
    }

    private fun parse(raw: String, protocol: ApiProtocol): LlmResult {
        try {
            val body = JsonParser.parseString(raw).asJsonObject
            check(body.get("error")?.isJsonNull != false) { "API 返回错误" }
            val text = when (protocol) {
                ApiProtocol.CHAT_COMPLETIONS -> {
                    val choice = body.getAsJsonArray("choices").first().asJsonObject
                    val finish = choice.string("finish_reason")
                    check(finish == null || finish == "stop") { "模型输出未完整结束" }
                    val message = choice.getAsJsonObject("message")
                    check(message.string("refusal").isNullOrBlank()) { "模型拒绝了请求" }
                    message.string("content").orEmpty()
                }
                ApiProtocol.RESPONSES -> {
                    check(body.string("status") == "completed") { "模型响应未完成" }
                    buildString {
                        for (item in body.getAsJsonArray("output")) {
                            val message = item.asJsonObject
                            if (message.string("type") != "message" || message.string("role") != "assistant") continue
                            check(message.string("status").let { it == null || it == "completed" }) { "模型输出未完整结束" }
                            for (part in message.getAsJsonArray("content")) {
                                val content = part.asJsonObject
                                check(content.string("type") != "refusal") { "模型拒绝了请求" }
                                if (content.string("type") == "output_text") append(content.string("text").orEmpty())
                            }
                        }
                    }
                }
            }
            check(text.isNotBlank()) { "API 未返回有效文本" }
            val usage = body.get("usage")?.takeUnless { it.isJsonNull }?.asJsonObject
            val input = usage?.number(if (protocol == ApiProtocol.RESPONSES) "input_tokens" else "prompt_tokens") ?: 0
            val output = usage?.number(if (protocol == ApiProtocol.RESPONSES) "output_tokens" else "completion_tokens") ?: 0
            return LlmResult(text, input, output, usage?.number("total_tokens") ?: (input + output))
        } catch (e: Exception) {
            // Provider payloads can echo credentials; expose only our own diagnostic.
            throw IllegalStateException("API 响应无效、被拒绝或未完整结束")
        }
    }

    private fun JsonObject.string(name: String): String? = get(name)?.takeUnless { it.isJsonNull }?.asString
    private fun JsonObject.number(name: String): Int? = get(name)?.takeUnless { it.isJsonNull }?.asInt?.coerceAtLeast(0)
}
