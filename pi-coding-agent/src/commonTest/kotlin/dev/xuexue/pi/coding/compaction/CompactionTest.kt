package dev.xuexue.pi.coding.compaction

import dev.xuexue.pi.ai.AssistantMessage
import dev.xuexue.pi.ai.StopReason
import dev.xuexue.pi.ai.TextContent
import dev.xuexue.pi.ai.UserMessage
import dev.xuexue.pi.ai.UserText
import dev.xuexue.pi.coding.session.CompactionEntry
import dev.xuexue.pi.coding.session.SessionManager
import dev.xuexue.pi.coding.testAssistant
import dev.xuexue.pi.coding.testUsage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompactionTest {
    @Test
    fun findsTheLastValidAssistantUsageAndAnchorsTrailingEstimates() {
        val manager = SessionManager.inMemory("/tmp")
        val valid = testAssistant("valid", usage = testUsage(input = 100, output = 50))
        manager.appendMessage(valid)
        manager.appendMessage(testAssistant("aborted", stopReason = StopReason.ABORTED, usage = testUsage(input = 300)))
        manager.appendMessage(testAssistant("zero", usage = testUsage(input = 0, output = 0)))
        val trailing = UserMessage(UserText("continue"), 4)

        val usage = getLastAssistantUsage(manager.getEntries())
        val estimate = estimateContextTokens(listOf(valid, trailing))

        assertEquals(100, usage?.input)
        assertEquals(150, estimate.usageTokens)
        assertEquals(0, estimate.lastUsageIndex)
        assertEquals(2, estimate.trailingTokens)
        assertEquals(152, estimate.tokens)
    }

    @Test
    fun checksTheStrictCompactionThresholdAndEnabledFlag() {
        val enabled = CompactionSettings(enabled = true, reserveTokens = 10_000, keepRecentTokens = 20_000)
        val disabled = enabled.copy(enabled = false)

        assertTrue(shouldCompact(90_001, 100_000, enabled))
        assertFalse(shouldCompact(90_000, 100_000, enabled))
        assertFalse(shouldCompact(95_000, 100_000, disabled))
    }

    @Test
    fun findsSplitTurnCutPointsAndBudgetsCustomMessages() {
        val manager = SessionManager.inMemory("/tmp")
        manager.appendMessage(UserMessage(UserText("hi"), 1))
        manager.appendMessage(testAssistant("hello"))
        manager.appendCustomMessageEntry("large", UserText("x".repeat(4_000)), display = true)
        manager.appendMessage(testAssistant("ok"))
        val entries = manager.getEntries()

        val tinyBudget = findCutPoint(entries, 0, entries.size, 1)
        val customFits = findCutPoint(entries, 0, entries.size, 2)

        assertEquals(3, tinyBudget.firstKeptEntryIndex)
        assertTrue(tinyBudget.isSplitTurn)
        assertEquals(2, tinyBudget.turnStartIndex)
        assertEquals(2, customFits.firstKeptEntryIndex)
        assertFalse(customFits.isSplitTurn)
        assertEquals(-1, customFits.turnStartIndex)
    }

    @Test
    fun preparationUsesTheLatestCompactionBoundaryAndPreviousSummary() {
        val manager = SessionManager.inMemory("/tmp")
        manager.appendMessage(UserMessage(UserText("old request"), 1))
        manager.appendMessage(testAssistant("old answer"))
        val keptId = manager.appendMessage(UserMessage(UserText("kept request ".repeat(20)), 3))
        manager.appendMessage(testAssistant("kept answer ".repeat(20)))
        manager.appendCompaction(
            summary = "previous summary",
            firstKeptEntryId = keptId,
            tokensBefore = 1_000,
            details =
                buildJsonObject {
                    put("readFiles", JsonArray(listOf(JsonPrimitive("old.txt"))))
                    put("modifiedFiles", JsonArray(listOf(JsonPrimitive("changed.txt"))))
                },
        )
        manager.appendMessage(UserMessage(UserText("new request ".repeat(20)), 5))
        manager.appendMessage(testAssistant("new answer ".repeat(20)))

        val preparation =
            prepareCompaction(
                manager.getBranch(),
                CompactionSettings(keepRecentTokens = 1),
            )

        assertNotNull(preparation)
        assertEquals("previous summary", preparation.previousSummary)
        assertTrue(preparation.messagesToSummarize.none { messageText(it).contains("old request") })
        assertTrue(preparation.messagesToSummarize.any { messageText(it).contains("kept request") })
        assertEquals(setOf("old.txt"), preparation.fileOperations.read)
        assertEquals(setOf("changed.txt"), preparation.fileOperations.edited)
    }

    @Test
    fun preparationSkipsAnImmediateRepeatedCompaction() {
        val manager = SessionManager.inMemory("/tmp")
        val userId = manager.appendMessage(UserMessage(UserText("request"), 1))
        manager.appendMessage(testAssistant("answer"))
        manager.appendCompaction("summary", userId, 2)

        assertTrue(manager.getEntries().last() is CompactionEntry)
        assertNull(prepareCompaction(manager.getBranch(), DEFAULT_COMPACTION_SETTINGS))
    }

    private fun messageText(message: dev.xuexue.pi.ai.AgentMessage): String =
        when (message) {
            is UserMessage -> (message.content as? UserText)?.value.orEmpty()
            is AssistantMessage -> message.content.filterIsInstance<TextContent>().joinToString("") { it.text }
            else -> ""
        }
}
