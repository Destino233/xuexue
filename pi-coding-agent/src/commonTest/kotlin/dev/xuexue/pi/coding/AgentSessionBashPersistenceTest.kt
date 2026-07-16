@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.xuexue.pi.coding

import dev.xuexue.pi.coding.session.BashExecutionMessage
import dev.xuexue.pi.coding.session.SessionMessageEntry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentSessionBashPersistenceTest {
    @Test
    fun recordsBashResultsImmediatelyWhileIdleWithoutMessageEvents() {
        val harness = AgentSessionHarness()
        val messageEvents = mutableListOf<AgentSessionEvent>()
        harness.session.subscribe { event ->
            if (event is AgentSessionEvent.MessageStart || event is AgentSessionEvent.MessageEnd) {
                messageEvents += event
            }
        }

        harness.session.recordBashResult(
            "echo hi",
            BashResult(
                output = "hi",
                exitCode = 0,
                cancelled = false,
                truncated = false,
            ),
        )

        assertFalse(harness.session.hasPendingBashMessages)
        assertTrue(harness.session.messages.last() is BashExecutionMessage)
        assertTrue(harness.manager.getEntries().last() is SessionMessageEntry)
        assertTrue(messageEvents.isEmpty())
    }

    @Test
    fun defersBashResultsUntilTheActiveAgentRunFinishes() =
        runTest {
            val release = CompletableDeferred<Unit>()
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

            harness.session.recordBashResult(
                "echo hi",
                BashResult(
                    output = "hi",
                    exitCode = 0,
                    cancelled = false,
                    truncated = false,
                ),
                excludeFromContext = true,
            )

            assertTrue(harness.session.hasPendingBashMessages)
            assertFalse(harness.session.messages.any { it is BashExecutionMessage })
            release.complete(Unit)
            prompt.join()

            assertFalse(harness.session.hasPendingBashMessages)
            val bash =
                harness.session.messages
                    .filterIsInstance<BashExecutionMessage>()
                    .single()
            assertEquals("echo hi", bash.command)
            assertEquals(true, bash.excludeFromContext)
            assertTrue(harness.manager.getEntries().last() is SessionMessageEntry)
        }
}
