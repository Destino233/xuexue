package dev.xuexue.pi.agent

import dev.xuexue.pi.ai.AbortSignal
import dev.xuexue.pi.ai.AssistantMessage
import dev.xuexue.pi.ai.StopReason
import dev.xuexue.pi.ai.TextContent
import dev.xuexue.pi.ai.UserMessage
import dev.xuexue.pi.ai.UserText
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AgentQueueTest {
    @Test
    fun queuedMessagesRemainOutsideTranscriptUntilDrained() {
        val agent = Agent()
        val steering = testUser("steering")
        val followUp = testUser("follow-up", timestamp = 2)

        agent.steer(steering)
        agent.followUp(followUp)

        assertFalse(steering in agent.currentState.messages)
        assertFalse(followUp in agent.currentState.messages)
        assertTrue(agent.hasQueuedMessages())
        assertEquals(1, agent.currentState.queuedSteeringMessages)
        assertEquals(1, agent.currentState.queuedFollowUpMessages)
    }

    @Test
    fun continueProcessesQueuedFollowUpFromAssistantTail() =
        runTest {
            val agent =
                Agent(
                    AgentOptions(
                        streamFunction = testCompletedStream(testAssistant(listOf(TextContent("Processed")))),
                    ),
                )
            agent.replaceMessages(
                listOf(
                    testUser("Initial"),
                    testAssistant(listOf(TextContent("Initial response"))),
                ),
            )
            agent.followUp(testUser("Queued follow-up", timestamp = 3))

            agent.continueConversation()

            val userTexts =
                agent.currentState.messages.filterIsInstance<UserMessage>().mapNotNull { message ->
                    (message.content as? UserText)?.value
                }
            assertTrue("Queued follow-up" in userTexts)
            assertTrue(agent.currentState.messages.last() is AssistantMessage)
            assertEquals(0, agent.currentState.queuedFollowUpMessages)
        }

    @Test
    fun continuePreservesOneAtATimeSteeringFromAssistantTail() =
        runTest {
            var responseCount = 0
            val agent =
                Agent(
                    AgentOptions(
                        streamFunction =
                            StreamFunction { _, _, _ ->
                                responseCount++
                                testCompleted(testAssistant(listOf(TextContent("Processed $responseCount"))))
                            },
                    ),
                )
            agent.replaceMessages(
                listOf(
                    testUser("Initial"),
                    testAssistant(listOf(TextContent("Initial response"))),
                ),
            )
            agent.steer(testUser("Steering 1", timestamp = 3))
            agent.steer(testUser("Steering 2", timestamp = 4))

            agent.continueConversation()

            assertEquals(
                listOf("user", "assistant", "user", "assistant"),
                agent.currentState.messages
                    .takeLast(4)
                    .map { it.testRole() },
            )
            assertEquals(2, responseCount)
            assertEquals(0, agent.currentState.queuedSteeringMessages)
        }

    @Test
    fun legacyPrepareNextTurnReceivesActiveSignal() =
        runTest {
            val tool =
                AgentTool(
                    name = "noop",
                    label = "Noop",
                    description = "Noop tool",
                    parameters = buildJsonObject { put("type", "object") },
                    execute = { _, _, _, _ ->
                        AgentToolResult(listOf(TextContent("ok")), buildJsonObject {})
                    },
                )
            var requestCount = 0
            var receivedSignal: AbortSignal? = null
            val agent =
                Agent(
                    AgentOptions(
                        initialState = AgentInitialState(tools = listOf(tool)),
                        prepareNextTurn = { signal ->
                            receivedSignal = signal
                            null
                        },
                        streamFunction =
                            StreamFunction { _, _, _ ->
                                requestCount++
                                if (requestCount == 1) {
                                    testCompleted(
                                        testAssistant(
                                            listOf(testToolCall("tool-1", "noop", buildJsonObject {})),
                                            stopReason = StopReason.TOOL_USE,
                                        ),
                                    )
                                } else {
                                    testCompleted(testAssistant(listOf(TextContent("done"))))
                                }
                            },
                    ),
                )

            agent.prompt("start")

            assertEquals(2, requestCount)
            assertNotNull(receivedSignal)
        }

    @Test
    fun forwardsMutableSessionIdToEachRun() =
        runTest {
            val received = mutableListOf<String?>()
            val agent =
                Agent(
                    AgentOptions(
                        sessionId = "session-abc",
                        streamFunction =
                            StreamFunction { _, _, options ->
                                received += options.stream.sessionId
                                testCompleted(testAssistant(listOf(TextContent("ok"))))
                            },
                    ),
                )

            agent.prompt("hello")
            agent.sessionId = "session-def"
            agent.prompt("hello again")

            assertEquals(listOf<String?>("session-abc", "session-def"), received)
        }

    @Test
    fun continueRejectsMissingOrUnqueuedAssistantTail() =
        runTest {
            val agent = Agent()
            assertEquals(
                "No messages to continue from",
                assertFailsWith<IllegalStateException> { agent.continueConversation() }.message,
            )
            agent.replaceMessages(listOf(testAssistant(listOf(TextContent("done")))))
            assertEquals(
                "Cannot continue from message role: assistant",
                assertFailsWith<IllegalStateException> { agent.continueConversation() }.message,
            )
        }
}
