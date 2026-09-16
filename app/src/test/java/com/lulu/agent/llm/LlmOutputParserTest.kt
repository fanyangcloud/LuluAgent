package com.lulu.agent.llm

import com.lulu.agent.llm.model.response.LlmOutputParser
import org.junit.Assert.*
import org.junit.Test

class LlmOutputParserTest {
    @Test fun parsesEvaluationAndMarkdownWrappedGreeting() {
        val evaluation = LlmOutputParser.evaluation("""{"match_score":81,"decision":"ACCEPT","highlights":["Kotlin"],"risks":[],"summary_reason":"适合"}""")
        assertTrue(evaluation.isApproved())
        assertEquals(listOf("Kotlin"), evaluation.highlights)
        assertEquals("你好", LlmOutputParser.greeting("```json\n{\"greeting_text\":\"你好\",\"key_selling_point\":\"Kotlin\"}\n```").getCleanGreeting())
    }

    @Test fun incompleteOrMalformedBusinessOutputCannotSucceed() {
        for (json in listOf("null", "{}", "not JSON", """{"match_score":101,"decision":"ACCEPT"}""", """{"match_score":80,"decision":"MAYBE"}""")) {
            assertThrows(IllegalArgumentException::class.java) { LlmOutputParser.evaluation(json) }
        }
        for (json in listOf("null", "{}", """{"greeting_text":" "}""", """{"greeting_text":null}""")) {
            assertThrows(IllegalArgumentException::class.java) { LlmOutputParser.greeting(json) }
        }
    }

    @Test fun pingRequiresExplicitOkStatus() {
        assertTrue(LlmOutputParser.ping("{\"status\":\"ok\"}"))
        for (json in listOf("{}", "null", "hello", "{\"status\":\"error\"}")) {
            assertThrows(IllegalArgumentException::class.java) { LlmOutputParser.ping(json) }
        }
    }
}
