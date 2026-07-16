package dev.xuexue.pi.agent

import dev.xuexue.pi.ai.AgentMessage
import dev.xuexue.pi.ai.AssistantContent
import dev.xuexue.pi.ai.AssistantMessage
import dev.xuexue.pi.ai.AssistantMessageEvent
import dev.xuexue.pi.ai.Message
import dev.xuexue.pi.ai.Model
import dev.xuexue.pi.ai.ModelCost
import dev.xuexue.pi.ai.ModelInput
import dev.xuexue.pi.ai.StopReason
import dev.xuexue.pi.ai.SuccessfulStopReason
import dev.xuexue.pi.ai.ToolCall
import dev.xuexue.pi.ai.ToolResultMessage
import dev.xuexue.pi.ai.Usage
import dev.xuexue.pi.ai.UsageCost
import dev.xuexue.pi.ai.UserMessage
import dev.xuexue.pi.ai.UserText
import dev.xuexue.pi.ai.createAssistantMessageEventStream
import kotlinx.serialization.json.JsonObject

internal fun testConfig(
    toolExecution: ToolExecutionMode? = null,
): AgentLoopConfig =
    object : AgentLoopConfig {
        override val model = testModel()
        override val toolExecution = toolExecution

        override suspend fun convertToLlm(messages: List<AgentMessage>): List<Message> = testLlm(messages)
    }

internal fun testLlm(messages: List<AgentMessage>): List<Message> = messages.filterIsInstance<Message>()

internal fun testCompletedStream(message: AssistantMessage) = StreamFunction { _, _, _ -> testCompleted(message) }

internal fun testCompleted(message: AssistantMessage) =
    createAssistantMessageEventStream().also { stream ->
        val reason =
            when (message.stopReason) {
                StopReason.STOP -> SuccessfulStopReason.STOP
                StopReason.LENGTH -> SuccessfulStopReason.LENGTH
                StopReason.TOOL_USE -> SuccessfulStopReason.TOOL_USE
                else -> error("Expected successful message")
            }
        stream.push(AssistantMessageEvent.Done(reason, message))
    }

internal fun testUser(
    text: String,
    timestamp: Long = 1,
): UserMessage = UserMessage(UserText(text), timestamp)

internal fun testAssistant(
    content: List<AssistantContent>,
    stopReason: StopReason = StopReason.STOP,
    timestamp: Long = 2,
): AssistantMessage =
    AssistantMessage(
        content = content,
        api = "openai-responses",
        provider = "openai",
        model = "mock",
        usage = testUsage(),
        stopReason = stopReason,
        timestamp = timestamp,
    )

internal fun testToolCall(
    id: String,
    name: String,
    arguments: JsonObject,
): ToolCall = ToolCall(id, name, arguments)

internal fun testUsage(): Usage =
    Usage(
        input = 0,
        output = 0,
        cacheRead = 0,
        cacheWrite = 0,
        totalTokens = 0,
        cost = UsageCost(0.0, 0.0, 0.0, 0.0, 0.0),
    )

internal fun testModel(
    id: String = "mock",
): Model =
    Model(
        id = id,
        name = id,
        api = "openai-responses",
        provider = "openai",
        baseUrl = "https://example.invalid",
        reasoning = false,
        input = listOf(ModelInput.TEXT),
        cost = ModelCost(0.0, 0.0, 0.0, 0.0),
        contextWindow = 8_192,
        maxTokens = 2_048,
    )

internal fun AgentEvent.testTypeName(): String =
    when (this) {
        AgentEvent.AgentStart -> "agent_start"
        is AgentEvent.AgentEnd -> "agent_end"
        AgentEvent.TurnStart -> "turn_start"
        is AgentEvent.TurnEnd -> "turn_end"
        is AgentEvent.MessageStart -> "message_start"
        is AgentEvent.MessageUpdate -> "message_update"
        is AgentEvent.MessageEnd -> "message_end"
        is AgentEvent.ToolExecutionStart -> "tool_execution_start"
        is AgentEvent.ToolExecutionUpdate -> "tool_execution_update"
        is AgentEvent.ToolExecutionEnd -> "tool_execution_end"
    }

internal fun AgentMessage.testRole(): String =
    when (this) {
        is UserMessage -> "user"
        is AssistantMessage -> "assistant"
        is ToolResultMessage -> "toolResult"
        else -> "custom"
    }
