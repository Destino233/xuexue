package dev.xuexue.pi.ai

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EstimateTest {
    @Test
    fun ignoresStaleAssistantUsageAfterNewerPrefixMessage() {
        val context =
            Context(
                systemPrompt = "system",
                messages =
                    listOf(
                        UserMessage(UserText("summary"), timestamp = 200),
                        assistant(timestamp = 100, totalTokens = 9_500),
                        UserMessage(UserText("x".repeat(4_000)), timestamp = 300),
                    ),
            )

        assertEquals(
            ContextUsageEstimate(
                tokens = 1_005,
                usageTokens = 0,
                trailingTokens = 1_005,
                lastUsageIndex = null,
            ),
            estimateContextTokens(context),
        )
    }

    @Test
    fun usesAssistantUsageAfterResponseToInsertedContext() {
        val context =
            Context(
                messages =
                    listOf(
                        UserMessage(UserText("summary"), timestamp = 200),
                        assistant(timestamp = 100, totalTokens = 9_500),
                        UserMessage(UserText("new prompt"), timestamp = 300),
                        assistant(timestamp = 400, totalTokens = 2_000),
                        UserMessage(UserText("tail"), timestamp = 500),
                    ),
            )

        assertEquals(
            ContextUsageEstimate(
                tokens = 2_001,
                usageTokens = 2_000,
                trailingTokens = 1,
                lastUsageIndex = 3,
            ),
            estimateContextTokens(context),
        )
    }

    @Test
    fun estimatesImagesAsTwelveHundredTokens() {
        val message =
            UserMessage(
                UserBlocks(listOf(ImageContent("ignored", "image/png"))),
                timestamp = 1,
            )

        assertEquals(1_200, estimateMessageTokens(message))
    }

    @Test
    fun includesToolsAddedAfterLastUsage() {
        val parameters = buildJsonObject { put("type", "object") }
        val tool = Tool("late", "desc", parameters)
        val messages =
            listOf<Message>(
                assistant(timestamp = 100, totalTokens = 20),
                ToolResultMessage(
                    toolCallId = "call-1",
                    toolName = "search",
                    content = listOf(TextContent("ok")),
                    addedToolNames = listOf("late"),
                    isError = false,
                    timestamp = 200,
                ),
            )

        val withoutTools = estimateContextTokens(Context(messages = messages))
        val withTools = estimateContextTokens(Context(messages = messages, tools = listOf(tool)))

        assertTrue(withTools.tokens > withoutTools.tokens)
        assertEquals(
            withTools.tokens - withoutTools.tokens,
            withTools.trailingTokens - withoutTools.trailingTokens,
        )
        val emptyTools = estimateContextTokens(Context(messages = messages, tools = emptyList()))
        assertEquals(0, emptyTools.tokens - withoutTools.tokens)
    }

    private fun assistant(
        timestamp: Long,
        totalTokens: Long,
    ): AssistantMessage =
        AssistantMessage(
            content = listOf(TextContent("kept")),
            api = "openai-responses",
            provider = "openai",
            model = "test-model",
            usage = usage(totalTokens),
            stopReason = StopReason.STOP,
            timestamp = timestamp,
        )

    private fun usage(totalTokens: Long): Usage =
        Usage(
            input = totalTokens,
            output = 0,
            cacheRead = 0,
            cacheWrite = 0,
            totalTokens = totalTokens,
            cost = UsageCost(0.0, 0.0, 0.0, 0.0, 0.0),
        )
}
