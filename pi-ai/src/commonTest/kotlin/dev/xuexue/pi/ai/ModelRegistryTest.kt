package dev.xuexue.pi.ai

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ModelRegistryTest {
    @Test
    fun loadsCustomModelsOverridesAndUrlsWithoutTreatingUrlSlashesAsComments() =
        runTest {
            val auth = InMemoryAuthStore(ambientEnvironment = mapOf("CUSTOM_KEY" to "secret"))
            val registry =
                ModelRegistry(
                    authStore = auth,
                    environment = mapOf("CUSTOM_KEY" to "secret"),
                    modelsJson =
                        """
                        {
                          // A comment is legal in Pi models.json.
                          "providers": {
                            "custom": {
                              "baseUrl": "https://example.test/v1",
                              "api": "openai-completions",
                              "apiKey": "${'$'}CUSTOM_KEY",
                              "authHeader": true,
                              "models": [{"id":"review","reasoning":true,"contextWindow":4096}]
                            },
                            "openai": {
                              "modelOverrides": {"gpt-4o": {"name":"GPT override","maxTokens":2048}}
                            }
                          }
                        }
                        """.trimIndent(),
                )
            val custom = registry.find("custom", "review")
            assertNotNull(custom)
            assertEquals("https://example.test/v1", custom.baseUrl)
            assertEquals("GPT override", registry.find("openai", "gpt-4o")?.name)
            assertEquals(2_048, registry.find("openai", "gpt-4o")?.maxTokens)
            assertEquals(custom, registry.findExactReference("custom/review"))
            assertEquals("review", registry.resolvePattern("rev*").model?.id)

            val authResult = registry.getApiKeyAndHeaders(custom)
            assertTrue(authResult.ok)
            assertEquals("secret", authResult.apiKey)
            assertEquals("Bearer secret", authResult.headers?.get("Authorization"))
        }

    @Test
    fun loadsCustomModelsWithTrailingCommasAndCommentsLikePi() =
        runTest {
            val registry =
                ModelRegistry(
                    authStore = InMemoryAuthStore(),
                    environment = emptyMap(),
                    modelsJson =
                        """
                        {
                          // trailing commas are legal in Pi models.json
                          "providers": {
                            "custom": {
                              "baseUrl": "https://example.test/v1",
                              "api": "openai-completions",
                              "models": [{"id":"review","contextWindow":4096},],
                            },
                          },
                        }
                        """.trimIndent(),
                )
            assertEquals("https://example.test/v1", registry.find("custom", "review")?.baseUrl)
        }

    @Test
    fun ambiguousBareModelDoesNotResolveAndThinkingSuffixIsParsed() {
        val first = defaultModelCatalog().first().copy(id = "same")
        val second = defaultModelCatalog()[1].copy(id = "same")
        val registry = ModelRegistry(builtInModels = listOf(first, second))
        assertEquals(null, registry.findExactReference("same"))
        assertEquals("high", registry.resolvePattern("${first.provider}/same:high").thinkingLevel)
    }
}
