package dev.xuexue.pi.coding

import dev.xuexue.pi.ai.AssistantMessage
import dev.xuexue.pi.ai.StopReason
import dev.xuexue.pi.ai.UserMessage
import dev.xuexue.pi.ai.UserText
import dev.xuexue.pi.coding.compaction.CompactionSettings
import dev.xuexue.pi.coding.session.SessionManager
import dev.xuexue.pi.coding.session.SessionMessageEntry
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class AgentSessionAutoCompactionTest {
    private var overflowTimestamp = Clock.System.now().toEpochMilliseconds() + 60_000

    @Test
    fun thresholdCompactionRunsAfterTheSuccessfulTurnWithoutRetrying() =
        runTest {
            val settings =
                InMemoryAgentSessionSettings(
                    compactionSettings =
                        CompactionSettings(
                            enabled = true,
                            reserveTokens = 8_190,
                            keepRecentTokens = 1,
                        ),
                )
            val harness =
                AgentSessionHarness(
                    responses =
                        listOf(
                            { _ -> testAssistant("answer", usage = testUsage(input = 100)) },
                            { _ -> testAssistant("turn summary") },
                        ),
                    settingsManager = settings,
                )
            val compactions = mutableListOf<AgentSessionEvent.CompactionEnd>()
            harness.session.subscribe { event ->
                if (event is AgentSessionEvent.CompactionEnd) compactions += event
            }

            harness.session.prompt("request")

            val event = compactions.single()
            assertEquals(CompactionReason.THRESHOLD, event.reason)
            assertFalse(event.willRetry)
            assertNotNull(event.result)
            assertEquals(0, harness.pendingResponseCount)
            assertTrue(harness.session.isIdle)
        }

    @Test
    fun overflowCompactsThenRetriesWhileKeepingTheErrorOnlyInHistory() =
        runTest {
            val manager = managerWithPriorContext()
            val harness =
                AgentSessionHarness(
                    responses =
                        listOf(
                            { _ -> overflowError() },
                            { _ -> testAssistant("history summary") },
                            { _ -> testAssistant("recovered") },
                        ),
                    sessionManager = manager,
                    settingsManager = overflowSettings(),
                )
            harness.agent.replaceMessages(manager.buildSessionContext().messages)
            val compactions = mutableListOf<AgentSessionEvent.CompactionEnd>()
            harness.session.subscribe { event ->
                if (event is AgentSessionEvent.CompactionEnd) compactions += event
            }

            harness.session.prompt("large request")

            val compaction = compactions.single()
            assertEquals(CompactionReason.OVERFLOW, compaction.reason)
            assertTrue(compaction.willRetry)
            assertNotNull(compaction.result)
            assertEquals("recovered", messageText(harness.session.messages.last()))
            assertTrue(
                manager.getEntries().filterIsInstance<SessionMessageEntry>().any { entry ->
                    (entry.message as? AssistantMessage)?.errorMessage == "prompt is too long"
                },
            )
            assertTrue(
                harness.session.messages.filterIsInstance<AssistantMessage>().none {
                    it.errorMessage == "prompt is too long"
                },
            )
            assertEquals(0, harness.pendingResponseCount)
        }

    @Test
    fun overflowRecoveryIsAttemptedOnlyOncePerUserMessage() =
        runTest {
            val manager = managerWithPriorContext()
            val harness =
                AgentSessionHarness(
                    responses =
                        listOf(
                            { _ -> overflowError() },
                            { _ -> testAssistant("history summary") },
                            { _ -> overflowError() },
                        ),
                    sessionManager = manager,
                    settingsManager = overflowSettings(),
                )
            harness.agent.replaceMessages(manager.buildSessionContext().messages)
            val compactions = mutableListOf<AgentSessionEvent.CompactionEnd>()
            harness.session.subscribe { event ->
                if (event is AgentSessionEvent.CompactionEnd) compactions += event
            }

            harness.session.prompt("large request")

            assertEquals(2, compactions.size)
            assertTrue(compactions[0].willRetry)
            assertEquals(null, compactions[1].result)
            assertEquals(
                "Context overflow recovery failed after one compact-and-retry attempt. " +
                    "Try reducing context or switching to a larger-context model.",
                compactions[1].errorMessage,
            )
            assertEquals(0, harness.pendingResponseCount)
        }

    private fun managerWithPriorContext(): SessionManager =
        SessionManager.inMemory("/tmp").also { manager ->
            manager.appendMessage(UserMessage(UserText("prior request ".repeat(20)), 1))
            manager.appendMessage(
                testAssistant(
                    text = "prior answer ".repeat(20),
                    timestamp = 2,
                    usage = testUsage(input = 1_000),
                ),
            )
        }

    private fun overflowSettings(): AgentSessionSettings =
        InMemoryAgentSessionSettings(
            retrySettings = RetrySettings(enabled = true, maxRetries = 3, baseDelayMs = 1),
            compactionSettings = CompactionSettings(enabled = true, reserveTokens = 1, keepRecentTokens = 1),
        )

    private fun overflowError(): AssistantMessage =
        testAssistant(
            "",
            stopReason = StopReason.ERROR,
            timestamp = overflowTimestamp++,
        ).copy(errorMessage = "prompt is too long")
}
