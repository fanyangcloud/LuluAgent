package com.lulu.agent.llm

import com.google.gson.JsonParser
import com.lulu.agent.llm.api.OpenAiCompatibleTransport
import com.lulu.agent.llm.config.ApiProtocol
import com.lulu.agent.llm.config.LlmConfig
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class OpenAiCompatibleTransportTest {
    private val server = MockWebServer()
    private val transport = OpenAiCompatibleTransport(OkHttpClient())
    private val chat = """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"{\"status\":\"ok\"}"}}],"usage":{"prompt_tokens":12,"completion_tokens":8,"total_tokens":20}}"""
    private val responses = """{"status":"completed","output":[{"type":"reasoning","summary":[]},{"type":"message","role":"assistant","content":[{"type":"output_text","text":"{\"status\":"},{"type":"output_text","text":"\"ok\"}"}]}],"usage":{"input_tokens":12,"output_tokens":8}}"""

    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()
    private fun config(protocol: ApiProtocol = ApiProtocol.CHAT_COMPLETIONS) =
        LlmConfig(server.url("/proxy/v1/").toString(), "test-key", "custom-model", protocol)

    @Test fun chatSendsConfiguredRequestAndParsesUsage() = runBlocking {
        server.enqueue(MockResponse().setBody(chat))
        val result = transport.generate(config(), "Output JSON", "ping")
        val request = server.takeRequest()
        assertEquals("/proxy/v1/chat/completions", request.path)
        assertEquals("Bearer test-key", request.getHeader("Authorization"))
        val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertEquals("custom-model", body["model"].asString)
        assertEquals("Output JSON", body["messages"].asJsonArray[0].asJsonObject["content"].asString)
        assertEquals("json_object", body["response_format"].asJsonObject["type"].asString)
        assertFalse(body["stream"].asBoolean)
        assertFalse(body.has("temperature"))
        assertFalse(body.has("input"))
        assertEquals("{\"status\":\"ok\"}", result.text)
        assertEquals(12, result.inputTokens)
        assertEquals(8, result.outputTokens)
        assertEquals(20, result.totalTokens)
    }

    @Test fun responsesSkipsReasoningAndJoinsTextParts() = runBlocking {
        server.enqueue(MockResponse().setBody(responses))
        val result = transport.generate(config(ApiProtocol.RESPONSES), "Output JSON", "ping")
        val request = server.takeRequest()
        assertEquals("/proxy/v1/responses", request.path)
        val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertEquals("ping", body["input"].asJsonArray[1].asJsonObject["content"].asString)
        assertEquals("json_object", body["text"].asJsonObject["format"].asJsonObject["type"].asString)
        assertFalse(body["store"].asBoolean)
        assertFalse(body.has("messages"))
        assertFalse(body.has("response_format"))
        assertEquals("{\"status\":\"ok\"}", result.text)
        assertEquals(12, result.inputTokens)
        assertEquals(8, result.outputTokens)
        assertEquals(20, result.totalTokens)
    }

    @Test fun missingUsageDoesNotDiscardValidOutput() = runBlocking {
        server.enqueue(MockResponse().setBody(chat.substringBefore(",\"usage\"") + "}"))
        assertEquals(0, transport.generate(config(), "JSON", "ping").totalTokens)
    }

    @Test fun rejectsRefusedIncompleteEmptyAndMalformedResponsesWithoutRetry() = runBlocking {
        val invalid = listOf(
            ApiProtocol.CHAT_COMPLETIONS to chat.replace("\"stop\"", "\"length\""),
            ApiProtocol.CHAT_COMPLETIONS to """{"choices":[{"message":{"refusal":"no","content":"{}"}}]}""",
            ApiProtocol.CHAT_COMPLETIONS to """{"choices":[]}""",
            ApiProtocol.RESPONSES to responses.replace("completed", "incomplete"),
            ApiProtocol.RESPONSES to responses.replace("completed", "failed"),
            ApiProtocol.RESPONSES to """{"status":"completed","output":[{"type":"message","role":"assistant","content":[{"type":"refusal","refusal":"no"}]}]}""",
            ApiProtocol.RESPONSES to """{"status":"completed","output":[]}""",
            ApiProtocol.RESPONSES to "not json"
        )
        for ((protocol, body) in invalid) {
            server.enqueue(MockResponse().setBody(body))
            val failure = runCatching { transport.generate(config(protocol), "JSON", "ping") }.exceptionOrNull()
            assertNotNull(body, failure)
        }
        assertEquals(invalid.size, server.requestCount)
    }

    @Test fun authenticationErrorsAreNotRetriedOrEchoed() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("test-key"))
        val error = runCatching { transport.generate(config(), "JSON", "ping") }.exceptionOrNull()
        assertNotNull(error)
        assertFalse(error!!.message.orEmpty().contains("test-key"))
        assertEquals(1, server.requestCount)
    }

    @Test fun transientServerFailureIsRetried() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody(chat))
        assertEquals(20, transport.generate(config(), "JSON", "ping").totalTokens)
        assertEquals(2, server.requestCount)
    }

    @Test fun switchingConfigurationUsesNewEndpointKeyModelAndProtocol() = runBlocking {
        server.enqueue(MockResponse().setBody(chat))
        transport.generate(config(), "JSON", "ping")
        server.takeRequest()
        server.enqueue(MockResponse().setBody(responses))
        transport.generate(config(ApiProtocol.RESPONSES).copy(baseUrl = server.url("/other/").toString(), apiKey = "second-key", model = "second-model"), "JSON", "ping")
        val request = server.takeRequest()
        assertEquals("/other/responses", request.path)
        assertEquals("Bearer second-key", request.getHeader("Authorization"))
        assertEquals("second-model", JsonParser.parseString(request.body.readUtf8()).asJsonObject["model"].asString)
    }

    @Test fun redirectsDoNotForwardCredentials() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/unexpected")))
        assertTrue(runCatching { transport.generate(config(), "JSON", "ping") }.isFailure)
        assertEquals(1, server.requestCount)
    }

    @Test fun cancellationStopsPendingCallWithoutRetry() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job = launch(Dispatchers.IO) { transport.generate(config(), "JSON", "ping") }
        assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
        withTimeout(2000) { job.cancelAndJoin() }
        assertEquals(1, server.requestCount)
    }
}
