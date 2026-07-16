package dev.xuexue.platform

import dev.xuexue.core.AgentStreamEvent
import dev.xuexue.core.PiAgentMessage
import dev.xuexue.core.PiAgentTool
import dev.xuexue.core.PiFileDiff
import dev.xuexue.core.PiModelResponse
import dev.xuexue.core.PiToolCall
import dev.xuexue.core.PiToolDefinition
import dev.xuexue.core.PiToolExecution
import dev.xuexue.pi.agent.AfterToolCallContext
import dev.xuexue.pi.agent.AgentContext
import dev.xuexue.pi.agent.AgentToolUpdateCallback
import dev.xuexue.pi.ai.AbortController
import dev.xuexue.pi.ai.AssistantMessage
import dev.xuexue.pi.ai.AssistantMessageEvent
import dev.xuexue.pi.ai.Context
import dev.xuexue.pi.ai.Model
import dev.xuexue.pi.ai.ModelCost
import dev.xuexue.pi.ai.ModelInput
import dev.xuexue.pi.ai.SimpleStreamOptions
import dev.xuexue.pi.ai.StopReason
import dev.xuexue.pi.ai.StreamOptions
import dev.xuexue.pi.ai.TextContent
import dev.xuexue.pi.ai.ThinkingContent
import dev.xuexue.pi.ai.Tool
import dev.xuexue.pi.ai.ToolCall
import dev.xuexue.pi.ai.Usage
import dev.xuexue.pi.ai.UsageCost
import dev.xuexue.pi.ai.UserMessage
import dev.xuexue.pi.ai.UserText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DesktopPiAgentAdapterTest {
    @Test
    fun convertsLegacyStreamingResponseToPiEvents() =
        runTest {
            val client = RecordingClient()
            val function = DesktopPiAgentAdapter(this, client)
            val stream =
                function.stream(
                    model(),
                    Context(
                        systemPrompt = "system",
                        messages = listOf(UserMessage(UserText("hello"), timestamp = 1)),
                        tools = listOf(Tool("echo", "Echo", buildJsonObject {})),
                    ),
                    SimpleStreamOptions(stream = StreamOptions(apiKey = "secret")),
                )
            val events = stream.asFlow().toList()
            val message = stream.result()

            assertEquals("secret", client.config?.apiKey)
            assertEquals(listOf("system", "user"), client.messages.map { it.role })
            assertEquals(listOf("echo"), client.tools.map { it.name })
            assertTrue(events.first() is AssistantMessageEvent.Start)
            assertTrue(events.any { it is AssistantMessageEvent.ThinkingDelta && it.delta == "think" })
            assertEquals(2, events.filterIsInstance<AssistantMessageEvent.TextDelta>().size)
            assertIs<AssistantMessageEvent.Done>(events.last())
            assertEquals(StopReason.TOOL_USE, message.stopReason)
            assertEquals(
                "think",
                message.content
                    .filterIsInstance<ThinkingContent>()
                    .single()
                    .thinking,
            )
            assertEquals(
                "hello",
                message.content
                    .filterIsInstance<TextContent>()
                    .single()
                    .text,
            )
            assertEquals(
                "call-1",
                message.content
                    .filterIsInstance<ToolCall>()
                    .single()
                    .id,
            )
            assertEquals(7L, message.usage.input)
            assertEquals(3L, message.usage.output)
        }

    @Test
    fun abortSignalCancelsLegacyClientAndTerminatesStream() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val client = BlockingClient(started)
            val controller = AbortController()
            val function = DesktopPiAgentAdapter(this, client)
            val stream =
                function.stream(
                    model(),
                    Context(messages = emptyList()),
                    SimpleStreamOptions(stream = StreamOptions(signal = controller.signal)),
                )
            val events = async { stream.asFlow().toList() }
            started.await()

            controller.abort()

            val terminal = assertIs<AssistantMessageEvent.Error>(events.await().last())
            assertEquals(StopReason.ABORTED, terminal.error.stopReason)
        }

    @Test
    fun adaptsLegacyToolProgressFailureAndFileDiff() =
        runTest {
            val updates = mutableListOf<String>()
            val legacy =
                object : PiAgentTool {
                    override val definition = PiToolDefinition("edit", "Edit", buildJsonObject {})

                    override suspend fun execute(
                        callId: String,
                        arguments: kotlinx.serialization.json.JsonObject,
                        onProgress: (String) -> Unit,
                    ): PiToolExecution {
                        onProgress("working")
                        return PiToolExecution(
                            output = "failed",
                            failed = true,
                            fileDiff = PiFileDiff("note.md", "@@ -1 +1 @@"),
                        )
                    }
                }
            val tool = adaptDesktopAgentTools(listOf(legacy)).single()
            val result =
                tool.execute(
                    "call-1",
                    buildJsonObject {},
                    null,
                    AgentToolUpdateCallback { update ->
                        updates +=
                            update.content
                                .filterIsInstance<TextContent>()
                                .single()
                                .text
                    },
                )
            val assistant = assistantWithToolCall()
            val call = assistant.content.filterIsInstance<ToolCall>().single()
            val after =
                desktopToolAfterCall(
                    AfterToolCallContext(
                        assistantMessage = assistant,
                        toolCall = call,
                        args = call.arguments,
                        result = result,
                        isError = false,
                        context = AgentContext("", mutableListOf(), listOf(tool)),
                    ),
                )

            assertEquals(listOf("working"), updates)
            assertEquals(true, after?.isError)
            assertEquals(
                "note.md",
                result.details.jsonObject["fileDiff"]
                    ?.jsonObject
                    ?.get("relativePath")
                    ?.jsonPrimitive
                    ?.content,
            )
        }

    private fun assistantWithToolCall(): AssistantMessage =
        AssistantMessage(
            content = listOf(ToolCall("call-1", "edit", buildJsonObject {})),
            api = "openai-completions",
            provider = "openai",
            model = "mock",
            usage = Usage(0, 0, 0, 0, totalTokens = 0, cost = UsageCost(0.0, 0.0, 0.0, 0.0, 0.0)),
            stopReason = StopReason.TOOL_USE,
            timestamp = 1,
        )

    private fun model(): Model =
        Model(
            id = "mock",
            name = "mock",
            api = "openai-completions",
            provider = "openai",
            baseUrl = "https://example.invalid/v1/chat/completions",
            reasoning = true,
            input = listOf(ModelInput.TEXT),
            cost = ModelCost(0.0, 0.0, 0.0, 0.0),
            contextWindow = 8_192,
            maxTokens = 2_048,
        )

    private class RecordingClient : AgentClient {
        var messages = emptyList<PiAgentMessage>()
        var tools = emptyList<PiToolDefinition>()
        var config: AgentProviderConfig? = null

        override suspend fun stream(
            messages: List<AgentMessage>,
            config: AgentProviderConfig,
            onEvent: (AgentStreamEvent) -> Unit,
        ) = Unit

        override suspend fun complete(
            messages: List<PiAgentMessage>,
            tools: List<PiToolDefinition>,
            config: AgentProviderConfig,
            onEvent: (AgentStreamEvent) -> Unit,
        ): PiModelResponse {
            this.messages = messages
            this.tools = tools
            this.config = config
            onEvent(AgentStreamEvent.ReasoningDelta("think"))
            onEvent(AgentStreamEvent.TextDelta("hel"))
            onEvent(AgentStreamEvent.TextDelta("lo"))
            return PiModelResponse(
                toolCalls = listOf(PiToolCall("call-1", "echo", buildJsonObject { put("value", "hello") })),
                inputTokens = 7,
                outputTokens = 3,
            )
        }
    }

    private class BlockingClient(
        private val started: CompletableDeferred<Unit>,
    ) : AgentClient {
        override suspend fun stream(
            messages: List<AgentMessage>,
            config: AgentProviderConfig,
            onEvent: (AgentStreamEvent) -> Unit,
        ) = Unit

        override suspend fun complete(
            messages: List<PiAgentMessage>,
            tools: List<PiToolDefinition>,
            config: AgentProviderConfig,
            onEvent: (AgentStreamEvent) -> Unit,
        ): PiModelResponse {
            started.complete(Unit)
            awaitCancellation()
        }
    }
}
