package dev.xuexue.pi.agent

import dev.xuexue.pi.ai.AgentMessage
import dev.xuexue.pi.ai.AssistantMessage
import dev.xuexue.pi.ai.Message
import dev.xuexue.pi.ai.StopReason
import dev.xuexue.pi.ai.TextContent
import dev.xuexue.pi.ai.UserMessage
import dev.xuexue.pi.ai.UserText
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AgentLoopControlFlowTest {
    @Test
    fun usesPrepareNextTurnSnapshotBeforeContinuing() =
        runTest {
            val tool = echoTool()
            var prepared = false
            var secondTurnSystemPrompt = ""
            var llmCalls = 0
            val config =
                object : AgentLoopConfig {
                    override val model = testModel()

                    override suspend fun convertToLlm(messages: List<AgentMessage>): List<Message> = testLlm(messages)

                    override suspend fun prepareNextTurn(context: ShouldStopAfterTurnContext): AgentLoopTurnUpdate? {
                        if (prepared) return null
                        prepared = true
                        return AgentLoopTurnUpdate(
                            context =
                                AgentContext(
                                    systemPrompt = "second prompt",
                                    messages = context.context.messages.toMutableList(),
                                    tools = context.context.tools,
                                ),
                        )
                    }
                }
            val stream =
                agentLoop(
                    this,
                    listOf(testUser("echo")),
                    AgentContext("first prompt", mutableListOf(), listOf(tool)),
                    config,
                    streamFunction =
                        StreamFunction { _, context, _ ->
                            llmCalls++
                            if (llmCalls == 2) secondTurnSystemPrompt = context.systemPrompt.orEmpty()
                            if (llmCalls == 1) {
                                testCompleted(
                                    testAssistant(
                                        listOf(testToolCall("tool-1", "echo", valueArguments("hello"))),
                                        stopReason = StopReason.TOOL_USE,
                                    ),
                                )
                            } else {
                                testCompleted(testAssistant(listOf(TextContent("done"))))
                            }
                        },
                )
            stream.asFlow().toList()

            assertEquals(2, llmCalls)
            assertEquals("second prompt", secondTurnSystemPrompt)
        }

    @Test
    fun shouldStopAfterTurnSeesPreparedContext() =
        runTest {
            var observedPrompt = ""
            val config =
                object : AgentLoopConfig {
                    override val model = testModel()

                    override suspend fun convertToLlm(messages: List<AgentMessage>): List<Message> = testLlm(messages)

                    override suspend fun prepareNextTurn(context: ShouldStopAfterTurnContext): AgentLoopTurnUpdate =
                        AgentLoopTurnUpdate(
                            context = context.context.copy(systemPrompt = "prepared"),
                        )

                    override suspend fun shouldStopAfterTurn(context: ShouldStopAfterTurnContext): Boolean {
                        observedPrompt = context.context.systemPrompt
                        return true
                    }
                }
            val stream =
                agentLoop(
                    this,
                    listOf(testUser("stop")),
                    AgentContext("initial", mutableListOf()),
                    config,
                    streamFunction = testCompletedStream(testAssistant(listOf(TextContent("done")))),
                )
            stream.asFlow().toList()

            assertEquals("prepared", observedPrompt)
        }

    @Test
    fun stopsAfterCurrentTurnWithoutPollingFollowUps() =
        runTest {
            val executed = mutableListOf<String>()
            val tool = echoTool { value -> executed += value }
            var steeringPolls = 0
            var followUpPolls = 0
            var callbackToolResultIds = emptyList<String>()
            var callbackRoles = emptyList<String>()
            var llmCalls = 0
            val config =
                object : AgentLoopConfig {
                    override val model = testModel()

                    override suspend fun convertToLlm(messages: List<AgentMessage>): List<Message> = testLlm(messages)

                    override suspend fun getSteeringMessages(): List<AgentMessage> {
                        steeringPolls++
                        return emptyList()
                    }

                    override suspend fun getFollowUpMessages(): List<AgentMessage> {
                        followUpPolls++
                        return listOf(testUser("queued"))
                    }

                    override suspend fun shouldStopAfterTurn(context: ShouldStopAfterTurnContext): Boolean {
                        callbackToolResultIds = context.toolResults.map { it.toolCallId }
                        callbackRoles = context.context.messages.map { it.testRole() }
                        return true
                    }
                }
            val stream =
                agentLoop(
                    this,
                    listOf(testUser("echo")),
                    AgentContext("", mutableListOf(), listOf(tool)),
                    config,
                    streamFunction =
                        StreamFunction { _, _, _ ->
                            llmCalls++
                            testCompleted(
                                testAssistant(
                                    listOf(testToolCall("tool-1", "echo", valueArguments("hello"))),
                                    stopReason = StopReason.TOOL_USE,
                                ),
                            )
                        },
                )
            val events = stream.asFlow().toList()

            assertEquals(1, llmCalls)
            assertEquals(listOf("hello"), executed)
            assertEquals(1, steeringPolls)
            assertEquals(0, followUpPolls)
            assertEquals(listOf("tool-1"), callbackToolResultIds)
            assertEquals(listOf("user", "assistant", "toolResult"), callbackRoles)
            assertEquals(listOf("user", "assistant", "toolResult"), stream.result().map { it.testRole() })
            assertEquals(
                listOf(
                    "agent_start",
                    "turn_start",
                    "message_start",
                    "message_end",
                    "message_start",
                    "message_end",
                    "tool_execution_start",
                    "tool_execution_end",
                    "message_start",
                    "message_end",
                    "turn_end",
                    "agent_end",
                ),
                events.map { it.testTypeName() },
            )
        }

    @Test
    fun continueUsesExistingContextWithoutReEmittingUserMessage() =
        runTest {
            val userMessage = testUser("Hello")
            val messages = mutableListOf<AgentMessage>(userMessage)
            val context = AgentContext("You are helpful.", messages, emptyList())
            val stream =
                agentLoopContinue(
                    this,
                    context,
                    testConfig(),
                    streamFunction = testCompletedStream(testAssistant(listOf(TextContent("Response")))),
                )
            val events = stream.asFlow().toList()
            val result = stream.result()

            assertEquals(1, result.size)
            assertIs<AssistantMessage>(result.single())
            assertEquals(1, events.filterIsInstance<AgentEvent.MessageEnd>().size)
            assertIs<AssistantMessage>(events.filterIsInstance<AgentEvent.MessageEnd>().single().message)
            assertSame(messages, context.messages)
            assertEquals(listOf("user", "assistant"), context.messages.map { it.testRole() })
        }

    @Test
    fun continueAllowsCustomLastMessageWhenConverterHandlesIt() =
        runTest {
            val custom = CustomMessage("Hook content", timestamp = 1)
            var providerMessage: Message? = null
            val config =
                object : AgentLoopConfig {
                    override val model = testModel()

                    override suspend fun convertToLlm(messages: List<AgentMessage>): List<Message> =
                        messages.mapNotNull { message ->
                            when (message) {
                                is CustomMessage -> UserMessage(UserText(message.text), message.timestamp)
                                is Message -> message
                                else -> null
                            }
                        }
                }
            val stream =
                agentLoopContinue(
                    this,
                    AgentContext("", mutableListOf(custom)),
                    config,
                    streamFunction =
                        StreamFunction { _, context, _ ->
                            providerMessage = context.messages.single()
                            testCompleted(testAssistant(listOf(TextContent("Response"))))
                        },
                )
            stream.asFlow().toList()

            assertIs<UserMessage>(providerMessage)
            assertEquals("Hook content", (providerMessage as UserMessage).content.let { it as UserText }.value)
            assertEquals(1, stream.result().size)
            assertIs<AssistantMessage>(stream.result().single())
        }

    private data class CustomMessage(
        val text: String,
        override val timestamp: Long,
    ) : AgentMessage

    private fun echoTool(onExecute: (String) -> Unit = {}): AgentTool =
        AgentTool(
            name = "echo",
            label = "Echo",
            description = "Echo tool",
            parameters = buildJsonObject { put("type", "object") },
            execute = { _, params, _, _ ->
                val value = params.getValue("value").jsonPrimitive.content
                onExecute(value)
                AgentToolResult(listOf(TextContent("echoed: $value")), buildJsonObject { put("value", value) })
            },
        )

    private fun valueArguments(value: String) = buildJsonObject { put("value", value) }
}
