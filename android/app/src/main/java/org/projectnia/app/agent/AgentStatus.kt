package org.projectnia.app.agent

data class AgentReply(
    val text: String,
    val signGlosses: List<String>,
)

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

enum class AgentCallFailure {
    NOT_CONFIGURED,
    AUTHENTICATION_REQUIRED,
    TIMEOUT,
    UNREACHABLE,
    SERVICE_ERROR,
    INVALID_RESPONSE,
}

data class AgentCallResult(
    val reply: AgentReply? = null,
    val failure: AgentCallFailure? = null,
) {
    init {
        require((reply == null) != (failure == null))
    }

    companion object {
        fun success(reply: AgentReply) = AgentCallResult(reply = reply)
        fun failed(failure: AgentCallFailure) = AgentCallResult(failure = failure)
    }
}

object AgentCallPresenter {
    fun message(failure: AgentCallFailure): String = when (failure) {
        AgentCallFailure.NOT_CONFIGURED -> "Online agent is not configured; offline result kept"
        AgentCallFailure.AUTHENTICATION_REQUIRED -> "Cloud Run authentication is required"
        AgentCallFailure.TIMEOUT -> "Online agent timed out; offline result kept"
        AgentCallFailure.UNREACHABLE -> "Online agent is unreachable; offline result kept"
        AgentCallFailure.SERVICE_ERROR -> "Online agent returned an error; offline result kept"
        AgentCallFailure.INVALID_RESPONSE ->
            "Unsafe or mismatched agent response rejected; nothing unverified was animated"
    }
}

object AgentReplyPolicy {
    fun validate(
        expectedRequestId: String,
        responseRequestId: String,
        message: String,
        signGlosses: List<String>,
        avatarVocabulary: Set<String>,
    ): AgentCallResult {
        if (
            expectedRequestId.isBlank() ||
            responseRequestId != expectedRequestId ||
            message.isBlank() ||
            signGlosses.size > 40 ||
            signGlosses.any { it !in avatarVocabulary }
        ) {
            return AgentCallResult.failed(AgentCallFailure.INVALID_RESPONSE)
        }
        return AgentCallResult.success(AgentReply(message, signGlosses))
    }
}
