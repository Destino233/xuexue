package dev.xuexue.pi.coding

import dev.xuexue.pi.agent.AgentToolResult
import dev.xuexue.pi.ai.AgentMessage
import dev.xuexue.pi.ai.AssistantMessageEvent
import dev.xuexue.pi.ai.ModelThinkingLevel
import dev.xuexue.pi.ai.ToolResultMessage
import dev.xuexue.pi.coding.compaction.CompactionResult
import dev.xuexue.pi.coding.session.SessionEntry
import kotlinx.serialization.json.JsonObject

sealed interface AgentSessionEvent {
    data object AgentStart : AgentSessionEvent

    data class AgentEnd(
        val messages: List<AgentMessage>,
        val willRetry: Boolean,
    ) : AgentSessionEvent

    data object AgentSettled : AgentSessionEvent

    data object TurnStart : AgentSessionEvent

    data class TurnEnd(
        val message: AgentMessage,
        val toolResults: List<ToolResultMessage>,
    ) : AgentSessionEvent

    data class MessageStart(
        val message: AgentMessage,
    ) : AgentSessionEvent

    data class MessageUpdate(
        val message: AgentMessage,
        val assistantMessageEvent: AssistantMessageEvent,
    ) : AgentSessionEvent

    data class MessageEnd(
        val message: AgentMessage,
    ) : AgentSessionEvent

    data class ToolExecutionStart(
        val toolCallId: String,
        val toolName: String,
        val args: JsonObject,
    ) : AgentSessionEvent

    data class ToolExecutionUpdate(
        val toolCallId: String,
        val toolName: String,
        val args: JsonObject,
        val partialResult: AgentToolResult,
    ) : AgentSessionEvent

    data class ToolExecutionEnd(
        val toolCallId: String,
        val toolName: String,
        val result: AgentToolResult,
        val isError: Boolean,
    ) : AgentSessionEvent

    data class QueueUpdate(
        val steering: List<String>,
        val followUp: List<String>,
    ) : AgentSessionEvent

    data class EntryAppended(
        val entry: SessionEntry,
    ) : AgentSessionEvent

    data class SessionInfoChanged(
        val name: String?,
    ) : AgentSessionEvent

    data class ThinkingLevelChanged(
        val level: ModelThinkingLevel,
    ) : AgentSessionEvent

    data class AutoRetryStart(
        val attempt: Int,
        val maxAttempts: Int,
        val delayMs: Long,
        val errorMessage: String,
    ) : AgentSessionEvent

    data class AutoRetryEnd(
        val success: Boolean,
        val attempt: Int,
        val finalError: String? = null,
    ) : AgentSessionEvent

    data class CompactionStart(
        val reason: CompactionReason,
    ) : AgentSessionEvent

    data class CompactionEnd(
        val reason: CompactionReason,
        val result: CompactionResult?,
        val aborted: Boolean,
        val willRetry: Boolean,
        val errorMessage: String? = null,
    ) : AgentSessionEvent
}

enum class CompactionReason {
    MANUAL,
    THRESHOLD,
    OVERFLOW,
}

fun interface AgentSessionEventListener {
    fun onEvent(event: AgentSessionEvent)
}

fun interface AgentSessionSubscription {
    fun dispose()
}
