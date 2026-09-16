package com.lulu.agent.llm

import com.lulu.agent.llm.config.ApiProtocol
import com.lulu.agent.llm.config.LlmConfig
import org.junit.Assert.*
import org.junit.Test

class LlmConfigTest {
    @Test fun legacyKeyKeepsDeepSeekEndpointAndModel() {
        val config = LlmConfig.fromStored(" old-key ", null, null, null).validated()
        assertEquals("https://api.deepseek.com/chat/completions", config.endpoint().toString())
        assertEquals("deepseek-chat", config.model)
        assertEquals("old-key", config.apiKey)
    }

    @Test fun preservesApiRootAndNormalizesTrailingSlash() {
        for (url in listOf(" https://example.com/proxy/v1 ", "https://example.com/proxy/v1/")) {
            val config = LlmConfig(url, " key ", " custom-model ", ApiProtocol.RESPONSES).validated()
            assertEquals("https://example.com/proxy/v1/responses", config.endpoint().toString())
            assertEquals("custom-model", config.model)
        }
    }

    @Test fun rejectsInvalidOrAmbiguousConfiguration() {
        for (url in listOf("", "http://example.com", "https://user:pass@example.com", "https://example.com?key=x", "https://example.com/#x", "https://example.com/v1/responses", "https://example.com/v1/chat/completions")) {
            assertThrows(IllegalArgumentException::class.java) { LlmConfig(url, "key").validated() }
        }
        assertThrows(IllegalArgumentException::class.java) { LlmConfig(apiKey = " ").validated() }
        assertThrows(IllegalArgumentException::class.java) { LlmConfig(apiKey = "key", model = " ").validated() }
        assertThrows(IllegalArgumentException::class.java) { LlmConfig.fromStored("key", null, null, "unknown") }
    }

    @Test fun configurationStringDoesNotExposeKey() {
        assertFalse(LlmConfig(apiKey = "secret-token").toString().contains("secret-token"))
    }
}
