@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.xuexue.pi.coding

import dev.xuexue.pi.agent.Agent
import dev.xuexue.pi.agent.AgentInitialState
import dev.xuexue.pi.agent.AgentOptions
import dev.xuexue.pi.agent.StreamFunction
import dev.xuexue.pi.ai.AbortSignal
import dev.xuexue.pi.ai.AssistantMessage
import dev.xuexue.pi.ai.AssistantMessageEvent
import dev.xuexue.pi.ai.FailureStopReason
import dev.xuexue.pi.ai.StopReason
import dev.xuexue.pi.ai.TextContent
import dev.xuexue.pi.ai.createAssistantMessageEventStream
import dev.xuexue.pi.coding.session.SessionManager
import dev.xuexue.pi.coding.session.SessionMessageEntry
import dev.xuexue.pi.coding.session.convertToLlm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentSessionLifecycleTest {
    @Test
    fun abortWaitsForSessionSettlementAndPersistsTheAbortedAssistant() =
        runTest {
            val manager = SessionManager.inMemory("/tmp")
            val agent = abortAwareAgent(this)
            val session = AgentSession(AgentSessionConfig(agent, manager))
            val settledIdleStates = mutableListOf<Boolean>()
            session.subscribe { event ->
                if (event == AgentSessionEvent.AgentSettled) settledIdleStates += session.isIdle
            }
            val prompt = launch { session.prompt("start") }
            runCurrent()

            assertTrue(session.isStreaming)
            session.abort()
            prompt.join()

            assertTrue(session.isIdle)
            assertEquals(listOf(true), settledIdleStates)
            val assistant =
                manager
                    .getEntries()
                    .filterIsInstance<SessionMessageEntry>()
                    .last()
                    .message as AssistantMessage
            assertEquals(StopReason.ABORTED, assistant.stopReason)
        }

    @Test
    fun waitForIdleDoesNotResolveBeforeTheRunCompletes() =
        runTest {
            val release = kotlinx.coroutines.CompletableDeferred<Unit>()
            val harness =
                AgentSessionHarness(
                    responses =
                        listOf(
                            {
                                release.await()
                                testAssistant("done")
                            },
                        ),
                )
            val prompt = launch { harness.session.prompt("start") }
            runCurrent()
            var idleResolved = false
            val waiter =
                launch {
                    harness.session.waitForIdle()
                    idleResolved = true
                }
            runCurrent()

            assertFalse(idleResolved)
            release.complete(Unit)
            prompt.join()
            waiter.join()
            assertTrue(idleResolved)
        }

    @Test
    fun followUpQueuedByAgentEndListenerGetsAContinuationBeforeSettlement() =
        runTest {
            val harness =
                AgentSessionHarness(
                    responses =
                        listOf(
                            { testAssistant("first") },
                            { testAssistant("follow-up") },
                        ),
                )
            var queued = false
            val events = mutableListOf<String>()
            harness.session.subscribe { event ->
                events += event::class.simpleName.orEmpty()
                if (event is AgentSessionEvent.AgentEnd && !queued) {
                    queued = true
                    harness.session.followUp("from agent_end")
                }
            }

            harness.session.prompt("start")

            assertEquals(
                listOf("start", "from agent_end"),
                harness.session.messages
                    .filterIsInstance<dev.xuexue.pi.ai.UserMessage>()
                    .map(::messageText),
            )
            assertEquals(1, events.count { it == AgentSessionEvent.AgentSettled::class.simpleName })
        }

    private fun abortAwareAgent(scope: CoroutineScope): Agent =
        Agent(
            AgentOptions(
                initialState = AgentInitialState(systemPrompt = "Test", model = testModel()),
                convertToLlm = { messages -> convertToLlm(messages) },
                streamFunction =
                    StreamFunction { _, _, options ->
                        createAssistantMessageEventStream().also { stream ->
                            scope.launch {
                                val partial = testAssistant("")
                                stream.push(AssistantMessageEvent.Start(partial))
                                awaitAbort(requireNotNull(options.stream.signal))
                                val aborted = testAssistant("aborted", stopReason = StopReason.ABORTED)
                                stream.push(AssistantMessageEvent.Error(FailureStopReason.ABORTED, aborted))
                            }
                        }
                    },
            ),
        )

    private suspend fun awaitAbort(signal: AbortSignal) {
        if (signal.isAborted) return
        suspendCancellableCoroutine { continuation ->
            val subscription = signal.onAbort { continuation.resume(Unit) }
            continuation.invokeOnCancellation { subscription.dispose() }
        }
    }
}
