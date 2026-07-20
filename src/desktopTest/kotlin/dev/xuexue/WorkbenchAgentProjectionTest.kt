package dev.xuexue

import dev.xuexue.pi.agent.AgentEvent
import dev.xuexue.pi.agent.AgentToolResult
import dev.xuexue.pi.ai.AssistantMessage
import dev.xuexue.pi.ai.AssistantMessageEvent
import dev.xuexue.pi.ai.StopReason
import dev.xuexue.pi.ai.TextContent
import dev.xuexue.pi.ai.ThinkingContent
import dev.xuexue.pi.ai.ToolCall
import dev.xuexue.pi.ai.ToolResultMessage
import dev.xuexue.pi.ai.Usage
import dev.xuexue.pi.ai.UsageCost
import dev.xuexue.pi.ai.UserMessage
import dev.xuexue.pi.ai.UserText
import dev.xuexue.platform.AgentMessage
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkbenchAgentProjectionTest {
    @Test
    fun projectsAssistantReasoningUsageAndQueuedSteering() {
        val session = AgentSessionState("session", "Session")
        session.messages += AgentMessage("steering_queued", "interrupt")
        val projection = WorkbenchAgentProjection(session, "assistant:1")
        val partial = assistant("hello", "thinking")

        projection.apply(AgentEvent.MessageStart(partial.copy(content = emptyList())))
        projection.apply(
            AgentEvent.MessageUpdate(
                partial,
                AssistantMessageEvent.TextDelta(1, "hello", partial),
            ),
        )
        projection.apply(AgentEvent.MessageEnd(partial))
        projection.apply(AgentEvent.MessageEnd(UserMessage(UserText("interrupt"), timestamp = 3)))

        assertEquals("hello", session.messages.single { it.role == "assistant" }.content)
        assertEquals("thinking", session.messages.single { it.role == "reasoning" }.content)
        assertEquals("noop\n{}", session.messages.single { it.role == "tool_call" }.content)
        assertEquals("steering", session.messages.single { it.content == "interrupt" }.role)
        assertEquals(12, session.inputTokens)
        assertEquals(5, session.outputTokens)
        assertEquals(0.25, session.costUsd)
    }

    @Test
    fun projectsToolLifecycleFailureAndFileDiff() {
        val session = AgentSessionState("session", "Session")
        val projection = WorkbenchAgentProjection(session, "assistant:1")
        val arguments = buildJsonObject { put("path", "note.md") }
        projection.apply(AgentEvent.ToolExecutionStart("call-1", "edit", arguments))
        projection.apply(
            AgentEvent.ToolExecutionUpdate(
                "call-1",
                "edit",
                arguments,
                AgentToolResult(listOf(TextContent("working")), buildJsonObject {}),
            ),
        )
        projection.apply(
            AgentEvent.MessageEnd(
                ToolResultMessage(
                    toolCallId = "call-1",
                    toolName = "edit",
                    content = listOf(TextContent("failed")),
                    details =
                        buildJsonObject {
                            put(
                                "fileDiff",
                                buildJsonObject {
                                    put("relativePath", "note.md")
                                    put("patch", "@@ -1 +1 @@")
                                },
                            )
                        },
                    isError = true,
                    timestamp = 4,
                ),
            ),
        )

        val call = session.messages.single { it.role == "tool_call" }
        val result = session.messages.single { it.role == "tool" }
        val diff = session.messages.single { it.role == "diff" }
        assertTrue(call.content.contains("working"))
        assertEquals("failed", result.content)
        assertTrue(result.error)
        assertTrue(diff.content.contains("note.md"))
        assertFalse(diff.error)
    }

    private fun assistant(
        text: String,
        thinking: String,
    ): AssistantMessage =
        AssistantMessage(
            content =
                listOf(
                    ThinkingContent(thinking),
                    TextContent(text),
                    ToolCall("unused", "noop", buildJsonObject {}),
                ),
            api = "openai-completions",
            provider = "openai",
            model = "mock",
            usage = Usage(12, 5, 0, 0, totalTokens = 17, cost = UsageCost(0.0, 0.25, 0.0, 0.0, 0.25)),
            stopReason = StopReason.STOP,
            timestamp = 2,
        )
}
