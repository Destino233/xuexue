package dev.xuexue.pi.ai

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenAiCodexResponsesProviderTest {
    @Test
    @Suppress("LongMethod") // This fixture asserts the full Codex request/event contract in one pass.
    fun buildsCodexPayloadHeadersAndMapsSseEvents() =
        runTest {
            val transport =
                FakeCodexTransport(
                    listOf(FakeCodexResponse(200, emptyMap(), successLines())),
                )
            val provider = OpenAiCodexResponsesProvider(transport)
            val events =
                provider
                    .stream(
                        codexModel(),
                        Context(
                            systemPrompt = "You are a coding agent",
                            messages = listOf(UserMessage(UserText("hello"), 1)),
                            tools = listOf(testTool()),
                        ),
                        SimpleStreamOptions(
                            stream =
                                StreamOptions(
                                    apiKey = TEST_TOKEN,
                                    sessionId = "session-1",
                                ),
                            reasoning = ThinkingLevel.HIGH,
                        ),
                    ).asFlow()
                    .toList()

            val body =
                ProviderJson
                    .parseToJsonElement(requireNotNull(transport.requests.single().body))
                    .jsonObject
            assertEquals("gpt-5.4", body["model"]?.jsonPrimitive?.content)
            assertEquals(false, body["store"]?.jsonPrimitive?.content?.toBoolean())
            assertEquals("You are a coding agent", body["instructions"]?.jsonPrimitive?.content)
            assertEquals(
                "low",
                body["text"]
                    ?.jsonObject
                    ?.get("verbosity")
                    ?.jsonPrimitive
                    ?.content,
            )
            assertEquals(
                "auto",
                body["reasoning"]
                    ?.jsonObject
                    ?.get("summary")
                    ?.jsonPrimitive
                    ?.content,
            )
            assertEquals("auto", body["tool_choice"]?.jsonPrimitive?.content)
            assertTrue(
                body["include"]
                    ?.jsonArray
                    ?.any { it.jsonPrimitive.content == "reasoning.encrypted_content" } == true,
            )
            assertEquals("Bearer $TEST_TOKEN", transport.requests.single().headers["Authorization"])
            assertEquals("acct-test", transport.requests.single().headers["chatgpt-account-id"])
            assertEquals("pi", transport.requests.single().headers["originator"])
            assertEquals("session-1", transport.requests.single().headers["session-id"])
            assertEquals(
                "https://chatgpt.com/backend-api/codex/responses",
                transport.requests.single().url,
            )

            val done = events.last() as AssistantMessageEvent.Done
            assertEquals(SuccessfulStopReason.TOOL_USE, done.reason)
            assertEquals(
                "Hello",
                done.message.content
                    .filterIsInstance<TextContent>()
                    .single()
                    .text,
            )
            assertEquals(
                "lookup",
                done.message.content
                    .filterIsInstance<ToolCall>()
                    .single()
                    .name,
            )
            assertEquals(4, done.message.usage.input)
            assertEquals(3, done.message.usage.output)
        }

    @Test
    fun retriesTransientCodexResponsesAndPreservesRetryAfter() =
        runTest {
            val transport =
                FakeCodexTransport(
                    responses =
                        listOf(
                            FakeCodexResponse(429, mapOf("retry-after-ms" to "0"), listOf("rate limit")),
                            FakeCodexResponse(200, emptyMap(), successLines()),
                        ),
                )
            val events =
                OpenAiCodexResponsesProvider(transport)
                    .stream(
                        codexModel(),
                        Context(messages = listOf(UserMessage(UserText("hello"), 1))),
                        SimpleStreamOptions(
                            stream = StreamOptions(apiKey = TEST_TOKEN, maxRetries = 1),
                        ),
                    ).asFlow()
                    .toList()

            assertEquals(2, transport.requests.size)
            assertTrue(events.last() is AssistantMessageEvent.Done)
        }

    private fun codexModel(): Model =
        Model(
            id = "gpt-5.4",
            name = "GPT-5.4",
            api = "openai-codex-responses",
            provider = "openai-codex",
            baseUrl = "https://chatgpt.com/backend-api",
            reasoning = true,
            thinkingLevelMap = mapOf(ModelThinkingLevel.HIGH to "high"),
            input = listOf(ModelInput.TEXT, ModelInput.IMAGE),
            cost = ModelCost(1.0, 2.0, 3.0, 4.0),
            contextWindow = 128_000,
            maxTokens = 8_192,
        )

    private fun testTool(): Tool =
        Tool(
            name = "lookup",
            description = "Look up a value",
            parameters =
                buildJsonObject {
                    put("type", "object")
                    put(
                        "properties",
                        buildJsonObject {
                            put("query", buildJsonObject { put("type", "string") })
                        },
                    )
                },
        )

    private class FakeCodexTransport(
        responses: List<FakeCodexResponse>,
    ) : HttpTransport {
        private val remaining = responses.toMutableList()
        val requests = mutableListOf<HttpRequest>()

        override suspend fun execute(request: HttpRequest): HttpResponse {
            requests += request
            val response = remaining.removeFirst()
            return HttpResponse(response.status, response.headers, flowOf(*response.lines.toTypedArray()))
        }
    }

    private data class FakeCodexResponse(
        val status: Int,
        val headers: Map<String, String>,
        val lines: List<String>,
    )

    private companion object {
        const val TEST_TOKEN =
            "e30.eyJodHRwczovL2FwaS5vcGVuYWkuY29tL2F1dGgiOnsiY2hhdGdwdF9hY2NvdW50X2lkIjoiYWNjdC10ZXN0In19.sig"

        fun successLines(): List<String> =
            listOf(
                "data: {\"type\":\"response.created\",\"response\":{\"id\":\"resp-1\"}}",
                "",
                "data: {\"type\":\"response.output_text.delta\",\"output_index\":0,\"delta\":\"Hello\"}",
                "",
                wireLine(
                    "data: {\"type\":\"response.output_item.added\",\"output_index\":1,",
                    "\"item\":{\"type\":\"function_call\",\"id\":\"fc-1\",",
                    "\"call_id\":\"call-1\",\"name\":\"lookup\"}}",
                ),
                "",
                wireLine(
                    "data: {\"type\":\"response.function_call_arguments.delta\",",
                    "\"output_index\":1,\"call_id\":\"call-1|fc-1\",",
                    "\"delta\":\"{\\\"query\\\":\\\"x\\\"}\"}",
                ),
                "",
                wireLine(
                    "data: {\"type\":\"response.function_call_arguments.done\",",
                    "\"output_index\":1,\"call_id\":\"call-1|fc-1\",",
                    "\"arguments\":\"{\\\"query\\\":\\\"x\\\"}\"}",
                ),
                "",
                wireLine(
                    "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp-1\",",
                    "\"status\":\"completed\",\"usage\":{\"input_tokens\":4,",
                    "\"output_tokens\":3,\"total_tokens\":7}}}",
                ),
                "",
            )

        private fun wireLine(vararg parts: String): String = parts.joinToString("")
    }
}
