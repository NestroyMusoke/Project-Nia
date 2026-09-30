package org.projectnia.app.agent

import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

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
    ): AgentCallResult {
        if (baseUrl.isBlank()) return AgentCallResult.failed(AgentCallFailure.NOT_CONFIGURED)
        val requestId = UUID.randomUUID().toString()
        val token = JSONObject()
            .put("label", label)
            .put("confidence", confidence)
            .put("margin", margin)
        val body = baseRequest(sessionId, avatarVocabulary, requestId)
            .put("tokens", JSONArray().put(token))
        return post("/v1/interpret", body.toString(), requestId, avatarVocabulary)
    }

    fun signMessage(message: String, sessionId: String, avatarVocabulary: Set<String>): AgentCallResult {
        if (baseUrl.isBlank()) return AgentCallResult.failed(AgentCallFailure.NOT_CONFIGURED)
        if (message.isBlank()) return AgentCallResult.failed(AgentCallFailure.INVALID_RESPONSE)
        val requestId = UUID.randomUUID().toString()
        val body = baseRequest(sessionId, avatarVocabulary, requestId).put("message", message.take(2000))
        return post("/v1/sign-message", body.toString(), requestId, avatarVocabulary)
    }

    private fun post(
        path: String,
        body: String,
        requestId: String,
        avatarVocabulary: Set<String>,
    ): AgentCallResult {
        val connection = runCatching {
            URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        }.getOrElse { return AgentCallResult.failed(AgentCallFailure.UNREACHABLE) }
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 8_000
            connection.readTimeout = 20_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            when (connection.responseCode) {
                401, 403 -> return AgentCallResult.failed(AgentCallFailure.AUTHENTICATION_REQUIRED)
                !in 200..299 -> return AgentCallResult.failed(AgentCallFailure.SERVICE_ERROR)
            }
            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            val response = runCatching { JSONObject(responseText) }
                .getOrElse { return AgentCallResult.failed(AgentCallFailure.INVALID_RESPONSE) }
            val text = response.optString("message")
            val glossArray = response.optJSONArray("sign_glosses") ?: JSONArray()
            val signGlosses = buildList {
                repeat(glossArray.length()) { index ->
                    add(glossArray.optString(index))
                }
            }
            AgentReplyPolicy.validate(
                expectedRequestId = requestId,
                responseRequestId = response.optString("request_id"),
                message = text,
                signGlosses = signGlosses,
                avatarVocabulary = avatarVocabulary,
            )
        } catch (_: SocketTimeoutException) {
            AgentCallResult.failed(AgentCallFailure.TIMEOUT)
        } catch (_: Exception) {
            AgentCallResult.failed(AgentCallFailure.UNREACHABLE)
        } finally {
            connection.disconnect()
        }
    }

    private fun baseRequest(sessionId: String, avatarVocabulary: Set<String>, requestId: String) = JSONObject()
        .put("user_id", "android")
        .put("session_id", sessionId)
        .put("avatar_vocabulary", JSONArray(avatarVocabulary.toList()))
        .put("request_id", requestId)

    private companion object {
        const val EXPECTED_SERVICE = "project-nia-agent"
        const val SUPPORTED_API_VERSION = 1
    }
}
