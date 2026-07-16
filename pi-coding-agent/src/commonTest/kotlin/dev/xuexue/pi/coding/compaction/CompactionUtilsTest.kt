package dev.xuexue.pi.coding.compaction

import dev.xuexue.pi.ai.AssistantMessage
import dev.xuexue.pi.ai.StopReason
import dev.xuexue.pi.ai.TextContent
import dev.xuexue.pi.ai.ThinkingContent
import dev.xuexue.pi.ai.ToolCall
import dev.xuexue.pi.ai.ToolResultMessage
import dev.xuexue.pi.ai.UserMessage
import dev.xuexue.pi.ai.UserText
import dev.xuexue.pi.coding.testAssistant
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompactionUtilsTest {
    @Test
    fun truncatesOnlyLongToolResultsDuringSerialization() {
        val longResult = toolResult("x".repeat(5_000))
        val longUser = UserMessage(UserText("y".repeat(5_000)), 1)

        val serialized = serializeConversation(listOf(longResult, longUser))

        assertTrue(serialized.contains("[Tool result]: ${"x".repeat(2_000)}"))
        assertTrue(serialized.contains("[... 3000 more characters truncated]"))
        assertFalse(serialized.contains("x".repeat(3_000)))
        assertTrue(serialized.contains("y".repeat(5_000)))
    }

    @Test
    fun serializesAssistantThinkingTextAndToolCallsInPiOrder() {
        val assistant =
            testAssistant(
                content =
                    listOf(
                        TextContent("answer"),
                        ThinkingContent("plan"),
                        ToolCall(
                            id = "call",
                            name = "read",
                            arguments = buildJsonObject { put("path", "src/Main.kt") },
                        ),
                    ),
            )

        val serialized = serializeConversation(listOf(assistant))

        assertEquals(
            "[Assistant thinking]: plan\n\n" +
                "[Assistant]: answer\n\n" +
                "[Assistant tool calls]: read(path=\"src/Main.kt\")",
            serialized,
        )
    }

    @Test
    fun tracksReadAndModifiedFilesWithModifiedFilesTakingPrecedence() {
        val operations = createFileOperations()
        extractFileOperations(toolAssistant("read", "a.txt"), operations)
        extractFileOperations(toolAssistant("read", "b.txt"), operations)
        extractFileOperations(toolAssistant("edit", "b.txt"), operations)
        extractFileOperations(toolAssistant("write", "c.txt"), operations)

        val files = computeFileLists(operations)

        assertEquals(listOf("a.txt"), files.readFiles)
        assertEquals(listOf("b.txt", "c.txt"), files.modifiedFiles)
        assertEquals(
            "\n\n<read-files>\na.txt\n</read-files>\n\n" +
                "<modified-files>\nb.txt\nc.txt\n</modified-files>",
            formatFileOperations(files.readFiles, files.modifiedFiles),
        )
    }

    private fun toolResult(text: String): ToolResultMessage =
        ToolResultMessage(
            toolCallId = "call",
            toolName = "read",
            content = listOf(TextContent(text)),
            isError = false,
            timestamp = 1,
        )

    private fun toolAssistant(
        name: String,
        path: String,
    ): AssistantMessage =
        testAssistant(
            content =
                listOf(
                    ToolCall(
                        id = "$name-$path",
                        name = name,
                        arguments = buildJsonObject { put("path", JsonPrimitive(path)) },
                    ),
                ),
            stopReason = StopReason.TOOL_USE,
        )
}
