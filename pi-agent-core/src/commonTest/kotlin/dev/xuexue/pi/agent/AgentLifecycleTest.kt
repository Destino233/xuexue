@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.xuexue.pi.agent

import dev.xuexue.pi.ai.AbortSignal
import dev.xuexue.pi.ai.AssistantMessage
import dev.xuexue.pi.ai.AssistantMessageEvent
import dev.xuexue.pi.ai.FailureStopReason
import dev.xuexue.pi.ai.StopReason
import dev.xuexue.pi.ai.TextContent
import dev.xuexue.pi.ai.createAssistantMessageEventStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AgentLifecycleTest {
    @Test
    fun emitsFullLifecycleWhenStreamFunctionThrows() =
        runTest {
            val agent =
                Agent(
                    AgentOptions(
                        streamFunction = StreamFunction { _, _, _ -> error("provider exploded") },
                    ),
                )
            val events = mutableListOf<String>()
            agent.subscribe { event, _ -> events += event.testTypeName() }

            agent.prompt("hello")

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
                events,
            )
            val failure = assertIs<AssistantMessage>(agent.currentState.messages.last())
            assertEquals(StopReason.ERROR, failure.stopReason)
            assertEquals("provider exploded", failure.errorMessage)
            assertEquals("provider exploded", agent.currentState.errorMessage)
        }

    @Test
    fun promptWaitsForAsyncAgentEndSubscriber() =
        runTest {
            val barrier = CompletableDeferred<Unit>()
            val agent = completedAgent()
            var listenerFinished = false
            agent.subscribe { event, _ ->
                if (event is AgentEvent.AgentEnd) {
                    barrier.await()
                    listenerFinished = true
                }
            }

            var promptResolved = false
            val promptJob =
                launch {
                    agent.prompt("hello")
                    promptResolved = true
                }
            runCurrent()

            assertFalse(promptResolved)
            assertFalse(listenerFinished)
            assertTrue(agent.currentState.isStreaming)

            barrier.complete(Unit)
            promptJob.join()

            assertTrue(listenerFinished)
            assertTrue(promptResolved)
            assertFalse(agent.currentState.isStreaming)
        }

    @Test
    fun waitForIdleWaitsForAsyncSubscribers() =
        runTest {
            val barrier = CompletableDeferred<Unit>()
            val agent = completedAgent()
            agent.subscribe { event, _ ->
                if (event is AgentEvent.MessageEnd && event.message is AssistantMessage) {
                    barrier.await()
                }
            }
            val promptJob = launch { agent.prompt("hello") }
            runCurrent()

            var idleResolved = false
            val idleJob =
                launch {
                    agent.waitForIdle()
                    idleResolved = true
                }
            runCurrent()

            assertFalse(idleResolved)
            assertTrue(agent.currentState.isStreaming)
            barrier.complete(Unit)
            promptJob.join()
            idleJob.join()
            assertTrue(idleResolved)
            assertFalse(agent.currentState.isStreaming)
        }

    @Test
    fun subscribersReceiveActiveAbortSignal() =
        runTest {
            var receivedSignal: AbortSignal? = null
            val agent = Agent(AgentOptions(streamFunction = abortAwareStream(this)))
            agent.subscribe { event, signal ->
                if (event == AgentEvent.AgentStart) receivedSignal = signal
            }
            val promptJob = launch { agent.prompt("hello") }
            runCurrent()

            val signal = assertNotNull(receivedSignal)
            assertFalse(signal.isAborted)
            agent.abort()
            promptJob.join()

            assertTrue(signal.isAborted)
            assertEquals(StopReason.ABORTED, assertIs<AssistantMessage>(agent.currentState.messages.last()).stopReason)
        }

    @Test
    fun rejectsSecondPromptWhileStreaming() =
        runTest {
            val agent = Agent(AgentOptions(streamFunction = abortAwareStream(this)))
            val firstPrompt = launch { agent.prompt("first") }
            runCurrent()

            val error = assertFailsWith<IllegalStateException> { agent.prompt("second") }
            assertEquals(
                "Agent is already processing a prompt. Use steer() or followUp() to queue messages, " +
                    "or wait for completion.",
                error.message,
            )
            agent.abort()
            firstPrompt.join()
        }

    @Test
    fun rejectsContinueWhileStreaming() =
        runTest {
            val agent = Agent(AgentOptions(streamFunction = abortAwareStream(this)))
            val firstPrompt = launch { agent.prompt("first") }
            runCurrent()

            val error = assertFailsWith<IllegalStateException> { agent.continueConversation() }
            assertEquals("Agent is already processing. Wait for completion before continuing.", error.message)
            agent.abort()
            firstPrompt.join()
        }

    @Test
    fun abortWithoutActiveRunIsNoOp() {
        val agent = Agent()
        agent.abort()
        assertFalse(agent.currentState.isStreaming)
    }

    private fun completedAgent(): Agent =
        Agent(
            AgentOptions(
                streamFunction = testCompletedStream(testAssistant(listOf(TextContent("ok")))),
            ),
        )

    private fun abortAwareStream(scope: CoroutineScope): StreamFunction =
        StreamFunction { _, _, options ->
            createAssistantMessageEventStream().also { stream ->
                scope.launch {
                    val partial = testAssistant(listOf(TextContent("")))
                    stream.push(AssistantMessageEvent.Start(partial))
                    awaitAbort(requireNotNull(options.stream.signal))
                    val aborted =
                        testAssistant(
                            listOf(TextContent("Aborted")),
                            stopReason = StopReason.ABORTED,
                        )
                    stream.push(AssistantMessageEvent.Error(FailureStopReason.ABORTED, aborted))
                }
            }
        }

    private suspend fun awaitAbort(signal: AbortSignal) {
        if (signal.isAborted) return
        suspendCancellableCoroutine { continuation ->
            val subscription = signal.onAbort { continuation.resume(Unit) }
            continuation.invokeOnCancellation { subscription.dispose() }
        }
    }
}
