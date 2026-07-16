package dev.xuexue.pi.coding.session

import dev.xuexue.pi.ai.UserText
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionManagerTreeTest {
    @Test
    fun appendsFormParentChainAndAdvanceLeaf() {
        val session = SessionManager.inMemory("/tmp")

        val first = session.appendMessage(userMessage("first"))
        val second = session.appendMessage(assistantMessage("second"))
        val third = session.appendMessage(userMessage("third"))

        val entries = session.getEntries()
        assertEquals(listOf(first, second, third), entries.map(SessionEntry::id))
        assertNull(entries[0].parentId)
        assertEquals(first, entries[1].parentId)
        assertEquals(second, entries[2].parentId)
        assertEquals(third, session.getLeafId())
    }

    @Test
    fun allAppendEntryTypesIntegrateIntoTree() {
        val session = SessionManager.inMemory("/tmp")

        val messageId = session.appendMessage(userMessage("hello"))
        val thinkingId = session.appendThinkingLevelChange("high")
        val modelId = session.appendModelChange("openai", "gpt-4")
        val customId = session.appendCustomEntry("state", buildJsonObject { put("key", "value") })
        val compactionId = session.appendCompaction("summary", messageId, 1_000)

        assertEquals(messageId, assertIs<ThinkingLevelChangeEntry>(session.getEntry(thinkingId)).parentId)
        assertEquals(thinkingId, assertIs<ModelChangeEntry>(session.getEntry(modelId)).parentId)
        val custom = assertIs<CustomEntry>(session.getEntry(customId))
        assertEquals(
            "value",
            custom.data
                ?.jsonObject
                ?.get("key")
                ?.jsonPrimitive
                ?.content,
        )
        assertEquals(customId, assertIs<CompactionEntry>(session.getEntry(compactionId)).parentId)
    }

    @Test
    fun getBranchReturnsRootToSelectedEntry() {
        val session = SessionManager.inMemory("/tmp")
        val first = session.appendMessage(userMessage("1"))
        val second = session.appendMessage(assistantMessage("2"))
        session.appendMessage(userMessage("3"))
        session.appendMessage(assistantMessage("4"))

        assertEquals(listOf(first, second), session.getBranch(second).map(SessionEntry::id))
    }

    @Test
    fun branchingPreservesOldPathAndCreatesSibling() {
        val session = SessionManager.inMemory("/tmp")
        val first = session.appendMessage(userMessage("1"))
        val second = session.appendMessage(assistantMessage("2"))
        val oldBranch = session.appendMessage(userMessage("3"))

        session.branch(second)
        val newBranch = session.appendMessage(userMessage("4-branch"))

        val root = session.getTree().single()
        assertEquals(first, root.entry.id)
        val branchPoint = root.children.single()
        assertEquals(second, branchPoint.entry.id)
        assertEquals(setOf(oldBranch, newBranch), branchPoint.children.map { it.entry.id }.toSet())
        assertEquals(listOf(first, second, newBranch), session.getBranch().map(SessionEntry::id))
    }

    @Test
    fun deepBranchesProduceExpectedTree() {
        val session = SessionManager.inMemory("/tmp")
        session.appendMessage(userMessage("1"))
        val second = session.appendMessage(assistantMessage("2"))
        val third = session.appendMessage(userMessage("3"))
        session.appendMessage(assistantMessage("4"))
        session.branch(second)
        val fifth = session.appendMessage(userMessage("5"))
        session.appendMessage(assistantMessage("6"))
        session.branch(fifth)
        session.appendMessage(userMessage("7"))

        val secondNode =
            session
                .getTree()
                .single()
                .children
                .single()
        assertEquals(2, secondNode.children.size)
        assertEquals(
            1,
            secondNode.children
                .single { it.entry.id == third }
                .children.size,
        )
        assertEquals(
            2,
            secondNode.children
                .single { it.entry.id == fifth }
                .children.size,
        )
    }

    @Test
    fun branchAndBranchSummaryRejectUnknownEntries() {
        val session = SessionManager.inMemory("/tmp")
        session.appendMessage(userMessage("hello"))

        val branchError = assertFailsWith<IllegalArgumentException> { session.branch("missing") }
        assertEquals("Entry missing not found", branchError.message)
        assertEquals(
            "Entry missing not found",
            assertFailsWith<IllegalArgumentException> { session.branchWithSummary("missing", "summary") }.message,
        )
    }

    @Test
    fun branchWithSummaryAdvancesLeafAndEntersContext() {
        val session = SessionManager.inMemory("/tmp")
        val first = session.appendMessage(userMessage("1"))
        session.appendMessage(assistantMessage("2"))
        session.appendMessage(userMessage("3"))

        val summaryId = session.branchWithSummary(first, "Summary of abandoned work")

        assertEquals(summaryId, session.getLeafId())
        assertEquals(first, assertIs<BranchSummaryEntry>(session.getEntry(summaryId)).parentId)
        val summary = assertIs<BranchSummaryMessage>(session.buildSessionContext().messages.last())
        assertEquals("Summary of abandoned work", summary.summary)
    }

    @Test
    fun labelsResolveIntoTreeAndLatestChangeWins() {
        val session = SessionManager.inMemory("/tmp")
        val messageId = session.appendMessage(userMessage("hello"))

        session.appendLabelChange(messageId, "first")
        val latest = session.appendLabelChange(messageId, "checkpoint")

        val latestEntry = assertIs<LabelEntry>(session.getEntry(latest))
        val node = session.getTree().first { it.entry.id == messageId }
        assertEquals("checkpoint", session.getLabel(messageId))
        assertEquals("checkpoint", node.label)
        assertEquals(latestEntry.timestamp, node.labelTimestamp)
        assertEquals(1, session.buildSessionContext().messages.size)
    }

    @Test
    fun emptyLabelClearsAndUnknownTargetFails() {
        val session = SessionManager.inMemory("/tmp")
        val messageId = session.appendMessage(userMessage("hello"))
        session.appendLabelChange(messageId, "checkpoint")

        session.appendLabelChange(messageId, null)

        assertNull(session.getLabel(messageId))
        assertFailsWith<IllegalArgumentException> { session.appendLabelChange("missing", "label") }
    }

    @Test
    fun resetLeafCreatesASecondRoot() {
        val session = SessionManager.inMemory("/tmp")
        session.appendMessage(userMessage("first"))
        session.resetLeaf()

        val secondRoot = session.appendMessage(userMessage("second"))

        assertEquals(2, session.getTree().size)
        assertNull(session.getEntry(secondRoot)?.parentId)
        assertEquals(1, session.buildSessionContext().messages.size)
    }

    @Test
    fun branchedSessionRetainsOnlySelectedPathAndItsLabels() {
        val session = SessionManager.inMemory("/tmp")
        val first = session.appendMessage(userMessage("1"))
        val second = session.appendMessage(assistantMessage("2"))
        val third = session.appendMessage(userMessage("3"))
        session.appendLabelChange(first, "first")
        session.appendLabelChange(second, "second")
        session.appendLabelChange(third, "third")

        val result = session.createBranchedSession(second)

        assertNull(result)
        val retainedMessages = session.getEntries().filterIsInstance<SessionMessageEntry>()
        assertEquals(listOf(first, second), retainedMessages.map(SessionEntry::id))
        assertEquals("first", session.getLabel(first))
        assertEquals("second", session.getLabel(second))
        assertNull(session.getLabel(third))
    }

    @Test
    fun sessionNameSanitizesNewlinesAndLatestEmptyNameClearsIt() {
        val session = SessionManager.inMemory("/tmp")
        session.appendSessionInfo("  alpha\r\nbeta\n ")
        assertEquals("alpha beta", session.getSessionName())

        session.appendSessionInfo("\n")

        assertNull(session.getSessionName())
        assertTrue(assertIs<SessionInfoEntry>(session.getLeafEntry()).name?.isEmpty() == true)
    }

    @Test
    fun selectedBranchContainsTypedUserContent() {
        val session = SessionManager.inMemory("/tmp")
        val first = session.appendMessage(userMessage("msg1"))
        val second = session.appendMessage(assistantMessage("msg2"))
        session.appendMessage(userMessage("msg3"))
        session.branch(second)
        session.appendMessage(userMessage("msg4"))

        val messages = session.buildSessionContext().messages
        assertEquals(3, messages.size)
        assertEquals("msg4", assertIs<UserText>(assertIs<dev.xuexue.pi.ai.UserMessage>(messages.last()).content).value)
        assertEquals(
            first,
            session
                .getTree()
                .single()
                .entry.id,
        )
    }
}
