package dev.xuexue.pi.agent

import dev.xuexue.pi.ai.AbortController
import dev.xuexue.pi.ai.AssistantMessageEvent
import dev.xuexue.pi.ai.Context
import dev.xuexue.pi.ai.FailureStopReason
import dev.xuexue.pi.ai.HttpRequest
import dev.xuexue.pi.ai.HttpResponse
import dev.xuexue.pi.ai.HttpTransport
import dev.xuexue.pi.ai.SuccessfulStopReason
import dev.xuexue.pi.ai.TextContent
import dev.xuexue.pi.ai.ThinkingContent
import dev.xuexue.pi.ai.Tool
import dev.xuexue.pi.ai.ToolCall
import dev.xuexue.pi.ai.UserMessage
import dev.xuexue.pi.ai.UserText
import kotlinx.coroutines.flow.Flow
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProxyTest {
    @Test
    fun serializesProxyRequestAndRebuildsPartialAssistantEvents() =
        runTest {
            val transport = ProxyTransport(proxyLines())
            val events =
                streamProxy(
                    model = testModel(),
                    context =
                        Context(
                            systemPrompt = "System",
                            messages = listOf(UserMessage(UserText("hello"), 1)),
                            tools = listOf(Tool("lookup", "Find it", buildJsonObject { put("type", "object") })),
                        ),
                    options =
                        ProxyStreamOptions(
                            authToken = "secret",
                            proxyUrl = "https://proxy.example",
                            temperature = 0.5,
                            maxTokens = 99,
                            sessionId = "session-1",
                            headers = mapOf("X-Trace" to "trace", "X-Removed" to null),
                        ),
                    httpTransport = transport,
                ).asFlow().toList()

            val request = requireNotNull(transport.request)
            assertEquals("POST", request.method)
            assertEquals("https://proxy.example/api/stream", request.url)
            assertEquals("Bearer secret", request.headers["Authorization"])
            val body =
                dev.xuexue.pi.ai.PiJson
                    .parseToJsonElement(requireNotNull(request.body))
                    .jsonObject
            assertEquals("mock", body["model"]!!.jsonObject["id"]!!.jsonPrimitive.content)
            assertEquals("System", body["context"]!!.jsonObject["systemPrompt"]!!.jsonPrimitive.content)
            assertEquals(
                "lookup",
                body["context"]!!
                    .jsonObject["tools"]!!
                    .jsonArray[0]
                    .jsonObject["name"]!!
                    .jsonPrimitive.content,
            )
            assertEquals("session-1", body["options"]!!.jsonObject["sessionId"]!!.jsonPrimitive.content)
            assertEquals(
                "trace",
                body["options"]!!
                    .jsonObject["headers"]!!
                    .jsonObject["X-Trace"]!!
                    .jsonPrimitive.content,
            )
            assertTrue(
                body["options"]!!
                    .jsonObject["headers"]!!
                    .jsonObject["X-Removed"]!!
                    .jsonPrimitive.isString
                    .not(),
            )
            assertFalse(request.body!!.contains("secret"))

            assertIs<AssistantMessageEvent.Start>(events[0])
            assertTrue(events.any { it is AssistantMessageEvent.ThinkingDelta && it.delta == "reason" })
            assertTrue(events.any { it is AssistantMessageEvent.ToolCallDelta && it.delta == "llo\"}" })
            val complete = assertIs<AssistantMessageEvent.Done>(events.last())
            assertEquals(SuccessfulStopReason.TOOL_USE, complete.reason)
            assertEquals(5, complete.message.usage.input)
            assertEquals(2, complete.message.usage.output)
            assertEquals("hello", assertIs<TextContent>(complete.message.content[0]).text)
            assertEquals("text-sig", assertIs<TextContent>(complete.message.content[0]).textSignature)
            assertEquals("reason", assertIs<ThinkingContent>(complete.message.content[1]).thinking)
            assertEquals("think-sig", assertIs<ThinkingContent>(complete.message.content[1]).thinkingSignature)
            val call = assertIs<ToolCall>(complete.message.content[2])
            assertEquals("call-1", call.id)
            assertEquals("lookup", call.name)
            assertEquals("hello", call.arguments["query"]!!.jsonPrimitive.content)
        }

    @Test
    fun returnsProxyResponseErrorWithoutLeakingTheToken() =
        runTest {
            val transport = ProxyTransport(lines = flowOf("{\"error\":\"token expired\"}"), status = 401)
            val events =
                streamProxy(
                    testModel(),
                    Context(messages = emptyList()),
                    ProxyStreamOptions(authToken = "secret", proxyUrl = "https://proxy.example"),
                    transport,
                ).asFlow().toList()

            val failure = assertIs<AssistantMessageEvent.Error>(events.single())
            assertEquals(FailureStopReason.ERROR, failure.reason)
            assertEquals("Proxy error: token expired", failure.error.errorMessage)
            assertFalse(requireNotNull(transport.request?.body).contains("secret"))
        }

    @Test
    fun returnsAbortedEventWithoutStartingTheRequest() =
        runTest {
            val controller = AbortController().also { it.abort() }
            val transport = ProxyTransport(proxyLines())
            val events =
                streamProxy(
                    testModel(),
                    Context(messages = emptyList()),
                    ProxyStreamOptions(
                        authToken = "secret",
                        proxyUrl = "https://proxy.example",
                        signal = controller.signal,
                    ),
                    transport,
                ).asFlow().toList()

            val failure = assertIs<AssistantMessageEvent.Error>(events.single())
            assertEquals(FailureStopReason.ABORTED, failure.reason)
            assertNull(transport.request)
        }

    private fun proxyLines(): Flow<String> =
        flowOf(
            "data: {\"type\":\"start\"}",
            "data: {\"type\":\"text_start\",\"contentIndex\":0}",
            "data: {\"type\":\"text_delta\",\"contentIndex\":0,\"delta\":\"hel\"}",
            "data: {\"type\":\"text_delta\",\"contentIndex\":0,\"delta\":\"lo\"}",
            "data: {\"type\":\"text_end\",\"contentIndex\":0,\"contentSignature\":\"text-sig\"}",
            "data: {\"type\":\"thinking_start\",\"contentIndex\":1}",
            "data: {\"type\":\"thinking_delta\",\"contentIndex\":1,\"delta\":\"reason\"}",
            "data: {\"type\":\"thinking_end\",\"contentIndex\":1,\"contentSignature\":\"think-sig\"}",
            "data: {\"type\":\"toolcall_start\",\"contentIndex\":2,\"id\":\"call-1\",\"toolName\":\"lookup\"}",
            "data: {\"type\":\"toolcall_delta\",\"contentIndex\":2,\"delta\":\"{\\\"query\\\":\\\"he\"}",
            "data: {\"type\":\"toolcall_delta\",\"contentIndex\":2,\"delta\":\"llo\\\"}\"}",
            "data: {\"type\":\"toolcall_end\",\"contentIndex\":2}",
            "data: {\"type\":\"done\",\"reason\":\"toolUse\",\"usage\":{\"input\":5,\"output\":2," +
                "\"cacheRead\":0,\"cacheWrite\":0,\"totalTokens\":7,\"cost\":{\"input\":0," +
                "\"output\":0,\"cacheRead\":0,\"cacheWrite\":0,\"total\":0}}}",
        )

    private class ProxyTransport(
        private val lines: Flow<String>,
        private val status: Int = 200,
    ) : HttpTransport {
        var request: HttpRequest? = null

        override suspend fun execute(request: HttpRequest): HttpResponse {
            this.request = request
            return HttpResponse(status, emptyMap(), lines)
        }
    }
}
