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
    fun checkHealth(): AgentHealth {
        if (baseUrl.isBlank()) return AgentHealth(AgentConnectionState.NOT_CONFIGURED)
        val connection = runCatching {
            URL(baseUrl.trimEnd('/') + "/healthz").openConnection() as HttpURLConnection
        }.getOrElse { return AgentHealth(AgentConnectionState.UNREACHABLE) }
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 5_000
            connection.readTimeout = 8_000
            when (connection.responseCode) {
                401, 403 -> AgentHealth(AgentConnectionState.AUTHENTICATION_REQUIRED)
                !in 200..299 -> AgentHealth(AgentConnectionState.UNREACHABLE)
                else -> {
                    val response = runCatching {
                        JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    }.getOrNull() ?: return AgentHealth(AgentConnectionState.INCOMPATIBLE)
                    if (
                        response.optString("status") != "ok" ||
                        response.optString("service") != EXPECTED_SERVICE ||
                        response.optInt("api_version", -1) != SUPPORTED_API_VERSION
                    ) {
                        AgentHealth(AgentConnectionState.INCOMPATIBLE)
                    } else {
                        AgentHealth(
                            state = AgentConnectionState.ONLINE,
                            mode = response.optString("mode").takeIf { it.isNotBlank() },
                            model = response.optString("model").takeIf { it.isNotBlank() },
                        )
                    }
                }
            }
        } catch (_: Exception) {
            AgentHealth(AgentConnectionState.UNREACHABLE)
        } finally {
            connection.disconnect()
        }
    }

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

    private companion object {
        const val EXPECTED_SERVICE = "project-nia-agent"
        const val SUPPORTED_API_VERSION = 1
    }
}
