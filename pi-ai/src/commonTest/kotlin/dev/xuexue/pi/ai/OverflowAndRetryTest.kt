package dev.xuexue.pi.ai

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverflowAndRetryTest {
    @Test
    fun recognizesProviderOverflowMessages() {
        val messages =
            listOf(
                "prompt is too long: 213462 tokens > 200000 maximum",
                "Your input exceeds the context window of this model",
                "The input token count (12) exceeds the maximum number of tokens allowed (10)",
                "413 status code (no body)",
                "Prompt has 40,000 tokens, but the configured context size is 32,000 tokens",
            )

        assertTrue(messages.all { isContextOverflow(assistantError(it)) })
    }

    @Test
    fun excludesRateLimitWordingFromOverflowFallback() {
        val message = assistantError("Throttling error: Too many tokens, please wait before trying again")

        assertFalse(isContextOverflow(message))
    }

    @Test
    fun detectsSilentAndLengthStopOverflow() {
        assertTrue(isContextOverflow(assistant(StopReason.STOP, input = 101), contextWindow = 100))
        assertTrue(isContextOverflow(assistant(StopReason.LENGTH, input = 99), contextWindow = 100))
        assertFalse(isContextOverflow(assistant(StopReason.LENGTH, input = 98), contextWindow = 100))
    }

    @Test
    fun classifiesTransientProviderErrors() {
        assertTrue(isRetryableAssistantError(assistantError("overloaded_error")))
        assertTrue(isRetryableAssistantError(assistantError("524 status code (no body)")))
        assertTrue(isRetryableAssistantError(assistantError("socket connection was closed unexpectedly")))
        assertTrue(isRetryableAssistantError(assistantError("ResourceExhausted: request limit reached")))
    }

    @Test
    fun keepsQuotaAndSuccessfulMessagesNonRetryable() {
        assertFalse(isRetryableAssistantError(assistantError("429 quota exceeded")))
        assertFalse(isRetryableAssistantError(assistant(StopReason.STOP)))
    }

    private fun assistantError(error: String): AssistantMessage = assistant(StopReason.ERROR, errorMessage = error)

    private fun assistant(
        stopReason: StopReason,
        errorMessage: String? = null,
        input: Long = 0,
    ): AssistantMessage =
        AssistantMessage(
            content = emptyList(),
            api = "faux",
            provider = "faux",
            model = "test",
            usage =
                Usage(
                    input = input,
                    output = 0,
                    cacheRead = 0,
                    cacheWrite = 0,
                    totalTokens = input,
                    cost = UsageCost(0.0, 0.0, 0.0, 0.0, 0.0),
                ),
            stopReason = stopReason,
            errorMessage = errorMessage,
            timestamp = 1,
        )
}
