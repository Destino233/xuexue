@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.xuexue.pi.coding

import dev.xuexue.pi.agent.AgentTool
import dev.xuexue.pi.agent.AgentToolResult
import dev.xuexue.pi.agent.QueueMode
import dev.xuexue.pi.ai.StopReason
import dev.xuexue.pi.ai.TextContent
import dev.xuexue.pi.ai.ToolCall
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentSessionQueueTest {
    @Test
    fun removesQueuedTextBeforeItsMessageStartEvent() =
        runTest {
            val releaseTool = CompletableDeferred<Unit>()
            val harness = waitingHarness(releaseTool, listOf({ testAssistant("done") }))
            var pendingAtQueuedMessageStart = -1
            harness.session.subscribe { event ->
                if (
                    event is AgentSessionEvent.MessageStart &&
                    messageText(event.message) == "queued"
                ) {
                    pendingAtQueuedMessageStart = harness.session.pendingMessageCount
                }
            }
            val prompt = launch { harness.session.prompt("start") }
            runCurrent()

            harness.session.steer("queued")
            assertEquals(1, harness.session.pendingMessageCount)
            releaseTool.complete(Unit)
            prompt.join()

            assertEquals(0, pendingAtQueuedMessageStart)
            assertEquals(0, harness.session.pendingMessageCount)
            assertEquals(listOf("start", "queued"), userTexts(harness))
        }

    @Test
    fun followUpsRunAfterTheOriginalTurnInOneAtATimeMode() =
        runTest {
            val releaseTool = CompletableDeferred<Unit>()
            val harness =
                waitingHarness(
                    releaseTool,
                    listOf(
                        { testAssistant("original complete") },
                        { testAssistant("follow-up one complete") },
                        { testAssistant("follow-up two complete") },
                    ),
                )
            val prompt = launch { harness.session.prompt("start") }
            runCurrent()

            harness.session.followUp("follow-up 1")
            harness.session.followUp("follow-up 2")
            releaseTool.complete(Unit)
            prompt.join()

            assertEquals(listOf("start", "follow-up 1", "follow-up 2"), userTexts(harness))
            assertEquals(
                listOf("", "original complete", "follow-up one complete", "follow-up two complete"),
                assistantTexts(harness),
            )
        }

    @Test
    fun allModeDeliversSteeringMessagesInOneBatch() =
        runTest {
            val releaseTool = CompletableDeferred<Unit>()
            var providerUsers = emptyList<String>()
            val harness =
                waitingHarness(
                    releaseTool,
                    listOf(
                        { context ->
                            providerUsers =
                                context.messages
                                    .filterIsInstance<dev.xuexue.pi.ai.UserMessage>()
                                    .map(::messageText)
                            testAssistant("batched")
                        },
                    ),
                )
            harness.session.setSteeringMode(QueueMode.ALL)
            val prompt = launch { harness.session.prompt("start") }
            runCurrent()

            harness.session.steer("one")
            harness.session.steer("two")
            releaseTool.complete(Unit)
            prompt.join()

            assertEquals(listOf("start", "one", "two"), providerUsers)
            assertEquals(listOf("", "batched"), assistantTexts(harness))
        }

    @Test
    fun promptRequiresAnExplicitStreamingBehaviorWhileActive() =
        runTest {
            val releaseTool = CompletableDeferred<Unit>()
            val harness = waitingHarness(releaseTool, listOf({ testAssistant("done") }))
            val prompt = launch { harness.session.prompt("start") }
            runCurrent()

            val error = assertFailsWith<IllegalStateException> { harness.session.prompt("second") }
            assertEquals(
                "Agent is already processing. Specify streamingBehavior ('steer' or 'followUp') to queue the message.",
                error.message,
            )
            harness.session.prompt(
                "queued",
                PromptOptions(streamingBehavior = StreamingBehavior.STEER),
            )
            assertEquals(listOf("queued"), harness.session.getSteeringMessages())
            releaseTool.complete(Unit)
            prompt.join()
        }

    @Test
    fun clearQueueReturnsBothQueuesAndEmitsAnEmptySnapshot() {
        val harness = AgentSessionHarness()
        val queueEvents = mutableListOf<AgentSessionEvent.QueueUpdate>()
        harness.session.subscribe { event ->
            if (event is AgentSessionEvent.QueueUpdate) queueEvents += event
        }
        harness.session.steer("steer")
        harness.session.followUp("follow")

        val cleared = harness.session.clearQueue()

        assertEquals(listOf("steer"), cleared.steering)
        assertEquals(listOf("follow"), cleared.followUp)
        assertEquals(0, harness.session.pendingMessageCount)
        assertTrue(queueEvents.last().steering.isEmpty())
        assertTrue(queueEvents.last().followUp.isEmpty())
        assertFalse(harness.agent.hasQueuedMessages())
    }

    private fun waitingHarness(
        releaseTool: CompletableDeferred<Unit>,
        laterResponses: List<SessionResponse>,
    ): AgentSessionHarness {
        val waitTool =
            AgentTool(
                name = "wait",
                label = "Wait",
                description = "Wait for release",
                parameters = buildJsonObject {},
                execute = { _, _, _, _ ->
                    releaseTool.await()
                    AgentToolResult(
                        content = listOf(TextContent("released")),
                        details = buildJsonObject {},
                    )
                },
            )
        val firstResponse: SessionResponse = {
            testAssistant(
                content = listOf(ToolCall("wait-1", "wait", buildJsonObject {})),
                stopReason = StopReason.TOOL_USE,
            )
        }
        return AgentSessionHarness(
            responses = listOf(firstResponse) + laterResponses,
            tools = listOf(waitTool),
        )
    }

    private fun userTexts(harness: AgentSessionHarness): List<String> =
        harness.session.messages
            .filterIsInstance<dev.xuexue.pi.ai.UserMessage>()
            .map(::messageText)

    private fun assistantTexts(harness: AgentSessionHarness): List<String> =
        harness.session.messages
            .filterIsInstance<dev.xuexue.pi.ai.AssistantMessage>()
            .map(::messageText)
}
