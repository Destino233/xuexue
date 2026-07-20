package dev.xuexue.pi.ai

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LazyProviderTest {
    @Test
    fun implementationLoadsOnceAndStreamsThroughTheOuterStream() =
        runTest {
            var loadCount = 0
            val provider =
                LazyPiProvider(
                    id = "lazy",
                    name = "Lazy",
                    supportedApis = setOf("lazy-api"),
                ) {
                    loadCount++
                    testProvider()
                }
            val model = testModel("lazy-api").copy(provider = "lazy")

            provider
                .stream(model, Context(messages = listOf(UserMessage(UserText("one"), 1))), SimpleStreamOptions())
                .asFlow()
                .toList()
            provider
                .stream(model, Context(messages = listOf(UserMessage(UserText("two"), 2))), SimpleStreamOptions())
                .asFlow()
                .toList()

            assertEquals(1, loadCount)
        }

    @Test
    fun loadFailureIsAnErrorEventAndIsCached() =
        runTest {
            var loadCount = 0
            val provider =
                LazyPiProvider(
                    id = "broken",
                    name = "Broken",
                    supportedApis = setOf("broken-api"),
                ) {
                    loadCount++
                    error("setup failed")
                }
            val model = testModel("broken-api").copy(provider = "broken")

            val first =
                provider
                    .stream(model, Context(messages = emptyList()), SimpleStreamOptions())
                    .asFlow()
                    .toList()
            val second =
                provider
                    .stream(model, Context(messages = emptyList()), SimpleStreamOptions())
                    .asFlow()
                    .toList()

            assertEquals(1, loadCount)
            assertTrue(first.single() is AssistantMessageEvent.Error)
            assertTrue(second.single() is AssistantMessageEvent.Error)
            assertEquals("setup failed", (first.single() as AssistantMessageEvent.Error).error.errorMessage)
        }

    @Test
    fun imageImplementationIsLoadedOnlyOnce() =
        runTest {
            var loadCount = 0
            val provider =
                LazyPiImageProvider(
                    id = "lazy-images",
                    name = "Lazy images",
                    supportedApis = setOf("lazy-images-api"),
                ) {
                    loadCount++
                    object : PiImageProvider {
                        override val id = "lazy-images"
                        override val name = "Loaded images"
                        override val supportedApis = setOf("lazy-images-api")

                        override suspend fun generateImages(
                            model: ImagesModel,
                            context: ImagesContext,
                            options: ImagesOptions,
                        ) = AssistantImages(
                            api = model.api,
                            provider = model.provider,
                            model = model.id,
                            output = emptyList(),
                            stopReason = ImagesStopReason.STOP,
                            timestamp = 1,
                        )
                    }
                }
            val model =
                ImagesModel(
                    id = "image-model",
                    name = "Image model",
                    api = "lazy-images-api",
                    provider = "lazy-images",
                    baseUrl = "https://example.test",
                    input = listOf(ModelInput.TEXT),
                    output = listOf(ModelInput.IMAGE),
                    cost = ModelCost(0.0, 0.0, 0.0, 0.0),
                )

            provider.generateImages(model, ImagesContext(emptyList()), ImagesOptions())
            provider.generateImages(model, ImagesContext(emptyList()), ImagesOptions())

            assertEquals(1, loadCount)
        }

    private fun testProvider() =
        object : PiProvider {
            override val id = "lazy"
            override val name = "Loaded"
            override val supportedApis = setOf("lazy-api")

            override suspend fun stream(
                model: Model,
                context: Context,
                options: SimpleStreamOptions,
            ): AssistantMessageEventStream {
                val stream = createAssistantMessageEventStream()
                stream.push(
                    AssistantMessageEvent.Done(
                        reason = SuccessfulStopReason.STOP,
                        message =
                            AssistantMessage(
                                content = listOf(TextContent("ok")),
                                api = model.api,
                                provider = model.provider,
                                model = model.id,
                                usage = emptyUsage(),
                                stopReason = StopReason.STOP,
                                timestamp = 1,
                            ),
                    ),
                )
                return stream
            }
        }

    private fun testModel(api: Api) =
        Model(
            id = "model",
            name = "Model",
            api = api,
            provider = "provider",
            baseUrl = "https://example.test",
            reasoning = false,
            input = listOf(ModelInput.TEXT),
            cost = ModelCost(0.0, 0.0, 0.0, 0.0),
            contextWindow = 1000,
            maxTokens = 100,
            compat = buildJsonObject {},
        )
}
