package dev.xuexue.pi.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ImagesApiRegistryTest {
    @Test
    fun registersByApiAndReplacesThePreviousProvider() =
        kotlinx.coroutines.test.runTest {
            val first = imageProvider("first", "images-api", "first")
            val second = imageProvider("second", "images-api", "second")
            val registry = ImagesApiRegistry(listOf(first))

            assertEquals("first", registry.find("images-api")?.id)
            registry.register(second, sourceId = "plugin")

            assertEquals("second", registry.find("images-api")?.id)
            assertEquals("plugin", registry.sourceId("images-api"))
            assertEquals("second", registry.generateImages(testModel(), ImagesContext(emptyList())).model)
        }

    @Test
    fun unknownAndMismatchedApisProducePiErrors() =
        kotlinx.coroutines.test.runTest {
            val supportedApis = mutableSetOf<ImagesApi>("images-api")
            val provider = imageProvider("provider", supportedApis, "ok")
            val registry = ImagesApiRegistry(listOf(provider))
            assertFailsWith<IllegalStateException> {
                registry.generateImages(testModel(api = "missing"), ImagesContext(emptyList()))
            }

            supportedApis.clear()
            assertFailsWith<IllegalArgumentException> {
                registry.generateImages(testModel(), ImagesContext(emptyList()))
            }
        }

    @Test
    fun rejectsProvidersWithoutAnAdvertisedApi() {
        assertFailsWith<IllegalArgumentException> {
            ImagesApiRegistry(listOf(imageProvider("provider", emptySet(), "ok")))
        }
    }

    private fun imageProvider(
        id: String,
        api: ImagesApi,
        resultModel: String,
    ) = imageProvider(id, setOf(api), resultModel)

    private fun imageProvider(
        id: String,
        supportedApis: Set<ImagesApi>,
        resultModel: String,
    ) = object : PiImageProvider {
        override val id = id
        override val name = id
        override val supportedApis = supportedApis

        override suspend fun generateImages(
            model: ImagesModel,
            context: ImagesContext,
            options: ImagesOptions,
        ) = AssistantImages(
            api = model.api,
            provider = model.provider,
            model = resultModel,
            output = emptyList(),
            stopReason = ImagesStopReason.STOP,
            timestamp = 1,
        )
    }

    private fun testModel(api: ImagesApi = "images-api") =
        ImagesModel(
            id = "image-model",
            name = "Image model",
            api = api,
            provider = "provider",
            baseUrl = "https://example.test",
            input = listOf(ModelInput.TEXT),
            output = listOf(ModelInput.IMAGE),
            cost = ModelCost(0.0, 0.0, 0.0, 0.0),
        )
}
