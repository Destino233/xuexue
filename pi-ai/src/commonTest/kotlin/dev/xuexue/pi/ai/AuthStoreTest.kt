package dev.xuexue.pi.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthStoreTest {
    @Test
    fun runtimeStoredAndEnvironmentApiKeysFollowPiPriority() =
        kotlinx.coroutines.test.runTest {
            val store =
                InMemoryAuthStore(
                    initial = mapOf("openai" to AuthCredential.ApiKey(key = "stored")),
                    ambientEnvironment = mapOf("OPENAI_API_KEY" to "environment"),
                )
            assertEquals("stored", store.getApiKey("openai"))

            store.setRuntimeApiKey("openai", "runtime")
            assertEquals("runtime", store.getApiKey("openai"))
            store.removeRuntimeApiKey("openai")
            store.remove("openai")
            assertEquals("environment", store.getApiKey("openai"))
            assertEquals(AuthStatusSource.ENVIRONMENT, store.getAuthStatus("openai").source)
        }

    @Test
    fun storedEnvironmentTemplateAndOAuthExpiryAreHandled() =
        kotlinx.coroutines.test.runTest {
            val store = InMemoryAuthStore()
            store.set(
                "custom",
                AuthCredential.ApiKey(
                    key = "prefix-\${CUSTOM_KEY}",
                    environment = mapOf("CUSTOM_KEY" to "value"),
                ),
            )
            assertEquals("prefix-value", store.getApiKey("custom"))
            store.set("oauth", AuthCredential.OAuth(accessToken = "token", expiresAtEpochMs = Long.MAX_VALUE))
            assertEquals("token", store.getApiKey("oauth", includeFallback = false))
            assertNull(store.getApiKey("missing", includeFallback = false))
            assertTrue(store.hasAuth("oauth"))
        }

    @Test
    fun bedrockEnvironmentChainDoesNotMistakeAccessKeyForBearerToken() =
        kotlinx.coroutines.test.runTest {
            val store =
                InMemoryAuthStore(
                    ambientEnvironment =
                        mapOf(
                            "AWS_ACCESS_KEY_ID" to "access",
                            "AWS_SECRET_ACCESS_KEY" to "secret",
                        ),
                )
            assertNull(store.getApiKey("amazon-bedrock"))
            assertTrue(store.hasAuth("amazon-bedrock"))
            assertEquals(AuthStatusSource.ENVIRONMENT, store.getAuthStatus("amazon-bedrock").source)

            val bearer = InMemoryAuthStore(ambientEnvironment = mapOf("AWS_BEARER_TOKEN_BEDROCK" to "token"))
            assertEquals("token", bearer.getApiKey("amazon-bedrock"))
        }
}
