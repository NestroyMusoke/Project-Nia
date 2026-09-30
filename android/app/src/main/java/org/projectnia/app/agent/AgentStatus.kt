package org.projectnia.app.agent

enum class AgentConnectionState {
    NOT_CONFIGURED,
    ONLINE,
    AUTHENTICATION_REQUIRED,
    INCOMPATIBLE,
    UNREACHABLE,
}

data class AgentHealth(
    val state: AgentConnectionState,
    val mode: String? = null,
    val model: String? = null,
)

object AgentStatusPresenter {
    fun label(health: AgentHealth): String = when (health.state) {
        AgentConnectionState.NOT_CONFIGURED -> "Online agent: not configured | offline recognition available"
        AgentConnectionState.ONLINE -> {
            val detail = listOfNotNull(health.mode, health.model).joinToString(" | ")
            if (detail.isBlank()) "Online agent: connected" else "Online agent: connected | $detail"
        }
        AgentConnectionState.AUTHENTICATION_REQUIRED ->
            "Online agent: Cloud Run authentication required"
        AgentConnectionState.INCOMPATIBLE ->
            "Online agent: incompatible Project Nia service"
        AgentConnectionState.UNREACHABLE ->
            "Online agent: unreachable | offline recognition available"
    }
}
