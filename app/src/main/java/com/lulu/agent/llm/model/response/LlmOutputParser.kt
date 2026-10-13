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

    /**
     * 解析多轮对话深度答复与熔断决策
     */
    fun dialogue(raw: String): DialogueReplyResponse = parse(raw) { json ->
        val decision = json.text("decision")
        require(
            decision in listOf(
                DialogueReplyResponse.DECISION_CONTINUE,
                DialogueReplyResponse.DECISION_SEND_RESUME,
                DialogueReplyResponse.DECISION_ASK_INTERVIEW,
                DialogueReplyResponse.DECISION_REJECT
            )
        )
        val replyText = json.text("reply_text")
        val reason = json.optText("reason")
        val intentTag = json.optText("intent_tag")

        DialogueReplyResponse(
            decision = decision,
            replyText = replyText,
            reason = reason,
            intentTag = intentTag
        ).also { require(it.isValid()) }
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

    private fun JsonObject.optText(name: String): String {
        val value = get(name) ?: return ""
        return if (value.isJsonPrimitive && value.asJsonPrimitive.isString) value.asString else ""
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