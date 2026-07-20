package dev.xuexue.pi.ai

import kotlin.time.Clock

private const val NON_VISION_USER_IMAGE_PLACEHOLDER = "(image omitted: model does not support images)"
private const val NON_VISION_TOOL_IMAGE_PLACEHOLDER = "(tool image omitted: model does not support images)"

/**
 * Applies Pi's history normalization before a provider-specific payload is
 * built. The session keeps the original structured messages; this function
 * only creates the model-facing view.
 */
internal fun transformMessagesForModel(
    messages: List<Message>,
    model: Model,
): List<Message> {
    val imageAware =
        messages.map { message ->
            when (message) {
                is UserMessage -> message.copy(content = downgradeUserImages(message.content, model))
                is ToolResultMessage -> message.copy(content = downgradeToolImages(message.content, model))
                is AssistantMessage -> message
            }
        }
    val transformed = imageAware.map { message -> transformAssistantMessage(message, model) }
    return insertSyntheticToolResults(transformed)
}

private fun downgradeUserImages(
    content: UserContent,
    model: Model,
): UserContent {
    if (ModelInput.IMAGE in model.input || content !is UserBlocks) return content
    return UserBlocks(replaceImagesWithPlaceholder(content.value, NON_VISION_USER_IMAGE_PLACEHOLDER))
}

private fun downgradeToolImages(
    content: List<ToolResultContent>,
    model: Model,
): List<ToolResultContent> =
    if (ModelInput.IMAGE in model.input) {
        content
    } else {
        replaceImagesWithPlaceholder(content, NON_VISION_TOOL_IMAGE_PLACEHOLDER)
    }

private fun <T : ContentBlock> replaceImagesWithPlaceholder(
    content: List<T>,
    placeholder: String,
): List<T> {
    val result = mutableListOf<T>()
    var previousWasPlaceholder = false
    content.forEach { block ->
        when (block) {
            is ImageContent -> {
                if (!previousWasPlaceholder) {
                    @Suppress("UNCHECKED_CAST")
                    result += TextContent(placeholder) as T
                }
                previousWasPlaceholder = true
            }

            is TextContent -> {
                result += block
                previousWasPlaceholder = block.text == placeholder
            }

            else -> {
                result += block
                previousWasPlaceholder = false
            }
        }
    }
    return result
}

private fun transformAssistantMessage(
    message: Message,
    model: Model,
): Message {
    if (message !is AssistantMessage) return message
    val sameModel =
        message.api == model.api &&
            message.provider == model.provider &&
            message.model == model.id
    val content = message.content.mapNotNull { block -> transformAssistantContent(block, sameModel) }
    return message.copy(content = content)
}

private fun transformAssistantContent(
    block: AssistantContent,
    sameModel: Boolean,
): AssistantContent? =
    when (block) {
        is ThinkingContent -> transformThinking(block, sameModel)
        is TextContent -> block
        is ToolCall -> if (sameModel || block.thoughtSignature == null) block else block.copy(thoughtSignature = null)
    }

private fun transformThinking(
    block: ThinkingContent,
    sameModel: Boolean,
): AssistantContent? =
    when {
        block.redacted == true && !sameModel -> null
        sameModel && block.thinkingSignature != null -> block
        block.thinking.isBlank() -> null
        sameModel -> block
        else -> TextContent(block.thinking)
    }

private fun insertSyntheticToolResults(messages: List<Message>): List<Message> {
    val result = mutableListOf<Message>()
    var pendingToolCalls = emptyList<ToolCall>()
    var existingToolResultIds = emptySet<String>()

    fun insertMissingResults() {
        pendingToolCalls
            .filter { it.id !in existingToolResultIds }
            .forEach { call ->
                result +=
                    ToolResultMessage(
                        toolCallId = call.id,
                        toolName = call.name,
                        content = listOf(TextContent("No result provided")),
                        isError = true,
                        timestamp = Clock.System.now().toEpochMilliseconds(),
                    )
            }
        pendingToolCalls = emptyList()
        existingToolResultIds = emptySet()
    }

    messages.forEach { message ->
        when (message) {
            is AssistantMessage -> {
                insertMissingResults()
                if (message.stopReason == StopReason.ERROR || message.stopReason == StopReason.ABORTED) return@forEach
                val calls = message.content.filterIsInstance<ToolCall>()
                pendingToolCalls = calls
                existingToolResultIds = emptySet()
                result += message
            }

            is ToolResultMessage -> {
                existingToolResultIds = existingToolResultIds + message.toolCallId
                result += message
            }

            is UserMessage -> {
                insertMissingResults()
                result += message
            }
        }
    }
    insertMissingResults()
    return result
}
