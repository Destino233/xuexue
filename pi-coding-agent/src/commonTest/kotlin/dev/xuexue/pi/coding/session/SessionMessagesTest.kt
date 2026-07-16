package dev.xuexue.pi.coding.session

import dev.xuexue.pi.ai.TextContent
import dev.xuexue.pi.ai.UserBlocks
import dev.xuexue.pi.ai.UserMessage
import dev.xuexue.pi.ai.UserText
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SessionMessagesTest {
    @Test
    fun bashExecutionTextMatchesPiFormatting() {
        val failed =
            BashExecutionMessage(
                command = "make test",
                output = "failure",
                exitCode = 2,
                cancelled = false,
                truncated = true,
                fullOutputPath = "/tmp/full.log",
                timestamp = 1,
            )
        val cancelled = failed.copy(output = "", exitCode = null, cancelled = true, truncated = false)

        assertEquals(
            "Ran `make test`\n```\nfailure\n```\n\nCommand exited with code 2" +
                "\n\n[Output truncated. Full output: /tmp/full.log]",
            bashExecutionToText(failed),
        )
        assertEquals("Ran `make test`\n(no output)\n\n(command cancelled)", bashExecutionToText(cancelled))
    }

    @Test
    fun convertToLlmSkipsExcludedBashAndConvertsVisibleBashToUserMessage() {
        val hidden =
            BashExecutionMessage("pwd", "/tmp", 0, false, false, timestamp = 1, excludeFromContext = true)
        val visible = hidden.copy(excludeFromContext = false, timestamp = 2)

        val messages = convertToLlm(listOf(hidden, visible))

        val user = assertIs<UserMessage>(messages.single())
        val text = assertIs<TextContent>(assertIs<UserBlocks>(user.content).value.single()).text
        assertTrue(text.startsWith("Ran `pwd`"))
    }

    @Test
    fun customAndSummaryMessagesConvertToUserMessages() {
        val custom = CustomMessage("notice", UserText("custom text"), true, timestamp = 1)
        val branch = BranchSummaryMessage("branch work", "entry-1", 2)
        val compaction = CompactionSummaryMessage("old work", 1_000, 3)

        val messages = convertToLlm(listOf(custom, branch, compaction))

        assertEquals("custom text", assertIs<UserText>(assertIs<UserMessage>(messages[0]).content).value)
        val branchContent = assertIs<UserBlocks>(assertIs<UserMessage>(messages[1]).content)
        val branchText = assertIs<TextContent>(branchContent.value.single()).text
        val compactionText =
            assertIs<TextContent>(assertIs<UserBlocks>(assertIs<UserMessage>(messages[2]).content).value.single()).text
        assertEquals(BRANCH_SUMMARY_PREFIX + "branch work" + BRANCH_SUMMARY_SUFFIX, branchText)
        assertEquals(COMPACTION_SUMMARY_PREFIX + "old work" + COMPACTION_SUMMARY_SUFFIX, compactionText)
    }

    @Test
    fun customMessageEntryParticipatesInContextWhileCustomStateDoesNot() {
        val entries =
            listOf(
                CustomEntry("1", null, TEST_TIMESTAMP, "state", buildJsonObject { put("enabled", true) }),
                CustomMessageEntry("2", "1", TEST_TIMESTAMP, "notice", UserText("visible to model"), display = false),
            )

        val context = buildSessionContext(entries)

        val message = assertIs<CustomMessage>(context.messages.single())
        assertEquals("notice", message.customType)
        assertEquals("visible to model", assertIs<UserText>(message.content).value)
        assertEquals(false, message.display)
    }

    @Test
    fun sessionEntryJsonRoundTripsStandardAndCustomMessages() {
        val standard = SessionMessageEntry("1", null, TEST_TIMESTAMP, assistantMessage("hello"))
        val custom =
            SessionMessageEntry(
                "2",
                "1",
                TEST_TIMESTAMP,
                CustomMessage("test", UserText("payload"), true, buildJsonObject { put("version", 1) }, 3),
            )

        val decodedStandard = assertIs<SessionMessageEntry>(decodeSessionEntry(encodeSessionEntry(standard)))
        val decodedCustom = assertIs<SessionMessageEntry>(decodeSessionEntry(encodeSessionEntry(custom)))

        assertEquals(standard.message, decodedStandard.message)
        assertEquals(custom.message, decodedCustom.message)
    }

    @Test
    fun unknownMessageAndEntryPayloadsRemainRoundTrippable() {
        val rawMessage =
            buildJsonObject {
                put("role", "extensionRole")
                put("timestamp", 9)
                put("value", "kept")
            }
        val rawEntry =
            buildJsonObject {
                put("type", "extension_entry")
                put("id", "raw-1")
                put("parentId", kotlinx.serialization.json.JsonNull)
                put("timestamp", TEST_TIMESTAMP)
                put("data", buildJsonObject { put("value", "kept") })
            }

        assertEquals(rawMessage, encodeAgentMessage(assertIs<RawAgentMessage>(decodeAgentMessage(rawMessage))))
        assertEquals(rawEntry, encodeSessionEntry(assertIs<RawSessionEntry>(decodeSessionEntry(rawEntry))))
    }
}
