package dev.xuexue.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentInteractionTest {
    @Test
    fun riskyOperationsRespectWorkspaceAndSessionPolicy() {
        assertFalse(AgentOperationKind.READ_FILE.requiresExplicitApproval(true, false, true, true))
        assertTrue(AgentOperationKind.READ_FILE.requiresExplicitApproval(false, false, true, true))
        assertTrue(AgentOperationKind.WRITE_FILE.requiresExplicitApproval(true, true, true, true))
        assertTrue(AgentOperationKind.WRITE_FILE.requiresExplicitApproval(true, false, false, true))
        assertFalse(AgentOperationKind.WRITE_FILE.requiresExplicitApproval(true, false, true, true))
        assertTrue(AgentOperationKind.RUN_SHELL.requiresExplicitApproval(true, false, true, false))
        assertTrue(AgentOperationKind.OUTSIDE_WORKSPACE.requiresExplicitApproval(true, false, true, true))
    }

    @Test
    fun sendAvailabilityExplainsTheFirstBlockingState() {
        assertTrue(agentSendAvailability(true, "model", "key", false) == AgentSendAvailability.OFFLINE)
        assertTrue(agentSendAvailability(false, "model", "key", true) == AgentSendAvailability.AWAITING_APPROVAL)
        assertTrue(agentSendAvailability(false, "", "key", false) == AgentSendAvailability.MODEL_REQUIRED)
        assertTrue(agentSendAvailability(false, "model", "", false) == AgentSendAvailability.API_KEY_REQUIRED)
        assertTrue(agentSendAvailability(false, "model", "key", false) == AgentSendAvailability.READY)
    }
}
