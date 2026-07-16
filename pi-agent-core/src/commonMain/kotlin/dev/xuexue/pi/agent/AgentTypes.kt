package dev.xuexue.pi.agent

import dev.xuexue.pi.ai.AbortSignal
import dev.xuexue.pi.ai.AgentMessage
import dev.xuexue.pi.ai.AssistantMessage
import dev.xuexue.pi.ai.AssistantMessageEvent
import dev.xuexue.pi.ai.AssistantMessageEventStream
import dev.xuexue.pi.ai.Context
import dev.xuexue.pi.ai.ImageContent
import dev.xuexue.pi.ai.Message
import dev.xuexue.pi.ai.Model
import dev.xuexue.pi.ai.ModelThinkingLevel
import dev.xuexue.pi.ai.SimpleStreamOptions
import dev.xuexue.pi.ai.TextContent
import dev.xuexue.pi.ai.ToolCall
import dev.xuexue.pi.ai.ToolResultContent
import dev.xuexue.pi.ai.ToolResultMessage
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

typealias ThinkingLevel = ModelThinkingLevel

fun interface StreamFunction {
    suspend fun stream(
        model: Model,
        context: Context,
        options: SimpleStreamOptions,
    ): AssistantMessageEventStream
}

enum class ToolExecutionMode {
    SEQUENTIAL,
    PARALLEL,
}

enum class QueueMode {
    ALL,
    ONE_AT_A_TIME,
}

data class BeforeToolCallResult(
    val block: Boolean? = null,
    val reason: String? = null,
)

data class AfterToolCallResult(
    val content: List<ToolResultContent>? = null,
    val details: JsonElement? = null,
    val isError: Boolean? = null,
    val terminate: Boolean? = null,
)

data class AgentToolResult(
    val content: List<ToolResultContent>,
    val details: JsonElement,
    val addedToolNames: List<String>? = null,
    val terminate: Boolean? = null,
)

fun interface AgentToolUpdateCallback {
    fun update(partialResult: AgentToolResult)
}

class AgentTool(
    val name: String,
    val label: String,
    val description: String,
    val parameters: JsonObject,
    val prepareArguments: ((JsonObject) -> JsonObject)? = null,
    val validateArguments: (JsonObject) -> JsonObject = { it },
    val execute: suspend (
        toolCallId: String,
        params: JsonObject,
        signal: AbortSignal?,
        onUpdate: AgentToolUpdateCallback?,
    ) -> AgentToolResult,
    val executionMode: ToolExecutionMode? = null,
    val promptSnippet: String? = null,
    val promptGuidelines: List<String> = emptyList(),
)

data class AgentContext(
    val systemPrompt: String,
    val messages: MutableList<AgentMessage>,
    val tools: List<AgentTool>? = null,
)

data class BeforeToolCallContext(
    val assistantMessage: AssistantMessage,
    val toolCall: ToolCall,
    val args: MutableMap<String, JsonElement>,
    val context: AgentContext,
)

data class AfterToolCallContext(
    val assistantMessage: AssistantMessage,
    val toolCall: ToolCall,
    val args: JsonObject,
    val result: AgentToolResult,
    val isError: Boolean,
    val context: AgentContext,
)

data class ShouldStopAfterTurnContext(
    val message: AssistantMessage,
    val toolResults: List<ToolResultMessage>,
    val context: AgentContext,
    val newMessages: List<AgentMessage>,
)

data class AgentLoopTurnUpdate(
    val context: AgentContext? = null,
    val model: Model? = null,
    val thinkingLevel: ThinkingLevel? = null,
)

interface AgentLoopConfig {
    val model: Model
    val streamOptions: SimpleStreamOptions
        get() = SimpleStreamOptions()
    val toolExecution: ToolExecutionMode?
        get() = null

    suspend fun convertToLlm(messages: List<AgentMessage>): List<Message>

    suspend fun transformContext(
        messages: List<AgentMessage>,
        signal: AbortSignal?,
    ): List<AgentMessage> = messages

    suspend fun getApiKey(provider: String): String? = null

    suspend fun shouldStopAfterTurn(context: ShouldStopAfterTurnContext): Boolean = false

    suspend fun prepareNextTurn(context: ShouldStopAfterTurnContext): AgentLoopTurnUpdate? = null

    suspend fun getSteeringMessages(): List<AgentMessage> = emptyList()

    suspend fun getFollowUpMessages(): List<AgentMessage> = emptyList()

    suspend fun beforeToolCall(
        context: BeforeToolCallContext,
        signal: AbortSignal?,
    ): BeforeToolCallResult? = null

    suspend fun afterToolCall(
        context: AfterToolCallContext,
        signal: AbortSignal?,
    ): AfterToolCallResult? = null
}

sealed interface AgentEvent {
    data object AgentStart : AgentEvent

    data class AgentEnd(
        val messages: List<AgentMessage>,
    ) : AgentEvent

    data object TurnStart : AgentEvent

    data class TurnEnd(
        val message: AgentMessage,
        val toolResults: List<ToolResultMessage>,
    ) : AgentEvent

    data class MessageStart(
        val message: AgentMessage,
    ) : AgentEvent

    data class MessageUpdate(
        val message: AgentMessage,
        val assistantMessageEvent: AssistantMessageEvent,
    ) : AgentEvent

    data class MessageEnd(
        val message: AgentMessage,
    ) : AgentEvent

    data class ToolExecutionStart(
        val toolCallId: String,
        val toolName: String,
        val args: JsonObject,
    ) : AgentEvent

    data class ToolExecutionUpdate(
        val toolCallId: String,
        val toolName: String,
        val args: JsonObject,
        val partialResult: AgentToolResult,
    ) : AgentEvent

    data class ToolExecutionEnd(
        val toolCallId: String,
        val toolName: String,
        val result: AgentToolResult,
        val isError: Boolean,
    ) : AgentEvent
}

fun interface AgentEventSink {
    suspend fun emit(event: AgentEvent)
}
