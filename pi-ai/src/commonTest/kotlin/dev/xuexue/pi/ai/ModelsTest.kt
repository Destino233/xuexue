package dev.xuexue.pi.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelsTest {
    @Test
    fun ordinaryReasoningModelsStopAtHigh() {
        val model = model(reasoning = true)

        assertEquals(
            listOf(
                ModelThinkingLevel.OFF,
                ModelThinkingLevel.MINIMAL,
                ModelThinkingLevel.LOW,
                ModelThinkingLevel.MEDIUM,
                ModelThinkingLevel.HIGH,
            ),
            getSupportedThinkingLevels(model),
        )
        assertEquals(ModelThinkingLevel.HIGH, clampThinkingLevel(model, ModelThinkingLevel.MAX))
    }

    @Test
    fun explicitMappingsCanExposeExtendedLevelsAndLeaveHoles() {
        val model =
            model(
                reasoning = true,
                thinkingLevelMap =
                    mapOf(
                        ModelThinkingLevel.XHIGH to null,
                        ModelThinkingLevel.MAX to "max",
                    ),
            )

        assertFalse(ModelThinkingLevel.XHIGH in getSupportedThinkingLevels(model))
        assertTrue(ModelThinkingLevel.MAX in getSupportedThinkingLevels(model))
        assertEquals(ModelThinkingLevel.MAX, clampThinkingLevel(model, ModelThinkingLevel.XHIGH))
    }

    @Test
    fun nonReasoningModelsOnlySupportOff() {
        val model = model(reasoning = false)

        assertEquals(listOf(ModelThinkingLevel.OFF), getSupportedThinkingLevels(model))
        assertEquals(ModelThinkingLevel.OFF, clampThinkingLevel(model, ModelThinkingLevel.HIGH))
    }

    @Test
    fun modelEqualityUsesProviderAndId() {
        val first = model(id = "same", provider = "one")
        val second = model(id = "same", provider = "one")
        val differentProvider = model(id = "same", provider = "two")

        assertTrue(modelsAreEqual(first, second))
        assertFalse(modelsAreEqual(first, differentProvider))
        assertFalse(modelsAreEqual(first, null))
    }

    @Test
    fun pinnedPiCatalogsContainAllGeneratedModels() {
        assertEquals(1_057, generatedPiModelCatalog().size)
        assertEquals(35, generatedPiImageModelCatalog().size)
        assertTrue(generatedPiModelCatalog().any { it.api == "mistral-conversations" })
        assertTrue(generatedPiModelCatalog().any { it.api == "bedrock-converse-stream" })
    }

    private fun model(
        id: String = "model",
        provider: String = "provider",
        reasoning: Boolean = false,
        thinkingLevelMap: Map<ModelThinkingLevel, String?>? = null,
    ): Model =
        Model(
            id = id,
            name = id,
            api = "test",
            provider = provider,
            baseUrl = "https://example.invalid",
            reasoning = reasoning,
            thinkingLevelMap = thinkingLevelMap,
            input = listOf(ModelInput.TEXT),
            cost = ModelCost(0.0, 0.0, 0.0, 0.0),
            contextWindow = 8_192,
            maxTokens = 2_048,
        )
}
