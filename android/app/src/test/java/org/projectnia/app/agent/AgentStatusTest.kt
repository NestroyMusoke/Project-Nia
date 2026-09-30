package org.projectnia.app.agent

import org.junit.Assert.assertEquals
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
}
