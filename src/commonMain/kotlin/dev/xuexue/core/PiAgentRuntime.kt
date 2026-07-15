package dev.xuexue.core

import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlin.coroutines.coroutineContext

/**
 * Headless agent loop modelled after Pi: a provider turn may request tools,
 * whose results are appended to the context before the next provider turn.
 */
@Serializable
data class PiAgentMessage(
    val role: String,
    val content: String = "",
    val toolCalls: List<PiToolCall> = emptyList(),
    val toolCallId: String? = null,
    val name: String? = null,
    val error: Boolean = false,
)

@Serializable
data class PiToolCall(
    val id: String,
    val name: String,
    val arguments: JsonObject = buildJsonObject {},
)

@Serializable
data class PiToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

data class PiModelResponse(
    val content: String = "",
    val toolCalls: List<PiToolCall> = emptyList(),
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val costUsd: Double? = null,
)

interface PiAgentModel {
    suspend fun complete(
        messages: List<PiAgentMessage>,
        tools: List<PiToolDefinition>,
        onEvent: (AgentStreamEvent) -> Unit,
    ): PiModelResponse
}

interface PiAgentTool {
    val definition: PiToolDefinition

    suspend fun execute(
        callId: String,
        arguments: JsonObject,
        onProgress: (String) -> Unit,
    ): PiToolExecution
}

data class PiToolExecution(
    val output: String,
    val failed: Boolean = false,
    val fileDiff: PiFileDiff? = null,
)

data class PiFileDiff(
    val relativePath: String,
    val patch: String,
)

class PiAgentRuntime(
    private val maxTurns: Int = 32,
) {
    suspend fun run(
        initialMessages: List<PiAgentMessage>,
        model: PiAgentModel,
        tools: List<PiAgentTool>,
        onEvent: (AgentStreamEvent) -> Unit,
        drainSteering: suspend () -> List<PiAgentMessage> = { emptyList() },
    ): List<PiAgentMessage> {
        val history = initialMessages.toMutableList()
        val toolsByName = tools.associateBy { it.definition.name }

        repeat(maxTurns) {
            coroutineContext.ensureActive()
            val response = model.complete(history, tools.map(PiAgentTool::definition), onEvent)
            val assistant =
                PiAgentMessage(
                    role = "assistant",
                    content = response.content,
                    toolCalls = response.toolCalls,
                )
            history += assistant
            if (response.inputTokens != null || response.outputTokens != null) {
                onEvent(
                    AgentStreamEvent.Usage(
                        inputTokens = response.inputTokens ?: 0,
                        outputTokens = response.outputTokens ?: 0,
                        costUsd = response.costUsd,
                    ),
                )
            }
            if (response.toolCalls.isEmpty()) return history

            response.toolCalls.forEach { call ->
                coroutineContext.ensureActive()
                onEvent(AgentStreamEvent.ToolStarted(call.id, call.name, toolTarget(call.arguments)))
                val execution =
                    toolsByName[call.name]
                        ?.let { tool ->
                            runCatching {
                                tool.execute(call.id, call.arguments) { detail ->
                                    onEvent(AgentStreamEvent.ToolProgress(call.id, detail))
                                }
                            }.getOrElse { error ->
                                PiToolExecution(error.message ?: "Tool execution failed.", failed = true)
                            }
                        }
                        ?: PiToolExecution("Unknown tool: ${call.name}", failed = true)
                onEvent(AgentStreamEvent.ToolFinished(call.id, execution.output, execution.failed))
                execution.fileDiff?.let { diff -> onEvent(AgentStreamEvent.FileDiff(diff.relativePath, diff.patch)) }
                history +=
                    PiAgentMessage(
                        role = "tool",
                        content = execution.output,
                        toolCallId = call.id,
                        name = call.name,
                        error = execution.failed,
                    )
            }
            history += drainSteering()
        }
        error("Agent stopped after $maxTurns tool turns. Ask it to continue with a narrower task.")
    }

    private fun toolTarget(arguments: JsonObject): String =
        listOf("path", "file_path", "query", "command")
            .firstNotNullOfOrNull { key -> arguments[key]?.toString()?.trim('"') }
            .orEmpty()
}
