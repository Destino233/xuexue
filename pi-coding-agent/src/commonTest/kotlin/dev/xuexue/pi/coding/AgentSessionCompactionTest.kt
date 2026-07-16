@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.xuexue.pi.coding

import dev.xuexue.pi.agent.StreamFunction
import dev.xuexue.pi.ai.AbortSignal
import dev.xuexue.pi.ai.AssistantMessageEvent
import dev.xuexue.pi.ai.FailureStopReason
import dev.xuexue.pi.ai.StopReason
import dev.xuexue.pi.ai.UserMessage
import dev.xuexue.pi.ai.UserText
import dev.xuexue.pi.ai.createAssistantMessageEventStream
import dev.xuexue.pi.coding.compaction.CompactionSettings
import dev.xuexue.pi.coding.session.CompactionEntry
import dev.xuexue.pi.coding.session.CompactionSummaryMessage
import dev.xuexue.pi.coding.session.SessionManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AgentSessionCompactionTest {
    @Test
    fun manuallyCompactsAndReplacesOnlyTheActiveModelContext() =
        runTest {
            val manager = populatedManager()
            val settings =
                InMemoryAgentSessionSettings(
                    compactionSettings = CompactionSettings(keepRecentTokens = 5),
                )
            val harness =
                AgentSessionHarness(
                    responses = listOf({ _ -> testAssistant("summary") }),
                    sessionManager = manager,
                    settingsManager = settings,
                )
            harness.agent.replaceMessages(manager.buildSessionContext().messages)
            val events = mutableListOf<AgentSessionEvent>()
            harness.session.subscribe(events::add)

            val result = harness.session.compact("focus on files")

            assertEquals("summary\n\n<read-files>\nold.txt\n</read-files>", result.summary)
            assertTrue(result.estimatedTokensAfter != null)
            val entry = assertIs<CompactionEntry>(manager.getEntries().last())
            assertEquals(result.firstKeptEntryId, entry.firstKeptEntryId)
            assertEquals(false, entry.fromHook)
            assertEquals(
                listOf("old.txt"),
                (entry.details as JsonObject)["readFiles"]?.jsonArray?.map { it.jsonPrimitive.content },
            )
            assertTrue(harness.session.messages.first() is CompactionSummaryMessage)
            assertEquals(1, events.filterIsInstance<AgentSessionEvent.CompactionStart>().size)
            val end = events.filterIsInstance<AgentSessionEvent.CompactionEnd>().single()
            assertFalse(end.aborted)
            assertEquals(result, end.result)
            assertFalse(harness.session.isCompacting)
        }

    @Test
    fun reportsAlreadyCompactedWithoutCallingTheProviderAgain() =
        runTest {
            val manager = populatedManager()
            val harness =
                AgentSessionHarness(
                    responses = listOf({ _ -> testAssistant("summary") }),
                    sessionManager = manager,
                    settingsManager =
                        InMemoryAgentSessionSettings(
                            compactionSettings = CompactionSettings(keepRecentTokens = 5),
                        ),
                )
            harness.agent.replaceMessages(manager.buildSessionContext().messages)
            harness.session.compact()

            val error = assertFailsWith<IllegalStateException> { harness.session.compact() }

            assertEquals("Already compacted", error.message)
            assertEquals(0, harness.pendingResponseCount)
        }

    @Test
    fun abortCompactionEmitsAnAbortedEndEventAndReconnectsAgentEvents() =
        runTest {
            val manager = populatedManager()
            val streamStarted = CompletableDeferred<Unit>()
            val harness =
                AgentSessionHarness(
                    responses = emptyList(),
                    sessionManager = manager,
                    settingsManager =
                        InMemoryAgentSessionSettings(
                            compactionSettings = CompactionSettings(keepRecentTokens = 5),
                        ),
                    streamFunction = abortAwareStream(this, streamStarted),
                )
            harness.agent.replaceMessages(manager.buildSessionContext().messages)
            val endEvents = mutableListOf<AgentSessionEvent.CompactionEnd>()
            harness.session.subscribe { event ->
                if (event is AgentSessionEvent.CompactionEnd) endEvents += event
            }
            val result = async { runCatching { harness.session.compact() } }
            runCurrent()
            streamStarted.await()

            harness.session.abortCompaction()
            val error = result.await().exceptionOrNull()

            assertEquals("Compaction cancelled", error?.message)
            assertTrue(endEvents.single().aborted)
            assertFalse(harness.session.isCompacting)

            harness.agent.streamFunction = StreamFunction { _, _, _ -> completedStream(testAssistant("after")) }
            val messageEnds = mutableListOf<AgentSessionEvent.MessageEnd>()
            harness.session.subscribe { event ->
                if (event is AgentSessionEvent.MessageEnd) messageEnds += event
            }
            harness.session.prompt("still connected")
            assertTrue(messageEnds.isNotEmpty())
        }

    private fun populatedManager(): SessionManager {
        val manager = SessionManager.inMemory("/tmp")
        manager.appendMessage(UserMessage(UserText("old request"), 1))
        manager.appendMessage(
            testAssistant(
                content =
                    listOf(
                        dev.xuexue.pi.ai.ToolCall(
                            "read-old",
                            "read",
                            kotlinx.serialization.json.buildJsonObject { put("path", "old.txt") },
                        ),
                    ),
                stopReason = StopReason.TOOL_USE,
            ),
        )
        manager.appendMessage(UserMessage(UserText("new request"), 3))
        manager.appendMessage(testAssistant("new answer", timestamp = 4))
        return manager
    }

    private fun abortAwareStream(
        scope: CoroutineScope,
        started: CompletableDeferred<Unit>,
    ): StreamFunction =
        StreamFunction { _, _, options ->
            createAssistantMessageEventStream().also { stream ->
                scope.launch {
                    started.complete(Unit)
                    awaitAbort(requireNotNull(options.stream.signal))
                    val aborted = testAssistant("", stopReason = StopReason.ABORTED)
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
