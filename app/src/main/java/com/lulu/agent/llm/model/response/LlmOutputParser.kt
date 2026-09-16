package com.lulu.agent.llm.model.response

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Reject unusable business output before caching, auditing success or sending a greeting. */
object LlmOutputParser {
    fun evaluation(raw: String): JDEvalResponse = parse(raw) { json ->
        val score = json.get("match_score")
        require(score != null && score.isJsonPrimitive && score.asJsonPrimitive.isNumber)
        val number = score.asDouble
        require(number in 0.0..100.0 && number == number.toInt().toDouble())
        val decision = json.text("decision")
        require(decision in listOf(JDEvalResponse.DECISION_ACCEPT, JDEvalResponse.DECISION_REJECT))
        JDEvalResponse(number.toInt(), decision, json.texts("highlights"), json.texts("risks"), json.text("summary_reason"))
    }

    fun greeting(raw: String): ChatGenerationResponse = parse(raw) { json ->
        ChatGenerationResponse(json.text("greeting_text"), json.text("key_selling_point"))
            .also { require(it.isValid()) }
    }

    fun ping(raw: String): Boolean = parse(raw) { json ->
        require(json.text("status") == "ok")
        true
    }

    private fun JsonObject.text(name: String): String {
        val value = get(name)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString)
        return value.asString
    }

    private fun JsonObject.texts(name: String): List<String> {
        val value = get(name)
        require(value != null && value.isJsonArray)
        return value.asJsonArray.map {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isString)
            it.asString
        }
    }

    private fun <T> parse(raw: String, block: (JsonObject) -> T): T {
        try {
            val text = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            return block(JsonParser.parseString(text).asJsonObject)
        } catch (e: Exception) {
            throw IllegalArgumentException("模型返回的 JSON 不符合所需业务格式")
        }
    }
}
