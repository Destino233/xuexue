package dev.xuexue

import dev.xuexue.pi.agent.AgentEvent
import dev.xuexue.pi.agent.AgentToolResult
import dev.xuexue.pi.ai.AssistantMessage
import dev.xuexue.pi.ai.TextContent
import dev.xuexue.pi.ai.ThinkingContent
import dev.xuexue.pi.ai.ToolResultMessage
import dev.xuexue.pi.ai.UserMessage
import dev.xuexue.pi.ai.UserText
import dev.xuexue.platform.AgentMessage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal class WorkbenchAgentProjection(
    private val session: AgentSessionState,
    private val assistantId: String,
) {
    fun apply(event: AgentEvent) {
        when (event) {
            is AgentEvent.MessageStart -> applyMessage(event.message)

            is AgentEvent.MessageUpdate -> applyMessage(event.message)

            is AgentEvent.MessageEnd -> applyMessage(event.message)

            is AgentEvent.ToolExecutionStart -> applyToolStart(event)

            is AgentEvent.ToolExecutionUpdate -> applyToolUpdate(event.toolCallId, event.partialResult)

            is AgentEvent.ToolExecutionEnd -> Unit

            AgentEvent.AgentStart,
            is AgentEvent.AgentEnd,
            AgentEvent.TurnStart,
            is AgentEvent.TurnEnd,
            -> Unit
        }
    }

    private fun applyMessage(message: dev.xuexue.pi.ai.AgentMessage) {
        when (message) {
            is AssistantMessage -> applyAssistant(message)
            is ToolResultMessage -> applyToolResult(message)
            is UserMessage -> applyQueuedUser(message)
        }
    }

    private fun applyAssistant(message: AssistantMessage) {
        val text = message.content.filterIsInstance<TextContent>().joinToString("") { it.text }
        val reasoning = message.content.filterIsInstance<ThinkingContent>().joinToString("") { it.thinking }
        upsert("assistant", assistantId, text, message.errorMessage != null)
        if (reasoning.isNotEmpty()) upsert("reasoning", "reasoning:$assistantId", reasoning)
        session.inputTokens =
            message.usage.input
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
        session.outputTokens =
            message.usage.output
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
        session.costUsd =
            message.usage.cost.total
                .takeIf { it > 0.0 }
    }

    private fun applyQueuedUser(message: UserMessage) {
        val text = (message.content as? UserText)?.value ?: return
        val queuedIndex = session.messages.indexOfFirst { it.role == "steering_queued" && it.content == text }
        if (queuedIndex >= 0) {
            session.messages[queuedIndex] = session.messages[queuedIndex].copy(role = "steering")
        }
    }

    private fun applyToolStart(event: AgentEvent.ToolExecutionStart) {
        val target =
            listOf("path", "file_path", "query", "command")
                .firstNotNullOfOrNull { key -> event.args[key]?.jsonPrimitive?.contentOrNull }
                .orEmpty()
        val content =
            listOf(event.toolName, target, event.args.toString())
                .filter(String::isNotBlank)
                .joinToString("\n")
        upsert("tool_call", event.toolCallId, content)
    }

    private fun applyToolUpdate(
        callId: String,
        result: AgentToolResult,
    ) {
        val detail = result.content.filterIsInstance<TextContent>().joinToString("") { it.text }
        if (detail.isNotEmpty()) upsert("tool_call", callId, detail)
    }

    private fun applyToolResult(message: ToolResultMessage) {
        val output = message.content.filterIsInstance<TextContent>().joinToString("") { it.text }
        upsert("tool", message.toolCallId, output, message.isError)
        val fileDiff = (message.details as? JsonObject)?.get("fileDiff") as? JsonObject
        val relativePath = fileDiff?.get("relativePath")?.jsonPrimitive?.contentOrNull
        val patch = fileDiff?.get("patch")?.jsonPrimitive?.contentOrNull
        if (relativePath != null && patch != null) {
            upsert(
                role = "diff",
                id = "diff:$relativePath:$assistantId",
                content = "--- a/$relativePath\n+++ b/$relativePath\n$patch",
            )
        }
    }

    private fun upsert(
        role: String,
        id: String,
        content: String,
        error: Boolean = false,
    ) {
        val index = session.messages.indexOfFirst { it.streamId == id && it.role == role }
        val message = AgentMessage(role, content, error = error, streamId = id)
        if (index >= 0) session.messages[index] = message else session.messages += message
    }
}
