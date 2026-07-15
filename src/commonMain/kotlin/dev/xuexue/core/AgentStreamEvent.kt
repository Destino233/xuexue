package dev.xuexue.core

sealed interface AgentStreamEvent {
    data class TextDelta(
        val text: String,
    ) : AgentStreamEvent

    data class ReasoningDelta(
        val text: String,
    ) : AgentStreamEvent

    data class ToolStarted(
        val callId: String,
        val name: String,
        val target: String = "",
        val detail: String = "",
    ) : AgentStreamEvent

    data class ToolProgress(
        val callId: String,
        val detail: String,
    ) : AgentStreamEvent

    data class ToolFinished(
        val callId: String,
        val output: String,
        val failed: Boolean = false,
    ) : AgentStreamEvent

    data class FileDiff(
        val relativePath: String,
        val patch: String,
    ) : AgentStreamEvent

    data class Usage(
        val inputTokens: Int,
        val outputTokens: Int,
        val costUsd: Double? = null,
    ) : AgentStreamEvent
}
