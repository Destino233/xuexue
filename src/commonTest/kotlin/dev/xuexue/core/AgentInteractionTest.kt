package dev.xuexue.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentInteractionTest {
    @Test
    fun riskyOperationsRespectWorkspaceAndSessionPolicy() {
        assertFalse(AgentOperationKind.READ_FILE.requiresExplicitApproval(true, false, true, true))
        assertTrue(AgentOperationKind.READ_FILE.requiresExplicitApproval(false, false, true, true))
        assertTrue(AgentOperationKind.WRITE_FILE.requiresExplicitApproval(true, true, true, true))
        assertTrue(AgentOperationKind.WRITE_FILE.requiresExplicitApproval(true, false, false, true))
        assertFalse(AgentOperationKind.WRITE_FILE.requiresExplicitApproval(true, false, true, true))
        assertTrue(AgentOperationKind.RUN_SHELL.requiresExplicitApproval(true, false, true, false))
        assertTrue(AgentOperationKind.OUTSIDE_WORKSPACE.requiresExplicitApproval(true, false, true, true))
    }

    @Test
    fun sendAvailabilityExplainsTheFirstBlockingState() {
        assertTrue(agentSendAvailability(true, "model", "key", false) == AgentSendAvailability.OFFLINE)
        assertTrue(agentSendAvailability(false, "model", "key", true) == AgentSendAvailability.AWAITING_APPROVAL)
        assertTrue(agentSendAvailability(false, "", "key", false) == AgentSendAvailability.MODEL_REQUIRED)
        assertTrue(agentSendAvailability(false, "model", "", false) == AgentSendAvailability.API_KEY_REQUIRED)
        assertTrue(agentSendAvailability(false, "model", "key", false) == AgentSendAvailability.READY)
    }

    @Test
    fun piRuntimeExecutesToolResultsBeforeTheNextModelTurn() =
        runBlocking {
            val events = mutableListOf<AgentStreamEvent>()
            val tool =
                object : PiAgentTool {
                    override val definition = PiToolDefinition("read", "read a file", buildJsonObject {})

                    override suspend fun execute(
                        callId: String,
                        arguments: JsonObject,
                        onProgress: (String) -> Unit,
                    ): PiToolExecution {
                        onProgress("reading Note.md")
                        return PiToolExecution("note contents")
                    }
                }
            var calls = 0
            val model =
                object : PiAgentModel {
                    override suspend fun complete(
                        messages: List<PiAgentMessage>,
                        tools: List<PiToolDefinition>,
                        onEvent: (AgentStreamEvent) -> Unit,
                    ): PiModelResponse {
                        calls++
                        return if (calls == 1) {
                            assertEquals("user", messages.last().role)
                            assertEquals("read", tools.single().name)
                            PiModelResponse(toolCalls = listOf(PiToolCall("call-1", "read")))
                        } else {
                            assertEquals("tool", messages.last().role)
                            assertEquals("note contents", messages.last().content)
                            onEvent(AgentStreamEvent.TextDelta("Done"))
                            PiModelResponse(content = "Done", inputTokens = 12, outputTokens = 3)
                        }
                    }
                }

            val history =
                PiAgentRuntime().run(
                    initialMessages = listOf(PiAgentMessage("user", "Inspect Note.md")),
                    model = model,
                    tools = listOf(tool),
                    onEvent = events::add,
                )

            assertEquals(2, calls)
            assertEquals(listOf("user", "assistant", "tool", "assistant"), history.map(PiAgentMessage::role))
            assertTrue(events.any { it is AgentStreamEvent.ToolStarted && it.name == "read" })
            assertTrue(events.any { it is AgentStreamEvent.ToolFinished && it.output == "note contents" })
            assertTrue(events.any { it is AgentStreamEvent.Usage && it.inputTokens == 12 })
        }
}
