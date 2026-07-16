package dev.xuexue.pi.coding.session

import dev.xuexue.pi.ai.AssistantMessage
import dev.xuexue.pi.ai.TextContent
import dev.xuexue.pi.ai.UserMessage
import dev.xuexue.pi.ai.UserText
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SessionContextTest {
    @Test
    fun emptyEntriesReturnDefaultContext() {
        val context = buildSessionContext(emptyList())

        assertTrue(context.messages.isEmpty())
        assertEquals("off", context.thinkingLevel)
        assertEquals(null, context.model)
    }

    @Test
    fun explicitNullLeafReturnsContextBeforeAllEntries() {
        val entries = listOf(messageEntry("1", null, "hello"))

        val context = buildSessionContext(entries, null)

        assertTrue(context.messages.isEmpty())
        assertTrue(buildContextEntries(entries, null).isEmpty())
    }

    @Test
    fun simpleConversationPreservesMessageOrder() {
        val entries =
            listOf(
                messageEntry("1", null, "hello"),
                messageEntry("2", "1", "hi there", assistant = true),
                messageEntry("3", "2", "how are you"),
                messageEntry("4", "3", "great", assistant = true),
            )

        val context = buildSessionContext(entries)

        assertEquals(listOf("user", "assistant", "user", "assistant"), context.messages.map(AgentMessageRole::of))
    }

    @Test
    fun settingsFollowFullPathAndAssistantModelWins() {
        val entries =
            listOf(
                messageEntry("1", null, "hello"),
                ThinkingLevelChangeEntry("2", "1", TEST_TIMESTAMP, "high"),
                ModelChangeEntry("3", "2", TEST_TIMESTAMP, "openai", "gpt-4"),
                messageEntry("4", "3", "hi", assistant = true),
            )

        val context = buildSessionContext(entries)

        assertEquals("high", context.thinkingLevel)
        assertEquals(SessionModel("anthropic", "claude-test"), context.model)
        assertEquals(2, context.messages.size)
    }

    @Test
    fun compactionPrependsSummaryAndKeepsConfiguredRange() {
        val entries =
            listOf(
                messageEntry("1", null, "first"),
                messageEntry("2", "1", "response1", assistant = true),
                messageEntry("3", "2", "second"),
                messageEntry("4", "3", "response2", assistant = true),
                CompactionEntry("5", "4", TEST_TIMESTAMP, "Summary of first two turns", "3", 1_000),
                messageEntry("6", "5", "third"),
                messageEntry("7", "6", "response3", assistant = true),
            )

        val context = buildSessionContext(entries)

        assertEquals(5, context.messages.size)
        assertEquals("Summary of first two turns", assertIs<CompactionSummaryMessage>(context.messages[0]).summary)
        assertEquals("second", assertIs<UserText>(assertIs<UserMessage>(context.messages[1]).content).value)
        val keptAssistant = assertIs<AssistantMessage>(context.messages[2])
        assertEquals("response2", assertIs<TextContent>(keptAssistant.content[0]).text)
        assertEquals("third", assertIs<UserText>(assertIs<UserMessage>(context.messages[3]).content).value)
    }

    @Test
    fun latestCompactionWins() {
        val entries =
            listOf(
                messageEntry("1", null, "a"),
                messageEntry("2", "1", "b", assistant = true),
                CompactionEntry("3", "2", TEST_TIMESTAMP, "First summary", "1", 100),
                messageEntry("4", "3", "c"),
                messageEntry("5", "4", "d", assistant = true),
                CompactionEntry("6", "5", TEST_TIMESTAMP, "Second summary", "4", 200),
                messageEntry("7", "6", "e"),
            )

        val context = buildSessionContext(entries)

        assertEquals(4, context.messages.size)
        assertEquals("Second summary", assertIs<CompactionSummaryMessage>(context.messages.first()).summary)
    }

    @Test
    fun contextEntriesRetainCustomStateWithoutSendingItToModel() {
        val entries =
            listOf(
                messageEntry("1", null, "first"),
                CustomEntry("2", "1", TEST_TIMESTAMP, "old-state", buildJsonObject { put("hidden", true) }),
                messageEntry("3", "2", "response1", assistant = true),
                CustomEntry("4", "3", TEST_TIMESTAMP, "kept-card", buildJsonObject { put("title", "Kept") }),
                messageEntry("5", "4", "second"),
                CompactionEntry("6", "5", TEST_TIMESTAMP, "Summary", "4", 1_000),
                CustomEntry("7", "6", TEST_TIMESTAMP, "after-card", buildJsonObject { put("title", "After") }),
                messageEntry("8", "7", "response2", assistant = true),
            )

        assertEquals(listOf("6", "4", "5", "7", "8"), buildContextEntries(entries).map(SessionEntry::id))
        assertEquals(
            listOf("compactionSummary", "user", "assistant"),
            buildSessionContext(entries).messages.map(AgentMessageRole::of),
        )
    }

    @Test
    fun specifiedLeafSelectsOnlyItsBranch() {
        val entries =
            listOf(
                messageEntry("1", null, "start"),
                messageEntry("2", "1", "response", assistant = true),
                messageEntry("3", "2", "branch A"),
                messageEntry("4", "2", "branch B"),
            )

        val branchA = buildSessionContext(entries, "3")
        val branchB = buildSessionContext(entries, "4")

        assertEquals("branch A", assertIs<UserText>(assertIs<UserMessage>(branchA.messages.last()).content).value)
        assertEquals("branch B", assertIs<UserText>(assertIs<UserMessage>(branchB.messages.last()).content).value)
    }

    @Test
    fun branchSummaryParticipatesInContext() {
        val entries =
            listOf(
                messageEntry("1", null, "start"),
                messageEntry("2", "1", "response", assistant = true),
                messageEntry("3", "2", "abandoned path"),
                BranchSummaryEntry("4", "2", TEST_TIMESTAMP, "3", "Summary of abandoned work"),
                messageEntry("5", "4", "new direction"),
            )

        val context = buildSessionContext(entries, "5")

        assertEquals(4, context.messages.size)
        assertEquals("Summary of abandoned work", assertIs<BranchSummaryMessage>(context.messages[2]).summary)
    }

    @Test
    fun missingLeafFallsBackToLastEntryAndOrphansStopAtBrokenParent() {
        val normal = listOf(messageEntry("1", null, "hello"), messageEntry("2", "1", "hi", assistant = true))
        val orphan = listOf(messageEntry("1", null, "hello"), messageEntry("2", "missing", "orphan", assistant = true))

        assertEquals(2, buildSessionContext(normal, "nonexistent").messages.size)
        assertEquals(1, buildSessionContext(orphan, "2").messages.size)
    }

    private object AgentMessageRole {
        fun of(message: dev.xuexue.pi.ai.AgentMessage): String = message.roleName()
    }
}
