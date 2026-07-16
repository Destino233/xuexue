package dev.xuexue.pi.coding

import dev.xuexue.pi.agent.Agent
import dev.xuexue.pi.agent.AgentOptions
import dev.xuexue.pi.agent.AgentTool
import dev.xuexue.pi.agent.StreamFunction
import dev.xuexue.pi.ai.AssistantContent
import dev.xuexue.pi.ai.AssistantMessage
import dev.xuexue.pi.ai.AssistantMessageEvent
import dev.xuexue.pi.ai.Context
import dev.xuexue.pi.ai.FailureStopReason
import dev.xuexue.pi.ai.Model
import dev.xuexue.pi.ai.ModelCost
import dev.xuexue.pi.ai.ModelInput
import dev.xuexue.pi.ai.StopReason
import dev.xuexue.pi.ai.SuccessfulStopReason
import dev.xuexue.pi.ai.TextContent
import dev.xuexue.pi.ai.Usage
import dev.xuexue.pi.ai.UsageCost
import dev.xuexue.pi.ai.UserBlocks
import dev.xuexue.pi.ai.UserMessage
import dev.xuexue.pi.ai.UserText
import dev.xuexue.pi.ai.createAssistantMessageEventStream
import dev.xuexue.pi.coding.session.SessionManager
import dev.xuexue.pi.coding.session.convertToLlm

internal typealias SessionResponse = suspend (Context) -> AssistantMessage

internal class AgentSessionHarness(
    responses: List<SessionResponse> = listOf({ testAssistant("ok") }),
    tools: List<AgentTool> = emptyList(),
    sessionManager: SessionManager = SessionManager.inMemory("/tmp"),
    settingsManager: AgentSessionSettings = InMemoryAgentSessionSettings(),
    streamFunction: StreamFunction? = null,
) {
    private val pendingResponses = responses.toMutableList()
    val agent =
        Agent(
            AgentOptions(
                initialState =
                    dev.xuexue.pi.agent.AgentInitialState(
                        systemPrompt = "Test",
                        model = testModel(),
                        tools = tools,
                    ),
                convertToLlm = { messages -> convertToLlm(messages) },
                streamFunction =
                    streamFunction
                        ?: StreamFunction { _, context, _ ->
                            check(pendingResponses.isNotEmpty()) { "No test response remains" }
                            completedStream(pendingResponses.removeAt(0)(context))
                        },
            ),
        )
    val session = AgentSession(AgentSessionConfig(agent, sessionManager, settingsManager))
    val manager = sessionManager

    val pendingResponseCount: Int
        get() = pendingResponses.size
}

internal fun testModel(
    reasoning: Boolean = true,
    contextWindow: Long = 8_192,
): Model =
    Model(
        id = "mock",
        name = "mock",
        api = "openai-responses",
        provider = "openai",
        baseUrl = "https://example.invalid",
        reasoning = reasoning,
        input = listOf(ModelInput.TEXT),
        cost = ModelCost(0.0, 0.0, 0.0, 0.0),
        contextWindow = contextWindow,
        maxTokens = 2_048,
    )

internal fun testAssistant(
    text: String,
    stopReason: StopReason = StopReason.STOP,
    timestamp: Long = 2,
    usage: Usage = testUsage(),
): AssistantMessage = testAssistant(listOf(TextContent(text)), stopReason, timestamp, usage)

internal fun testAssistant(
    content: List<AssistantContent>,
    stopReason: StopReason = StopReason.STOP,
    timestamp: Long = 2,
    usage: Usage = testUsage(),
): AssistantMessage =
    AssistantMessage(
        content = content,
        api = "openai-responses",
        provider = "openai",
        model = "mock",
        usage = usage,
        stopReason = stopReason,
        timestamp = timestamp,
    )

internal fun testUsage(
    input: Long = 1,
    output: Long = 1,
    cacheRead: Long = 0,
    cacheWrite: Long = 0,
    cost: Double = 0.0,
): Usage =
    Usage(
        input = input,
        output = output,
        cacheRead = cacheRead,
        cacheWrite = cacheWrite,
        totalTokens = input + output + cacheRead + cacheWrite,
        cost = UsageCost(0.0, cost, 0.0, 0.0, cost),
    )

internal fun completedStream(message: AssistantMessage) =
    createAssistantMessageEventStream().also { stream ->
        when (message.stopReason) {
            StopReason.STOP -> stream.push(AssistantMessageEvent.Done(SuccessfulStopReason.STOP, message))
            StopReason.LENGTH -> stream.push(AssistantMessageEvent.Done(SuccessfulStopReason.LENGTH, message))
            StopReason.TOOL_USE -> stream.push(AssistantMessageEvent.Done(SuccessfulStopReason.TOOL_USE, message))
            StopReason.ERROR -> stream.push(AssistantMessageEvent.Error(FailureStopReason.ERROR, message))
            StopReason.ABORTED -> stream.push(AssistantMessageEvent.Error(FailureStopReason.ABORTED, message))
        }
    }

internal fun messageText(message: dev.xuexue.pi.ai.AgentMessage): String =
    when (message) {
        is UserMessage -> {
            when (val content = message.content) {
                is UserText -> content.value
                is UserBlocks -> content.value.filterIsInstance<TextContent>().joinToString("") { it.text }
            }
        }

        is AssistantMessage -> {
            message.content.filterIsInstance<TextContent>().joinToString("") { it.text }
        }

        else -> {
            ""
        }
    }
