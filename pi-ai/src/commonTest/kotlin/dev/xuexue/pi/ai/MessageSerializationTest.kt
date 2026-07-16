package dev.xuexue.pi.ai

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MessageSerializationTest {
    @Test
    fun userStringContentUsesPiWireShape() {
        val message: Message = UserMessage(UserText("hello"), timestamp = 42)

        val encoded = PiJson.encodeToString(Message.serializer(), message)

        assertEquals("""{"role":"user","content":"hello","timestamp":42}""", encoded)
        assertEquals(message, PiJson.decodeFromString(Message.serializer(), encoded))
    }

    @Test
    fun userBlockContentUsesTypeDiscriminator() {
        val message: Message =
            UserMessage(
                content =
                    UserBlocks(
                        listOf(
                            TextContent("look"),
                            ImageContent("AA==", "image/png"),
                        ),
                    ),
                timestamp = 7,
            )

        val encoded = PiJson.encodeToString(Message.serializer(), message)

        assertEquals(
            """{"role":"user","content":[{"type":"text","text":"look"},""" +
                """{"type":"image","data":"AA==","mimeType":"image/png"}],"timestamp":7}""",
            encoded,
        )
        assertEquals(message, PiJson.decodeFromString(Message.serializer(), encoded))
    }

    @Test
    fun assistantMessagePreservesToolCallAndStopReason() {
        val message: Message =
            assistantMessage(
                content =
                    listOf(
                        ThinkingContent("checking"),
                        TextContent("done"),
                        ToolCall(
                            id = "call-1",
                            name = "read",
                            arguments = buildJsonObject { put("path", "README.md") },
                        ),
                    ),
                stopReason = StopReason.TOOL_USE,
            )

        val encoded = PiJson.encodeToString(Message.serializer(), message)
        val decoded = PiJson.decodeFromString(Message.serializer(), encoded)

        assertEquals(true, encoded.contains("\"stopReason\":\"toolUse\""))
        assertEquals(true, encoded.contains("\"type\":\"toolCall\""))
        assertEquals(message, decoded)
        assertIs<AssistantMessage>(decoded)
    }

    @Test
    fun assistantEventUsesPiEventNames() {
        val message = assistantMessage(stopReason = StopReason.TOOL_USE)
        val event: AssistantMessageEvent =
            AssistantMessageEvent.Done(
                reason = SuccessfulStopReason.TOOL_USE,
                message = message,
            )

        val encoded = PiJson.encodeToString(AssistantMessageEvent.serializer(), event)

        assertEquals(true, encoded.startsWith("{\"type\":\"done\",\"reason\":\"toolUse\""))
        assertEquals(event, PiJson.decodeFromString(AssistantMessageEvent.serializer(), encoded))
    }

    @Test
    fun usageTotalTokensMatchesPiComponentSum() {
        val usage = usage(input = 10, output = 4, cacheRead = 3, cacheWrite = 2)

        assertEquals(19, usage.totalTokens)
    }

    private fun assistantMessage(
        content: List<AssistantContent> = emptyList(),
        stopReason: StopReason = StopReason.STOP,
    ): AssistantMessage =
        AssistantMessage(
            content = content,
            api = "openai-responses",
            provider = "openai",
            model = "gpt-test",
            usage = usage(input = 1, output = 2),
            stopReason = stopReason,
            timestamp = 100,
        )

    private fun usage(
        input: Long,
        output: Long,
        cacheRead: Long = 0,
        cacheWrite: Long = 0,
    ): Usage =
        Usage(
            input = input,
            output = output,
            cacheRead = cacheRead,
            cacheWrite = cacheWrite,
            totalTokens = input + output + cacheRead + cacheWrite,
            cost = UsageCost(0.0, 0.0, 0.0, 0.0, 0.0),
        )
}
