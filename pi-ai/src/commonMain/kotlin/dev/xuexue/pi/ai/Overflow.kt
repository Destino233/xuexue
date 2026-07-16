package dev.xuexue.pi.ai

private val overflowPatterns =
    listOf(
        "prompt is too long",
        "request_too_large",
        "input is too long for requested model",
        "exceeds the context window",
        "exceeds (?:the )?(?:model'?s )?maximum context length(?: of [\\d,]+ tokens?|\\s*\\([\\d,]+\\))",
        "input token count.*exceeds the maximum",
        "maximum prompt length is \\d+",
        "reduce the length of the messages",
        "maximum context length is \\d+ tokens",
        "exceeds (?:the )?maximum allowed input length of [\\d,]+ tokens?",
        "input \\(\\d+ tokens\\) is longer than the model'?s context length \\(\\d+ tokens\\)",
        "exceeds the limit of \\d+",
        "exceeds the available context size",
        "greater than the context length",
        "context window exceeds limit",
        "exceeded model token limit",
        "too large for model with \\d+ maximum context length",
        "prompt has [\\d,]+ tokens?, but the configured context size is [\\d,]+ tokens?",
        "model_context_window_exceeded",
        "prompt too long; exceeded (?:max )?context length",
        "context[_ ]length[_ ]exceeded",
        "too many tokens",
        "token limit exceeded",
        "^4(?:00|13)\\s*(?:status code)?\\s*\\(no body\\)",
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

private val nonOverflowPatterns =
    listOf(
        Regex("^(Throttling error|Service unavailable):", RegexOption.IGNORE_CASE),
        Regex("rate limit", RegexOption.IGNORE_CASE),
        Regex("too many requests", RegexOption.IGNORE_CASE),
    )

fun isContextOverflow(
    message: AssistantMessage,
    contextWindow: Long? = null,
): Boolean {
    val errorMessage = message.errorMessage
    val isErrorMessage = message.stopReason == StopReason.ERROR && errorMessage != null
    val matchesOverflowPattern =
        errorMessage != null &&
            nonOverflowPatterns.none { it.containsMatchIn(errorMessage) } &&
            overflowPatterns.any { it.containsMatchIn(errorMessage) }
    val errorOverflow = isErrorMessage && matchesOverflowPattern
    val inputTokens = message.usage.input + message.usage.cacheRead
    val silentOverflow =
        contextWindow != null && message.stopReason == StopReason.STOP && inputTokens > contextWindow
    val isLengthWithoutOutput = message.stopReason == StopReason.LENGTH && message.usage.output == 0L
    val lengthStopOverflow =
        contextWindow != null &&
            isLengthWithoutOutput &&
            inputTokens >= contextWindow * 0.99
    return errorOverflow || silentOverflow || lengthStopOverflow
}

fun getOverflowPatterns(): List<Regex> = overflowPatterns.toList()
