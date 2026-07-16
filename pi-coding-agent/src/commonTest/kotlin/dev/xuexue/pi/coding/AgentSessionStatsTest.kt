package dev.xuexue.pi.coding

import dev.xuexue.pi.ai.StopReason
import dev.xuexue.pi.ai.TextContent
import dev.xuexue.pi.ai.ToolCall
import dev.xuexue.pi.ai.ToolResultMessage
import dev.xuexue.pi.ai.UserMessage
import dev.xuexue.pi.ai.UserText
import dev.xuexue.pi.coding.session.SessionManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentSessionStatsTest {
    @Test
    fun aggregatesAllPersistedMessageUsageAndToolCounts() {
        val manager = SessionManager.inMemory("/tmp")
        manager.appendMessage(UserMessage(UserText("hello"), 1))
        manager.appendMessage(
            testAssistant(
                content =
                    listOf(
                        TextContent("calling"),
                        ToolCall("call", "read", kotlinx.serialization.json.JsonObject(emptyMap())),
                    ),
                stopReason = StopReason.TOOL_USE,
                usage = testUsage(input = 10, output = 4, cacheRead = 3, cacheWrite = 2, cost = 0.25),
            ),
        )
        manager.appendMessage(
            ToolResultMessage(
                toolCallId = "call",
                toolName = "read",
                content = listOf(TextContent("result")),
                isError = false,
                timestamp = 3,
            ),
        )
        val harness = AgentSessionHarness(sessionManager = manager)
        harness.agent.replaceMessages(manager.buildSessionContext().messages)

        val stats = harness.session.getSessionStats()

        assertEquals(1, stats.userMessages)
        assertEquals(1, stats.assistantMessages)
        assertEquals(1, stats.toolCalls)
        assertEquals(1, stats.toolResults)
        assertEquals(3, stats.totalMessages)
        assertEquals(10, stats.tokens.input)
        assertEquals(4, stats.tokens.output)
        assertEquals(19, stats.tokens.total)
        assertEquals(0.25, stats.cost)
        assertEquals(21, stats.contextUsage?.tokens)
    }

    @Test
    fun contextUsageIsUnknownUntilAnAssistantRespondsAfterCompaction() {
        val manager = SessionManager.inMemory("/tmp")
        manager.appendMessage(UserMessage(UserText("first"), 1))
        manager.appendMessage(testAssistant("before", usage = testUsage(input = 100)))
        val keptId = manager.appendMessage(UserMessage(UserText("kept"), 3))
        manager.appendCompaction("summary", keptId, 100)
        manager.appendMessage(UserMessage(UserText("after"), 4))
        val harness = AgentSessionHarness(sessionManager = manager)
        harness.agent.replaceMessages(manager.buildSessionContext().messages)

        val usage = harness.session.getContextUsage()

        assertNull(usage?.tokens)
        assertNull(usage?.percent)
    }

    @Test
    fun lastAssistantTextSkipsAnEmptyAbortedMessage() {
        val manager = SessionManager.inMemory("/tmp")
        manager.appendMessage(testAssistant("previous"))
        manager.appendMessage(testAssistant(emptyList(), stopReason = StopReason.ABORTED))
        val harness = AgentSessionHarness(sessionManager = manager)
        harness.agent.replaceMessages(manager.buildSessionContext().messages)

        assertEquals("previous", harness.session.getLastAssistantText())
    }
}
