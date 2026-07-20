package dev.xuexue.pi.coding.compaction

import dev.xuexue.pi.agent.StreamFunction
import dev.xuexue.pi.ai.AbortController
import dev.xuexue.pi.ai.AssistantMessage
import dev.xuexue.pi.ai.Context
import dev.xuexue.pi.ai.SimpleStreamOptions
import dev.xuexue.pi.ai.ToolCall
import dev.xuexue.pi.ai.UserBlocks
import dev.xuexue.pi.ai.UserMessage
import dev.xuexue.pi.ai.UserText
import dev.xuexue.pi.coding.completedStream
import dev.xuexue.pi.coding.session.BranchSummaryEntry
import dev.xuexue.pi.coding.session.SessionEntry
import dev.xuexue.pi.coding.session.SessionManager
import dev.xuexue.pi.coding.testAssistant
import dev.xuexue.pi.coding.testModel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BranchSummarizationTest {
    @Test
    fun collectsOnlyTheAbandonedBranchUntilTheDeepestCommonAncestor() {
        val manager = SessionManager.inMemory("/tmp")
        manager.appendMessage(UserMessage(UserText("one"), 1))
        val ancestor = manager.appendMessage(testAssistant("answer"))
        manager.appendMessage(UserMessage(UserText("abandoned"), 3))
        val abandonedLeaf = manager.appendMessage(testAssistant("abandoned answer", timestamp = 4))
        manager.branch(ancestor)
        val target = manager.appendMessage(UserMessage(UserText("target"), 5))

        val result = collectEntriesForBranchSummary(manager, abandonedLeaf, target)

        assertEquals(ancestor, result.commonAncestorId)
        assertEquals(listOf("abandoned", "abandoned answer"), result.entries.map(::entryText))
    }

    @Test
    fun newestMessagesWinWhenTheSummaryTokenBudgetIsReached() {
        val manager = SessionManager.inMemory("/tmp")
        val entries =
            listOf(
                manager.appendMessage(UserMessage(UserText("old ".repeat(100)), 1)),
                manager.appendMessage(testAssistant("middle ".repeat(100), timestamp = 2)),
                manager.appendMessage(UserMessage(UserText("recent"), 3)),
            ).mapNotNull(manager::getEntry)

        val result = prepareBranchEntries(entries, tokenBudget = 10)

        assertEquals(listOf("recent"), result.messages.map(::messageText))
    }

    @Test
    fun branchSummaryDetailsTrackFilesFromNestedPiSummariesOnly() {
        val manager = SessionManager.inMemory("/tmp")
        manager.appendMessage(UserMessage(UserText("work"), 1))
        val summaryId =
            manager.branchWithSummary(
                manager.getLeafId(),
                "old summary",
                buildJsonObject {
                    put("readFiles", JsonArray(listOf(JsonPrimitive("read.txt"))))
                    put("modifiedFiles", JsonArray(listOf(JsonPrimitive("changed.txt"))))
                },
                fromHook = false,
            )
        val hookSummary =
            BranchSummaryEntry(
                id = "hook",
                parentId = summaryId,
                timestamp = "1970-01-01T00:00:00Z",
                fromId = summaryId,
                summary = "hook summary",
                details =
                    buildJsonObject {
                        put("readFiles", JsonArray(listOf(JsonPrimitive("ignored.txt"))))
                    },
                fromHook = true,
            )
        val entries = manager.getEntries() + hookSummary

        val result = prepareBranchEntries(entries)

        assertEquals(setOf("read.txt"), result.fileOperations.read)
        assertEquals(setOf("changed.txt"), result.fileOperations.edited)
        assertTrue("ignored.txt" !in result.fileOperations.read)
    }

    @Test
    fun generationUsesThePiPromptAndAppendsFileOperations() =
        runTest {
            val manager = SessionManager.inMemory("/tmp")
            manager.appendMessage(UserMessage(UserText("inspect the file"), 1))
            manager.appendMessage(
                testAssistant(
                    content =
                        listOf(
                            ToolCall(
                                id = "call",
                                name = "read",
                                arguments = buildJsonObject { put("path", "notes.md") },
                            ),
                        ),
                    timestamp = 2,
                ),
            )
            val entries = manager.getEntries()
            var capturedContext: Context? = null
            var capturedOptions: SimpleStreamOptions? = null
            val stream =
                StreamFunction { _, context, options ->
                    capturedContext = context
                    capturedOptions = options
                    completedStream(testAssistant("structured summary"))
                }

            val result =
                generateBranchSummary(
                    entries,
                    GenerateBranchSummaryOptions(
                        model = testModel(),
                        apiKey = "key",
                        signal = AbortController().signal,
                        customInstructions = "focus on risks",
                        streamFunction = stream,
                    ),
                )

            assertTrue(result.summary.orEmpty().startsWith("The user explored a different conversation branch"))
            val context = requireNotNull(capturedContext)
            val prompt = messageText(context.messages.single())
            assertTrue(prompt.contains("Additional focus: focus on risks"))
            assertTrue(result.summary.orEmpty().contains("<read-files>\nnotes.md\n</read-files>"))
            assertEquals(SUMMARIZATION_SYSTEM_PROMPT, context.systemPrompt)
            assertEquals(2_048, capturedOptions?.stream?.maxTokens)
        }

    private fun entryText(entry: SessionEntry): String =
        when (entry) {
            is dev.xuexue.pi.coding.session.SessionMessageEntry -> {
                when (val message = entry.message) {
                    is UserMessage -> {
                        (message.content as UserText).value
                    }

                    is AssistantMessage -> {
                        message.content
                            .filterIsInstance<dev.xuexue.pi.ai.TextContent>()
                            .joinToString("") { it.text }
                    }

                    else -> {
                        ""
                    }
                }
            }

            else -> {
                ""
            }
        }

    private fun messageText(message: dev.xuexue.pi.ai.AgentMessage): String =
        when (message) {
            is UserMessage -> {
                when (val content = message.content) {
                    is UserText -> {
                        content.value
                    }

                    is UserBlocks -> {
                        content.value
                            .filterIsInstance<dev.xuexue.pi.ai.TextContent>()
                            .joinToString("") { it.text }
                    }
                }
            }

            is AssistantMessage -> {
                message.content
                    .filterIsInstance<dev.xuexue.pi.ai.TextContent>()
                    .joinToString("") { it.text }
            }

            else -> {
                ""
            }
        }
}
