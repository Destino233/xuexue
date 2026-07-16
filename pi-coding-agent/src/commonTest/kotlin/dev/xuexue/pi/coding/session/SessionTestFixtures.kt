package dev.xuexue.pi.coding.session

import dev.xuexue.pi.ai.AssistantMessage
import dev.xuexue.pi.ai.StopReason
import dev.xuexue.pi.ai.TextContent
import dev.xuexue.pi.ai.Usage
import dev.xuexue.pi.ai.UsageCost
import dev.xuexue.pi.ai.UserMessage
import dev.xuexue.pi.ai.UserText

internal fun userMessage(
    text: String,
    timestamp: Long = 1,
): UserMessage = UserMessage(UserText(text), timestamp)

internal fun assistantMessage(
    text: String,
    timestamp: Long = 2,
    provider: String = "anthropic",
    model: String = "claude-test",
): AssistantMessage =
    AssistantMessage(
        content = listOf(TextContent(text)),
        api = "anthropic-messages",
        provider = provider,
        model = model,
        usage = Usage(1, 1, 0, 0, totalTokens = 2, cost = UsageCost(0.0, 0.0, 0.0, 0.0, 0.0)),
        stopReason = StopReason.STOP,
        timestamp = timestamp,
    )

internal fun messageEntry(
    id: String,
    parentId: String?,
    text: String,
    assistant: Boolean = false,
): SessionMessageEntry =
    SessionMessageEntry(
        id = id,
        parentId = parentId,
        timestamp = TEST_TIMESTAMP,
        message = if (assistant) assistantMessage(text) else userMessage(text),
    )

internal const val TEST_TIMESTAMP = "2025-01-01T00:00:00Z"
