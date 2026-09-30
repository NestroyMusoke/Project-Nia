package org.projectnia.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentStatusTest {
    @Test
    fun `not configured keeps offline capability explicit`() {
        assertTrue(
            AgentStatusPresenter.label(AgentHealth(AgentConnectionState.NOT_CONFIGURED))
                .contains("offline recognition available")
        )
    }

    @Test
    fun `online status identifies runtime mode and model`() {
        assertEquals(
            "Online agent: connected | vertex-ai | gemini-2.5-flash",
            AgentStatusPresenter.label(
                AgentHealth(
                    AgentConnectionState.ONLINE,
                    mode = "vertex-ai",
                    model = "gemini-2.5-flash",
                )
            ),
        )
    }

    @Test
    fun `authentication failure is not reported as generic offline`() {
        assertEquals(
            "Online agent: Cloud Run authentication required",
            AgentStatusPresenter.label(AgentHealth(AgentConnectionState.AUTHENTICATION_REQUIRED)),
        )
    }

    @Test
    fun `matching request with fully supported glosses is accepted`() {
        val result = AgentReplyPolicy.validate(
            expectedRequestId = "request-1",
            responseRequestId = "request-1",
            message = "Please wait.",
            signGlosses = listOf("please", "wait"),
            avatarVocabulary = setOf("please", "wait"),
        )

        assertEquals(listOf("please", "wait"), result.reply?.signGlosses)
        assertNull(result.failure)
    }

    @Test
    fun `unknown gloss rejects entire response instead of playing fragment`() {
        val result = AgentReplyPolicy.validate(
            expectedRequestId = "request-1",
            responseRequestId = "request-1",
            message = "Please wait.",
            signGlosses = listOf("please", "invented", "wait"),
            avatarVocabulary = setOf("please", "wait"),
        )

        assertNull(result.reply)
        assertEquals(AgentCallFailure.INVALID_RESPONSE, result.failure)
    }

    @Test
    fun `mismatched request id is rejected`() {
        val result = AgentReplyPolicy.validate(
            expectedRequestId = "new-request",
            responseRequestId = "old-request",
            message = "Hello.",
            signGlosses = emptyList(),
            avatarVocabulary = emptySet(),
        )

        assertEquals(AgentCallFailure.INVALID_RESPONSE, result.failure)
    }
}
