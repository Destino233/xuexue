@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.xuexue.pi.coding

import dev.xuexue.pi.ai.AssistantMessage
import dev.xuexue.pi.ai.StopReason
import dev.xuexue.pi.coding.session.SessionMessageEntry
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentSessionRetryTest {
    @Test
    fun retriesTransientErrorsAndEmitsOneSettlementAfterSuccess() =
        runTest {
            val harness = retryHarness(listOf(retryableError(), testAssistant("recovered")))
            val retryEvents = mutableListOf<String>()
            val agentEnds = mutableListOf<Boolean>()
            var settlements = 0
            harness.session.subscribe { event ->
                when (event) {
                    is AgentSessionEvent.AutoRetryStart -> retryEvents += "start:${event.attempt}"
                    is AgentSessionEvent.AutoRetryEnd -> retryEvents += "end:${event.success}"
                    is AgentSessionEvent.AgentEnd -> agentEnds += event.willRetry
                    AgentSessionEvent.AgentSettled -> settlements++
                    else -> Unit
                }
            }

            harness.session.prompt("test")

            assertEquals(listOf("start:1", "end:true"), retryEvents)
            assertEquals(listOf(true, false), agentEnds)
            assertEquals(1, settlements)
            assertEquals(0, harness.session.retryAttempt)
            assertFalse(harness.session.isRetrying)
            assertEquals(0, harness.pendingResponseCount)
            assertEquals(listOf("user", "assistant"), harness.session.messages.map(::messageRole))
            assertEquals(
                listOf("user", "assistant", "assistant"),
                harness.manager
                    .getEntries()
                    .filterIsInstance<SessionMessageEntry>()
                    .map { messageRole(it.message) },
            )
        }

    @Test
    fun exhaustsTheConfiguredRetryBudgetAndKeepsTheFinalError() =
        runTest {
            val harness =
                retryHarness(
                    responses = listOf(retryableError(), retryableError(), retryableError()),
                    maxRetries = 2,
                )
            val starts = mutableListOf<Int>()
            val ends = mutableListOf<AgentSessionEvent.AutoRetryEnd>()
            val agentEnds = mutableListOf<Boolean>()
            harness.session.subscribe { event ->
                when (event) {
                    is AgentSessionEvent.AutoRetryStart -> starts += event.attempt
                    is AgentSessionEvent.AutoRetryEnd -> ends += event
                    is AgentSessionEvent.AgentEnd -> agentEnds += event.willRetry
                    else -> Unit
                }
            }

            harness.session.prompt("test")

            assertEquals(listOf(1, 2), starts)
            assertEquals(listOf(true, true, false), agentEnds)
            assertEquals(1, ends.size)
            assertFalse(ends.single().success)
            assertEquals("overloaded_error", ends.single().finalError)
            assertEquals(StopReason.ERROR, (harness.session.messages.last() as AssistantMessage).stopReason)
            assertEquals(0, harness.session.retryAttempt)
        }

    @Test
    fun doesNotRetryDisabledOrNonRetryableErrors() =
        runTest {
            val disabled =
                AgentSessionHarness(
                    responses = listOf({ _ -> retryableError() }),
                    settingsManager =
                        InMemoryAgentSessionSettings(
                            RetrySettings(enabled = false, maxRetries = 3, baseDelayMs = 1),
                        ),
                )
            val nonRetryable = retryHarness(listOf(testAssistant("", StopReason.ERROR).withError("invalid_api_key")))
            var retryStarts = 0
            disabled.session.subscribe { if (it is AgentSessionEvent.AutoRetryStart) retryStarts++ }
            nonRetryable.session.subscribe { if (it is AgentSessionEvent.AutoRetryStart) retryStarts++ }

            disabled.session.prompt("disabled")
            nonRetryable.session.prompt("invalid")

            assertEquals(0, retryStarts)
            assertEquals(0, disabled.pendingResponseCount)
            assertEquals(0, nonRetryable.pendingResponseCount)
        }

    @Test
    fun abortRetryCancelsBackoffAndSettlesWithoutAnotherProviderCall() =
        runTest {
            val harness =
                retryHarness(
                    responses = listOf(retryableError()),
                    baseDelayMs = 10_000,
                )
            val ends = mutableListOf<AgentSessionEvent.AutoRetryEnd>()
            harness.session.subscribe { event ->
                if (event is AgentSessionEvent.AutoRetryEnd) ends += event
            }
            val prompt = launch { harness.session.prompt("test") }
            runCurrent()

            assertTrue(harness.session.isRetrying)
            harness.session.abortRetry()
            prompt.join()

            assertFalse(harness.session.isRetrying)
            assertTrue(harness.session.isIdle)
            assertEquals("Retry cancelled", ends.single().finalError)
            assertEquals(0, harness.session.retryAttempt)
            assertEquals(0, harness.pendingResponseCount)
        }

    @Test
    fun retryToggleUsesTheInjectedSettingsStore() {
        val settings = InMemoryAgentSessionSettings()
        val harness = AgentSessionHarness(settingsManager = settings)

        assertTrue(harness.session.autoRetryEnabled)
        harness.session.setAutoRetryEnabled(false)

        assertFalse(harness.session.autoRetryEnabled)
        assertFalse(settings.getRetrySettings().enabled)
    }

    private fun retryHarness(
        responses: List<AssistantMessage>,
        maxRetries: Int = 3,
        baseDelayMs: Long = 1,
    ): AgentSessionHarness =
        AgentSessionHarness(
            responses = responses.map { response -> { _ -> response } },
            settingsManager =
                InMemoryAgentSessionSettings(
                    RetrySettings(
                        enabled = true,
                        maxRetries = maxRetries,
                        baseDelayMs = baseDelayMs,
                    ),
                ),
        )

    private fun retryableError(): AssistantMessage = testAssistant("", StopReason.ERROR).withError("overloaded_error")

    private fun AssistantMessage.withError(error: String): AssistantMessage = copy(errorMessage = error)
}
