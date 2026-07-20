package dev.xuexue.pi.ai

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class DeferredToolsTest {
    @Test
    fun splitUsesTranscriptMarkersAndKeepsPreviouslyUsedToolsImmediate() {
        val context =
            Context(
                messages =
                    listOf(
                        AssistantMessage(
                            content = listOf(ToolCall("call-1", "used", buildJsonObject {})),
                            api = "test",
                            provider = "test",
                            model = "test",
                            usage = emptyUsage(),
                            stopReason = StopReason.TOOL_USE,
                            timestamp = 1,
                        ),
                        ToolResultMessage(
                            toolCallId = "call-1",
                            toolName = "used",
                            content = listOf(TextContent("ok")),
                            addedToolNames = listOf("late", "used"),
                            isError = false,
                            timestamp = 2,
                        ),
                    ),
                tools =
                    listOf(
                        Tool("used", "used", buildJsonObject { put("type", "object") }),
                        Tool("late", "late", buildJsonObject { put("type", "object") }),
                    ),
            )

        val placement = splitDeferredTools(context, enabled = true)

        assertEquals(listOf("used"), placement.immediate.map(Tool::name))
        assertEquals(
            listOf("late"),
            placement.deferred.values
                .map(Tool::name)
                .toList(),
        )
    }

    @Test
    fun splitDeduplicatesByNormalizedNameAndCanBeDisabled() {
        val context =
            Context(
                messages =
                    listOf(
                        ToolResultMessage(
                            toolCallId = "call",
                            toolName = "read",
                            content = listOf(TextContent("ok")),
                            addedToolNames = listOf("READ"),
                            isError = false,
                            timestamp = 1,
                        ),
                    ),
                tools =
                    listOf(
                        Tool("read", "first", buildJsonObject {}),
                        Tool("READ", "last", buildJsonObject {}),
                    ),
            )

        val disabled = splitDeferredTools(context, enabled = false) { it.lowercase() }
        val enabled = splitDeferredTools(context, enabled = true) { it.lowercase() }

        assertEquals(listOf("READ"), disabled.immediate.map(Tool::name))
        assertEquals(
            listOf("READ"),
            enabled.deferred.values
                .map(Tool::name)
                .toList(),
        )
    }
}
