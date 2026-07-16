package dev.xuexue.pi.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val CHARS_PER_TOKEN = 4L
private const val ESTIMATED_IMAGE_CHARS = 4_800L

fun calculateContextTokens(usage: Usage): Long =
    usage.totalTokens.takeIf { it != 0L }
        ?: (usage.input + usage.output + usage.cacheRead + usage.cacheWrite)

fun estimateTextTokens(text: String): Long = (text.length.toLong() + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN

fun estimateTextAndImageContentTokens(content: UserContent): Long =
    when (content) {
        is UserText -> {
            estimateTextTokens(content.value)
        }

        is UserBlocks -> {
            val chars = content.value.sumOf(::estimateTextAndImageBlockChars)
            (chars + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN
        }
    }

fun estimateMessageTokens(message: Message): Long =
    when (message) {
        is UserMessage -> {
            estimateTextAndImageContentTokens(message.content)
        }

        is ToolResultMessage -> {
            val chars = message.content.sumOf(::estimateTextAndImageBlockChars)
            (chars + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN
        }

        is AssistantMessage -> {
            val chars = message.content.sumOf(::estimateAssistantBlockChars)
            (chars + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN
        }
    }

fun estimateContextTokens(messages: List<Message>): ContextUsageEstimate = estimateMessages(messages)

fun estimateContextTokens(context: Context): ContextUsageEstimate {
    val estimate = estimateMessages(context.messages)
    if (estimate.lastUsageIndex != null) {
        val addedNames =
            context.messages
                .drop(estimate.lastUsageIndex + 1)
                .filterIsInstance<ToolResultMessage>()
                .flatMap { it.addedToolNames.orEmpty() }
                .toSet()
        val addedToolTokens = estimateToolsTokens(context.tools?.filter { it.name in addedNames })
        return estimate.copy(
            tokens = estimate.tokens + addedToolTokens,
            trailingTokens = estimate.trailingTokens + addedToolTokens,
        )
    }

    val systemPromptTokens = context.systemPrompt?.let(::estimateTextTokens) ?: 0L
    val prefixTokens = systemPromptTokens + estimateToolsTokens(context.tools)
    return estimate.copy(
        tokens = estimate.tokens + prefixTokens,
        trailingTokens = estimate.trailingTokens + prefixTokens,
    )
}

private fun estimateMessages(messages: List<Message>): ContextUsageEstimate {
    val usageInfo = getLastAssistantUsageInfo(messages)
    if (usageInfo != null) {
        val usageTokens = calculateContextTokens(usageInfo.message.usage)
        val trailingTokens = messages.drop(usageInfo.index + 1).sumOf(::estimateMessageTokens)
        return ContextUsageEstimate(
            tokens = usageTokens + trailingTokens,
            usageTokens = usageTokens,
            trailingTokens = trailingTokens,
            lastUsageIndex = usageInfo.index,
        )
    }

    val tokens = messages.sumOf(::estimateMessageTokens)
    return ContextUsageEstimate(tokens, usageTokens = 0, trailingTokens = tokens, lastUsageIndex = null)
}

private fun getLastAssistantUsageInfo(messages: List<Message>): AssistantUsageInfo? {
    var latestPrefixTimestamp = Long.MIN_VALUE
    var usageInfo: AssistantUsageInfo? = null
    messages.forEachIndexed { index, message ->
        if (message is AssistantMessage) {
            val usageAppliesToPrefix = message.timestamp >= latestPrefixTimestamp
            val hasUsableStopReason =
                message.stopReason != StopReason.ABORTED && message.stopReason != StopReason.ERROR
            if (usageAppliesToPrefix && hasUsableStopReason && calculateContextTokens(message.usage) > 0) {
                usageInfo = AssistantUsageInfo(message, index)
            }
        }
        latestPrefixTimestamp = maxOf(latestPrefixTimestamp, message.timestamp)
    }
    return usageInfo
}

private fun estimateToolsTokens(tools: List<Tool>?): Long {
    if (tools.isNullOrEmpty()) return 0
    val json =
        JsonArray(
            tools.map { tool ->
                buildJsonObject {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("parameters", tool.parameters)
                }
            },
        ).toString()
    return estimateTextTokens(json)
}

private fun estimateTextAndImageBlockChars(block: ContentBlock): Long =
    when (block) {
        is TextContent -> block.text.length.toLong()
        is ImageContent -> ESTIMATED_IMAGE_CHARS
        else -> error("Expected text or image content, got ${block::class.simpleName}")
    }

private fun estimateAssistantBlockChars(block: AssistantContent): Long =
    when (block) {
        is TextContent -> {
            block.text.length.toLong()
        }

        is ThinkingContent -> {
            block.thinking.length.toLong()
        }

        is ToolCall -> {
            block.name.length +
                block.arguments
                    .toString()
                    .length
                    .toLong()
        }
    }

private data class AssistantUsageInfo(
    val message: AssistantMessage,
    val index: Int,
)
