package dev.xuexue.pi.agent

import dev.xuexue.pi.ai.AgentMessage
import dev.xuexue.pi.ai.AssistantMessage
import dev.xuexue.pi.ai.AssistantMessageEvent
import dev.xuexue.pi.ai.Context
import dev.xuexue.pi.ai.Message
import dev.xuexue.pi.ai.Model
import dev.xuexue.pi.ai.ModelCost
import dev.xuexue.pi.ai.ModelInput
import dev.xuexue.pi.ai.StopReason
import dev.xuexue.pi.ai.SuccessfulStopReason
import dev.xuexue.pi.ai.TextContent
import dev.xuexue.pi.ai.ToolCall
import dev.xuexue.pi.ai.ToolResultMessage
import dev.xuexue.pi.ai.Usage
import dev.xuexue.pi.ai.UsageCost
import dev.xuexue.pi.ai.UserMessage
import dev.xuexue.pi.ai.UserText
import dev.xuexue.pi.ai.createAssistantMessageEventStream
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AgentLoopTest {
    @Test
    fun emitsPiLifecycleOrderAndReturnsNewMessages() =
        runTest {
            val prompt = user("Hello", timestamp = 1)
            val response = assistant(listOf(TextContent("Hi there!")), timestamp = 2)
            val stream =
                agentLoop(
                    scope = this,
                    prompts = listOf(prompt),
                    context = AgentContext("You are helpful.", mutableListOf(), emptyList()),
                    config = config(),
                    streamFunction = completedStream(response),
                )

            val events = stream.asFlow().toList()
            val messages = stream.result()

            assertEquals(listOf(prompt, response), messages)
            assertEquals(
                listOf(
                    "agent_start",
                    "turn_start",
                    "message_start",
                    "message_end",
                    "message_start",
                    "message_end",
                    "turn_end",
                    "agent_end",
                ),
                events.map { event -> event.typeName() },
            )
        }

    @Test
    fun customMessagesAreConvertedOnlyAtProviderBoundary() =
        runTest {
            val notification = NotificationMessage("status", timestamp = 1)
            val prompt = user("Hello", timestamp = 2)
            var converted = emptyList<Message>()
            val config =
                object : AgentLoopConfig {
                    override val model = model()

                    override suspend fun convertToLlm(messages: List<AgentMessage>): List<Message> {
                        converted = messages.filterIsInstance<Message>()
                        return converted
                    }
                }

            val stream =
                agentLoop(
                    this,
                    listOf(prompt),
                    AgentContext("", mutableListOf(notification), emptyList()),
                    config,
                    streamFunction = completedStream(assistant(listOf(TextContent("ok")), timestamp = 3)),
                )
            stream.asFlow().toList()

            assertEquals(listOf(prompt), converted)
        }

    @Test
    fun transformContextRunsBeforeConvertToLlm() =
        runTest {
            val oldMessages =
                mutableListOf<AgentMessage>(
                    user("old 1", 1),
                    assistant(listOf(TextContent("response 1")), timestamp = 2),
                    user("old 2", 3),
                    assistant(listOf(TextContent("response 2")), timestamp = 4),
                )
            var transformed = emptyList<AgentMessage>()
            var converted = emptyList<Message>()
            val config =
                object : AgentLoopConfig {
                    override val model = model()

                    override suspend fun transformContext(
                        messages: List<AgentMessage>,
                        signal: dev.xuexue.pi.ai.AbortSignal?,
                    ): List<AgentMessage> = messages.takeLast(2).also { transformed = it }

                    override suspend fun convertToLlm(messages: List<AgentMessage>): List<Message> {
                        converted = messages.filterIsInstance<Message>()
                        return converted
                    }
                }

            val stream =
                agentLoop(
                    this,
                    listOf(user("new", 5)),
                    AgentContext("", oldMessages, emptyList()),
                    config,
                    streamFunction = completedStream(assistant(listOf(TextContent("ok")), timestamp = 6)),
                )
            stream.asFlow().toList()

            assertEquals(2, transformed.size)
            assertEquals(transformed, converted)
        }

    @Test
    fun executesToolAndPersistsResultBeforeNextTurn() =
        runTest {
            val executed = mutableListOf<String>()
            val tool =
                AgentTool(
                    name = "echo",
                    label = "Echo",
                    description = "Echo tool",
                    parameters = buildJsonObject { put("type", "object") },
                    execute = { _, params, _, _ ->
                        val value = params.getValue("value").toString().trim('"')
                        executed += value
                        AgentToolResult(
                            content = listOf(TextContent("echoed: $value")),
                            details = buildJsonObject { put("value", value) },
                        )
                    },
                )
            var callIndex = 0
            val streamFunction =
                StreamFunction { _, context, _ ->
                    if (callIndex++ == 0) {
                        assertEquals(1, context.messages.size)
                        completed(
                            assistant(
                                content =
                                    listOf(
                                        ToolCall(
                                            "tool-1",
                                            "echo",
                                            buildJsonObject { put("value", "hello") },
                                        ),
                                    ),
                                stopReason = StopReason.TOOL_USE,
                                timestamp = 2,
                            ),
                        )
                    } else {
                        assertIs<ToolResultMessage>(context.messages.last())
                        completed(assistant(listOf(TextContent("done")), timestamp = 4))
                    }
                }

            val stream =
                agentLoop(
                    this,
                    listOf(user("echo something", 1)),
                    AgentContext("", mutableListOf(), listOf(tool)),
                    config(),
                    streamFunction = streamFunction,
                )
            val events = stream.asFlow().toList()
            val messages = stream.result()

            assertEquals(listOf("hello"), executed)
            assertTrue(events.any { it is AgentEvent.ToolExecutionStart })
            assertTrue(events.any { it is AgentEvent.ToolExecutionEnd && !it.isError })
            assertIs<ToolResultMessage>(messages[2])
            assertEquals(4, messages.size)
        }

    @Test
    fun lengthTruncatedToolCallIsNeverExecuted() =
        runTest {
            var executed = false
            val tool =
                AgentTool(
                    name = "echo",
                    label = "Echo",
                    description = "Echo tool",
                    parameters = buildJsonObject { put("type", "object") },
                    execute = { _, _, _, _ ->
                        executed = true
                        AgentToolResult(listOf(TextContent("bad")), buildJsonObject {})
                    },
                )
            var callIndex = 0
            val streamFunction =
                StreamFunction { _, context, _ ->
                    if (callIndex++ == 0) {
                        completed(
                            assistant(
                                listOf(ToolCall("tool-1", "echo", buildJsonObject { put("value", "partial") })),
                                stopReason = StopReason.LENGTH,
                                timestamp = 2,
                            ),
                        )
                    } else {
                        val result = assertIs<ToolResultMessage>(context.messages.last())
                        assertTrue(result.isError)
                        completed(assistant(listOf(TextContent("recovered")), timestamp = 4))
                    }
                }

            val stream =
                agentLoop(
                    this,
                    listOf(user("run", 1)),
                    AgentContext("", mutableListOf(), listOf(tool)),
                    config(),
                    streamFunction = streamFunction,
                )
            stream.asFlow().toList()

            assertFalse(executed)
        }

    @Test
    fun continueRejectsEmptyOrAssistantEndedContext() =
        runTest {
            val function = completedStream(assistant(emptyList(), timestamp = 2))
            val emptyError =
                runCatching {
                    agentLoopContinue(this, AgentContext("", mutableListOf()), config(), streamFunction = function)
                }.exceptionOrNull()
            val assistantError =
                runCatching {
                    agentLoopContinue(
                        this,
                        AgentContext("", mutableListOf(assistant(emptyList(), timestamp = 1))),
                        config(),
                        streamFunction = function,
                    )
                }.exceptionOrNull()

            assertEquals("Cannot continue: no messages in context", emptyError?.message)
            assertEquals("Cannot continue from message role: assistant", assistantError?.message)
        }

    private data class NotificationMessage(
        val text: String,
        override val timestamp: Long,
    ) : AgentMessage

    private fun config(): AgentLoopConfig =
        object : AgentLoopConfig {
            override val model = model()

            override suspend fun convertToLlm(messages: List<AgentMessage>): List<Message> = testLlm(messages)
        }

    private fun completedStream(message: AssistantMessage) = testCompletedStream(message)

    private fun completed(message: AssistantMessage) =
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

    private fun user(
        text: String,
        timestamp: Long,
    ): UserMessage = UserMessage(UserText(text), timestamp)

    private fun assistant(
        content: List<dev.xuexue.pi.ai.AssistantContent>,
        stopReason: StopReason = StopReason.STOP,
        timestamp: Long,
    ): AssistantMessage =
        AssistantMessage(
            content = content,
            api = "openai-responses",
            provider = "openai",
            model = "mock",
            usage = usage(),
            stopReason = stopReason,
            timestamp = timestamp,
        )

    private fun usage(): Usage =
        Usage(
            input = 0,
            output = 0,
            cacheRead = 0,
            cacheWrite = 0,
            totalTokens = 0,
            cost = UsageCost(0.0, 0.0, 0.0, 0.0, 0.0),
        )

    private fun model(): Model =
        Model(
            id = "mock",
            name = "mock",
            api = "openai-responses",
            provider = "openai",
            baseUrl = "https://example.invalid",
            reasoning = false,
            input = listOf(ModelInput.TEXT),
            cost = ModelCost(0.0, 0.0, 0.0, 0.0),
            contextWindow = 8_192,
            maxTokens = 2_048,
        )

    private fun AgentEvent.typeName(): String =
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
}
