package com.lulu.agent.llm.config

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class ApiProtocol(val path: String, val label: String) {
    CHAT_COMPLETIONS("chat/completions", "Chat Completions"),
    RESPONSES("responses", "Responses")
}

data class LlmConfig(
    val baseUrl: String = "https://api.deepseek.com/",
    val apiKey: String = "",
    val model: String = "deepseek-chat",
    val protocol: ApiProtocol = ApiProtocol.CHAT_COMPLETIONS
) {
    fun validated(): LlmConfig {
        val url = baseUrl.trim().toHttpUrlOrNull()
        require(url != null && url.isHttps) { "Base URL 必须是有效的 HTTPS 地址" }
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
            "Base URL 不能包含凭证、查询参数或片段"
        }
        val path = url.encodedPath.trimEnd('/')
        require(!path.endsWith("/responses") && !path.endsWith("/completions")) {
            "请填写 API 根地址，不要包含 responses 或 chat/completions"
        }
        require(apiKey.isNotBlank() && apiKey.trim().all { it.code in 33..126 }) { "请填写有效的 API Key" }
        require(model.isNotBlank()) { "请填写模型名称" }
        return copy(baseUrl = url.toString().trimEnd('/') + "/", apiKey = apiKey.trim(), model = model.trim())
    }

    fun endpoint(): HttpUrl = requireNotNull((baseUrl.trim().trimEnd('/') + "/" + protocol.path).toHttpUrlOrNull()) {
        "API 地址无效"
    }

    // Never include credentials in diagnostics or generated data-class output.
    override fun toString() = "LlmConfig(model=$model, protocol=$protocol, apiKey=<redacted>)"

    companion object {
        fun fromStored(key: String?, url: String?, model: String?, protocol: String?): LlmConfig = LlmConfig(
            apiKey = key.orEmpty().trim(),
            baseUrl = url ?: "https://api.deepseek.com/",
            model = model ?: "deepseek-chat",
            protocol = protocol?.let { value ->
                ApiProtocol.entries.firstOrNull { it.name == value }
                    ?: throw IllegalArgumentException("接口协议无效，请重新保存 API 配置")
            } ?: ApiProtocol.CHAT_COMPLETIONS
        )
    }
}
