package dev.xuexue.pi.agent

import dev.xuexue.pi.ai.TextContent
import dev.xuexue.pi.ai.ToolCall
import dev.xuexue.pi.ai.ToolResultMessage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

internal data class ExecutedToolCallBatch(
    val messages: List<ToolResultMessage>,
    val terminate: Boolean,
)

internal data class PreparedToolCall(
    val toolCall: ToolCall,
    val tool: AgentTool,
    val args: JsonObject,
)

internal data class ExecutedToolCallOutcome(
    val result: AgentToolResult,
    val isError: Boolean,
)

internal data class FinalizedToolCallOutcome(
    val toolCall: ToolCall,
    val result: AgentToolResult,
    val isError: Boolean,
)

internal sealed interface ToolCallPreparation {
    data class Prepared(
        val value: PreparedToolCall,
    ) : ToolCallPreparation

    data class Immediate(
        val result: AgentToolResult,
        val isError: Boolean,
    ) : ToolCallPreparation
}

internal fun createErrorToolResult(message: String): AgentToolResult =
    AgentToolResult(
        content = listOf(TextContent(message)),
        details = buildJsonObject {},
    )

internal fun immediateError(message: String): ToolCallPreparation.Immediate =
    ToolCallPreparation.Immediate(
        createErrorToolResult(message),
        isError = true,
    )

internal fun shouldTerminate(finalizedCalls: List<FinalizedToolCallOutcome>): Boolean =
    finalizedCalls.isNotEmpty() &&
        finalizedCalls.all { it.result.terminate == true }
