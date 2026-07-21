package dev.xuexue.pi.ai

import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CompatApiTest {
    @AfterTest
    fun resetGlobalProviders() {
        resetApiProviders()
    }

    @Test
    fun apiRegistryReplacesAndRemovesProvidersBySource() {
        val registry = ApiProviderRegistry()
        val original = RecordingApiProvider("test-api")
        val replacement = RecordingApiProvider("test-api")

        registry.register(original, sourceId = "original")
        registry.register(replacement, sourceId = "replacement")

        assertEquals(replacement, registry.find("test-api"))
        registry.unregisterBySource("replacement")
        assertNull(registry.find("test-api"))
    }

    @Test
    fun globalCompatStreamUsesEnvironmentKeyAndCompletes() =
        runTest {
            val provider = RecordingApiProvider("compat-test")
            registerApiProvider(provider, sourceId = "test")
            val model = compatModel(api = "compat-test")
            val message =
                complete(
                    model,
                    Context(messages = emptyList()),
                    SimpleStreamOptions(StreamOptions(env = mapOf("OPENAI_API_KEY" to "from-env"))),
                )

            assertEquals("from-env", requireNotNull(provider.options).stream.apiKey)
            assertEquals("complete", (message.content.single() as TextContent).text)
        }

    @Test
    fun explicitApiKeyWinsOverTheEnvironment() =
        runTest {
            val provider = RecordingApiProvider("compat-explicit")
            registerApiProvider(provider, sourceId = "test")
            val model = compatModel(api = "compat-explicit")

            stream(
                model,
                Context(messages = emptyList()),
                SimpleStreamOptions(StreamOptions(apiKey = "explicit", env = mapOf("OPENAI_API_KEY" to "from-env"))),
            ).result()

            assertEquals("explicit", requireNotNull(provider.options).stream.apiKey)
        }

    @Test
    fun missingApiProviderMatchesPiError() =
        runTest {
            val error =
                assertFailsWith<IllegalStateException> {
                    stream(compatModel(api = "missing-api"), Context(messages = emptyList()))
                }

            assertEquals("No API provider registered for api: missing-api", error.message)
        }

    private class RecordingApiProvider(
        override val api: Api,
    ) : ApiProvider {
        var options: SimpleStreamOptions? = null

        override suspend fun stream(
            model: Model,
            context: Context,
            options: SimpleStreamOptions,
        ): AssistantMessageEventStream {
            this.options = options
            return createAssistantMessageEventStream().also { output ->
                output.push(
                    AssistantMessageEvent.Done(
                        SuccessfulStopReason.STOP,
                        AssistantMessage(
                            content = listOf(TextContent("complete")),
                            api = model.api,
                            provider = model.provider,
                            model = model.id,
                            usage = Usage(0, 0, 0, 0, totalTokens = 0, cost = UsageCost(0.0, 0.0, 0.0, 0.0, 0.0)),
                            stopReason = StopReason.STOP,
                            timestamp = 1,
                        ),
                    ),
                )
            }
        }
    }

    private fun compatModel(api: Api): Model =
        Model(
            id = "compat-model",
            name = "Compatibility model",
            api = api,
            provider = "openai",
            baseUrl = "https://example.invalid",
            reasoning = false,
            input = listOf(ModelInput.TEXT),
            cost = ModelCost(0.0, 0.0, 0.0, 0.0),
            contextWindow = 8_192,
            maxTokens = 2_048,
        )
}
