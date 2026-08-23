package org.projectnia.app.agent

import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class AgentReply(val text: String)

class NiaAgentClient(private val baseUrl: String) {
    fun interpret(label: String, confidence: Float, margin: Float, sessionId: String): AgentReply? {
        if (baseUrl.isBlank()) return null
        val connection = URL(baseUrl.trimEnd('/') + "/v1/interpret").openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 8_000
            connection.readTimeout = 20_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            val safeLabel = label.replace("\\", "\\\\").replace("\"", "\\\"")
            val body = """{"user_id":"android","session_id":"$sessionId","tokens":[{"label":"$safeLabel","confidence":$confidence,"margin":$margin}],"request_id":"${UUID.randomUUID()}"}"""
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode !in 200..299) return null
            val json = connection.inputStream.bufferedReader().use { it.readText() }
            val text = Regex("\"message\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"").find(json)?.groupValues?.get(1)
                ?.replace("\\n", "\n")?.replace("\\\"", "\"")
            text?.let(::AgentReply)
        } finally {
            connection.disconnect()
        }
    }
}

