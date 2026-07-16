package dev.xuexue.pi.ai

private val nonRetryableProviderLimitPattern =
    Regex(
        "GoUsageLimitError|FreeUsageLimitError|Monthly usage limit reached|available balance|" +
            "insufficient_quota|out of budget|quota exceeded|billing",
        RegexOption.IGNORE_CASE,
    )

private val retryableProviderErrorPattern =
    Regex(
        "overloaded|rate.?limit|too many requests|429|500|502|503|504|524|service.?unavailable|" +
            "server.?error|internal.?error|provider.?returned.?error|network.?error|connection.?error|" +
            "connection.?refused|connection.?lost|other side closed|fetch failed|upstream.?connect|" +
            "reset before headers|socket hang up|socket connection was closed|timed? out|timeout|terminated|" +
            "websocket.?closed|websocket.?error|ended without|stream ended before message_stop|" +
            "http2 request did not get a response|retry delay|you can retry your request|" +
            "try your request again|please retry your request|ResourceExhausted",
        RegexOption.IGNORE_CASE,
    )

fun isRetryableAssistantError(message: AssistantMessage): Boolean {
    if (message.stopReason != StopReason.ERROR || message.errorMessage == null) return false
    val errorMessage = message.errorMessage
    return !nonRetryableProviderLimitPattern.containsMatchIn(errorMessage) &&
        retryableProviderErrorPattern.containsMatchIn(errorMessage)
}
