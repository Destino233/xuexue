package dev.xuexue.pi.coding.compaction

import dev.xuexue.pi.agent.StreamFunction
import dev.xuexue.pi.ai.Context
import dev.xuexue.pi.ai.ModelThinkingLevel
import dev.xuexue.pi.ai.SimpleStreamOptions
import dev.xuexue.pi.ai.ThinkingLevel
import dev.xuexue.pi.ai.UserMessage
import dev.xuexue.pi.ai.UserText
import dev.xuexue.pi.coding.completedStream
import dev.xuexue.pi.coding.testAssistant
import dev.xuexue.pi.coding.testModel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompactionGenerationTest {
    @Test
    fun summaryUsesPiPromptReasoningAndTokenBudget() =
        runTest {
            var capturedContext: Context? = null
            var capturedOptions: SimpleStreamOptions? = null
            val stream =
                StreamFunction { _, context, options ->
                    capturedContext = context
                    capturedOptions = options
                    completedStream(testAssistant("## Goal\nSummary"))
                }

            val result =
                generateSummary(
                    currentMessages = listOf(UserMessage(UserText("work"), 1)),
                    reserveTokens = 2_000,
                    previousSummary = "old summary",
                    request =
                        CompactionRequest(
                            model = testModel(reasoning = true),
                            apiKey = "key",
                            thinkingLevel = ModelThinkingLevel.MEDIUM,
                            streamFunction = stream,
                        ),
                )

            assertEquals("## Goal\nSummary", result)
            assertEquals(SUMMARIZATION_SYSTEM_PROMPT, capturedContext?.systemPrompt)
            val prompt = capturedContext?.messages?.single() as UserMessage
            assertTrue(promptText(prompt).contains("<conversation>"))
            assertTrue(promptText(prompt).contains("<previous-summary>\nold summary\n</previous-summary>"))
            assertEquals(1_600, capturedOptions?.stream?.maxTokens)
            assertEquals("key", capturedOptions?.stream?.apiKey)
            assertEquals(ThinkingLevel.MEDIUM, capturedOptions?.reasoning)
        }

    @Test
    fun summaryOmitsReasoningWhenOffOrUnsupported() =
        runTest {
            val captured = mutableListOf<SimpleStreamOptions>()
            val stream =
                StreamFunction { _, _, options ->
                    captured += options
                    completedStream(testAssistant("summary"))
                }

            generateSummary(
                currentMessages = listOf(UserMessage(UserText("one"), 1)),
                reserveTokens = 2_000,
                request =
                    CompactionRequest(
                        model = testModel(reasoning = true),
                        apiKey = null,
                        thinkingLevel = ModelThinkingLevel.OFF,
                        streamFunction = stream,
                    ),
            )
            generateSummary(
                currentMessages = listOf(UserMessage(UserText("two"), 2)),
                reserveTokens = 2_000,
                request =
                    CompactionRequest(
                        model = testModel(reasoning = false),
                        apiKey = null,
                        thinkingLevel = ModelThinkingLevel.HIGH,
                        streamFunction = stream,
                    ),
            )

            assertNull(captured[0].reasoning)
            assertNull(captured[1].reasoning)
        }

    @Test
    fun splitTurnCompactionMergesBothSummariesAndClampsModelOutput() =
        runTest {
            val options = mutableListOf<SimpleStreamOptions>()
            val responses = ArrayDeque(listOf("history", "turn prefix"))
            val stream =
                StreamFunction { _, _, requestOptions ->
                    options += requestOptions
                    completedStream(testAssistant(responses.removeFirst()))
                }
            val fileOperations = createFileOperations().apply { read += "src/Main.kt" }
            val preparation =
                CompactionPreparation(
                    firstKeptEntryId = "kept",
                    messagesToSummarize = listOf(UserMessage(UserText("history input"), 1)),
                    turnPrefixMessages = listOf(UserMessage(UserText("large turn"), 2)),
                    isSplitTurn = true,
                    tokensBefore = 600_000,
                    previousSummary = null,
                    fileOperations = fileOperations,
                    settings = CompactionSettings(reserveTokens = 500_000),
                )

            val result =
                compact(
                    preparation = preparation,
                    request =
                        CompactionRequest(
                            model = testModel().copy(maxTokens = 128_000),
                            apiKey = "key",
                            streamFunction = stream,
                        ),
                )

            assertEquals(listOf(128_000, 128_000), options.map { it.stream.maxTokens })
            assertEquals(
                "history\n\n---\n\n**Turn Context (split turn):**\n\nturn prefix" +
                    "\n\n<read-files>\nsrc/Main.kt\n</read-files>",
                result.summary,
            )
            assertEquals(listOf("src/Main.kt"), result.details?.readFiles)
        }

    private fun promptText(message: UserMessage): String =
        (message.content as dev.xuexue.pi.ai.UserBlocks)
            .value
            .filterIsInstance<dev.xuexue.pi.ai.TextContent>()
            .single()
            .text
}
