package org.projectnia.app.agent

import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class AgentReply(
    val text: String,
    val signGlosses: List<String>,
)

class NiaAgentClient(private val baseUrl: String) {
    fun interpret(
        label: String,
        confidence: Float,
        margin: Float,
        sessionId: String,
        avatarVocabulary: Set<String>,
    ): AgentReply? {
        if (baseUrl.isBlank()) return null
        val token = JSONObject()
            .put("label", label)
            .put("confidence", confidence)
            .put("margin", margin)
        val body = baseRequest(sessionId, avatarVocabulary)
            .put("tokens", JSONArray().put(token))
        return post("/v1/interpret", body.toString(), avatarVocabulary)
    }

    fun signMessage(message: String, sessionId: String, avatarVocabulary: Set<String>): AgentReply? {
        if (baseUrl.isBlank() || message.isBlank()) return null
        val body = baseRequest(sessionId, avatarVocabulary).put("message", message.take(2000))
        return post("/v1/sign-message", body.toString(), avatarVocabulary)
    }

    private fun post(path: String, body: String, avatarVocabulary: Set<String>): AgentReply? {
        val connection = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 8_000
            connection.readTimeout = 20_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode !in 200..299) return null
            val response = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val text = response.optString("message").takeIf { it.isNotBlank() }
            val glossArray = response.optJSONArray("sign_glosses") ?: JSONArray()
            val signGlosses = buildList {
                repeat(glossArray.length()) { index ->
                    glossArray.optString(index).takeIf { it in avatarVocabulary }?.let(::add)
                }
            }
            text?.let { AgentReply(it, signGlosses) }
        } finally {
            connection.disconnect()
        }
    }

    private fun baseRequest(sessionId: String, avatarVocabulary: Set<String>) = JSONObject()
        .put("user_id", "android")
        .put("session_id", sessionId)
        .put("avatar_vocabulary", JSONArray(avatarVocabulary.toList()))
        .put("request_id", UUID.randomUUID().toString())
}
