package dev.xuexue.pi.ai

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ProviderErrorTest {
    @Test
    fun preservesStatusAndJsonGatewayReason() {
        runTest {
            val response =
                HttpResponse(
                    status = 403,
                    headers = emptyMap(),
                    lines = flowOf("{\"error\":\"blocked by gateway WAF\"}"),
                )

            assertEquals(
                "Provider returned HTTP 403: {\"error\":\"blocked by gateway WAF\"}",
                providerError(response),
            )
        }
    }

    @Test
    fun appliesProviderPrefixWithoutDroppingBody() {
        runTest {
            val response =
                HttpResponse(
                    status = 403,
                    headers = emptyMap(),
                    lines = flowOf("{\"error\":\"blocked by gateway WAF\"}"),
                )

            assertEquals(
                "OpenAI API error (403): {\"error\":\"blocked by gateway WAF\"}",
                providerError(response, prefix = "OpenAI API error"),
            )
        }
    }

    @Test
    fun treatsEmptyBodiesAndEmptyJsonObjectsAsEmptyResponse() {
        runTest {
            assertEquals(
                "Provider returned HTTP 429: empty response",
                providerError(HttpResponse(429, emptyMap(), flowOf("", "  "))),
            )
            assertEquals(
                "Provider returned HTTP 429: empty response",
                providerError(HttpResponse(429, emptyMap(), flowOf("{}"))),
            )
        }
    }

    @Test
    fun truncatesLongBodiesWithDiagnosticCount() {
        val body = "x".repeat(MAX_PROVIDER_ERROR_BODY_CHARS + 50)

        val truncated = normalizeProviderErrorBody(body)

        assertContains(requireNotNull(truncated), "... [truncated 50 chars]")
        assertFalse(truncated == body)
    }

    @Test
    fun openAiCompletionsErrorEventRetainsGatewayBody() {
        runTest {
            val provider = OpenAiCompletionsProvider(ErrorTransport(403, "{\"error\":\"blocked by gateway WAF\"}"))
            val events =
                provider
                    .stream(
                        testModel(),
                        Context(messages = listOf(UserMessage(UserText("hello"), 1))),
                        SimpleStreamOptions(StreamOptions(apiKey = "key")),
                    ).asFlow()
                    .toList()

            val error = events.last() as AssistantMessageEvent.Error
            assertEquals(FailureStopReason.ERROR, error.reason)
            assertContains(error.error.errorMessage.orEmpty(), "403")
            assertContains(error.error.errorMessage.orEmpty(), "blocked by gateway WAF")
        }
    }

    @Test
    fun openRouterImageErrorEventRetainsGatewayBody() {
        runTest {
            val result =
                OpenRouterImageProvider(ErrorTransport(403, "{\"error\":\"blocked by gateway WAF\"}"))
                    .generateImages(
                        ImagesModel(
                            id = "image-model",
                            name = "Image model",
                            api = "openrouter-images",
                            provider = "openrouter",
                            baseUrl = "https://provider.test/v1",
                            input = listOf(ModelInput.TEXT),
                            output = listOf(ModelInput.IMAGE),
                            cost = ModelCost(0.0, 0.0, 0.0, 0.0),
                        ),
                        ImagesContext(listOf(TextContent("draw"))),
                        ImagesOptions(apiKey = "key"),
                    )

            assertEquals(ImagesStopReason.ERROR, result.stopReason)
            assertContains(result.errorMessage.orEmpty(), "403")
            assertContains(result.errorMessage.orEmpty(), "blocked by gateway WAF")
        }
    }

    private fun testModel(): Model {
        val model =
            Model(
                id = "test-model",
                name = "Test model",
                api = "openai-completions",
                provider = "openai",
                baseUrl = "https://provider.test/v1",
                reasoning = false,
                input = listOf(ModelInput.TEXT),
                cost = ModelCost(0.0, 0.0, 0.0, 0.0),
                contextWindow = 128_000,
                maxTokens = 4_096,
            )
        return model
    }

    private class ErrorTransport(
        private val status: Int,
        private val body: String,
    ) : HttpTransport {
        override suspend fun execute(request: HttpRequest): HttpResponse {
            val response =
                HttpResponse(
                    status = status,
                    headers = mapOf("content-type" to "application/json"),
                    lines = flowOf(body),
                )
            return response
        }
    }
}
