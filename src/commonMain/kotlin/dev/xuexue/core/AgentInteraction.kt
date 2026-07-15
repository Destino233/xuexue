package dev.xuexue.core

enum class AgentOperationKind {
    READ_FILE,
    WRITE_FILE,
    RUN_SHELL,
    OUTSIDE_WORKSPACE,
}

enum class AgentApprovalDecision {
    ALLOW_ONCE,
    ALLOW_SESSION,
    DENY,
}

enum class AgentSendAvailability {
    READY,
    OFFLINE,
    MODEL_REQUIRED,
    API_KEY_REQUIRED,
    AWAITING_APPROVAL,
}

fun agentSendAvailability(
    offline: Boolean,
    model: String,
    apiKey: String,
    awaitingApproval: Boolean,
): AgentSendAvailability =
    when {
        offline -> AgentSendAvailability.OFFLINE
        awaitingApproval -> AgentSendAvailability.AWAITING_APPROVAL
        model.isBlank() -> AgentSendAvailability.MODEL_REQUIRED
        apiKey.isBlank() -> AgentSendAvailability.API_KEY_REQUIRED
        else -> AgentSendAvailability.READY
    }

data class AgentApprovalRequest(
    val id: String,
    val operation: AgentOperationKind,
    val target: String,
    val detail: String,
)

data class AgentApprovalRecord(
    val request: AgentApprovalRequest,
    val decision: AgentApprovalDecision,
)

fun AgentOperationKind.requiresExplicitApproval(
    workspaceTrusted: Boolean,
    readOnlyMode: Boolean,
    allowWrite: Boolean,
    allowShell: Boolean,
): Boolean =
    when (this) {
        AgentOperationKind.READ_FILE -> !workspaceTrusted
        AgentOperationKind.WRITE_FILE -> readOnlyMode || !workspaceTrusted || !allowWrite
        AgentOperationKind.RUN_SHELL -> !workspaceTrusted || !allowShell
        AgentOperationKind.OUTSIDE_WORKSPACE -> true
    }
