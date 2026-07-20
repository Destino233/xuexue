package dev.xuexue.pi.ai

import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageTransformTest {
    @Test
    fun downgradesUnsupportedUserAndToolImages() {
        val messages =
            listOf<Message>(
                UserMessage(
                    UserBlocks(
                        listOf(
                            ImageContent("a", "image/png"),
                            ImageContent("b", "image/png"),
                            TextContent("question"),
                        ),
                    ),
                    timestamp = 1,
                ),
                ToolResultMessage(
                    toolCallId = "call",
                    toolName = "image_tool",
                    content = listOf(ImageContent("c", "image/png"), ImageContent("d", "image/png")),
                    isError = false,
                    timestamp = 2,
                ),
            )

        val transformed = transformMessagesForModel(messages, textOnlyModel())

        val user = transformed[0] as UserMessage
        assertEquals(
            listOf("(image omitted: model does not support images)", "question"),
            (user.content as UserBlocks).value.filterIsInstance<TextContent>().map { it.text },
        )
        val tool = transformed[1] as ToolResultMessage
        assertEquals("(tool image omitted: model does not support images)", (tool.content.single() as TextContent).text)
    }

    @Test
    fun convertsCrossModelThinkingAndDropsIncompleteAssistantMessages() {
        val messages =
            listOf<Message>(
                AssistantMessage(
                    content =
                        listOf(
                            ThinkingContent("plan", thinkingSignature = "sig"),
                            ToolCall("call", "lookup", buildJsonObject {}),
                        ),
                    api = "openai-responses",
                    provider = "openai",
                    model = "old-model",
                    usage = emptyUsage(),
                    stopReason = StopReason.TOOL_USE,
                    timestamp = 1,
                ),
                AssistantMessage(
                    content = listOf(TextContent("partial")),
                    api = "openai-responses",
                    provider = "openai",
                    model = "old-model",
                    usage = emptyUsage(),
                    stopReason = StopReason.ERROR,
                    errorMessage = "overloaded",
                    timestamp = 2,
                ),
            )

        val transformed = transformMessagesForModel(messages, textOnlyModel())

        assertEquals(2, transformed.size)
        val assistant = transformed[0] as AssistantMessage
        assertEquals("plan", (assistant.content[0] as TextContent).text)
        assertFalse((assistant.content[1] as ToolCall).thoughtSignature != null)
        val synthetic = transformed[1] as ToolResultMessage
        assertTrue(synthetic.isError)
        assertEquals("No result provided", (synthetic.content.single() as TextContent).text)
    }

    @Test
    fun preservesSameModelThinkingSignatureAndImages() {
        val model =
            textOnlyModel().copy(
                api = "openai-responses",
                provider = "openai",
                id = "same-model",
                input = listOf(ModelInput.IMAGE),
            )
        val messages =
            listOf<Message>(
                AssistantMessage(
                    content = listOf(ThinkingContent("", thinkingSignature = "encrypted")),
                    api = model.api,
                    provider = model.provider,
                    model = model.id,
                    usage = emptyUsage(),
                    stopReason = StopReason.STOP,
                    timestamp = 1,
                ),
                UserMessage(UserBlocks(listOf(ImageContent("a", "image/png"))), 2),
            )

        val transformed = transformMessagesForModel(messages, model)

        assertEquals(2, transformed.size)
        assertTrue((transformed[0] as AssistantMessage).content.single() is ThinkingContent)
        assertTrue((transformed[1] as UserMessage).content is UserBlocks)
    }

    private fun textOnlyModel(): Model =
        Model(
            id = "new-model",
            name = "New model",
            api = "openai-completions",
            provider = "openai",
            baseUrl = "https://provider.test/v1",
            reasoning = true,
            input = listOf(ModelInput.TEXT),
            cost = ModelCost(0.0, 0.0, 0.0, 0.0),
            contextWindow = 128_000,
            maxTokens = 4_096,
        )
}
