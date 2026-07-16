package dev.xuexue.pi.coding.compaction

import dev.xuexue.pi.ai.AgentMessage
import dev.xuexue.pi.ai.AssistantMessage
import dev.xuexue.pi.ai.Message
import dev.xuexue.pi.ai.TextContent
import dev.xuexue.pi.ai.ThinkingContent
import dev.xuexue.pi.ai.ToolCall
import dev.xuexue.pi.ai.ToolResultMessage
import dev.xuexue.pi.ai.UserBlocks
import dev.xuexue.pi.ai.UserMessage
import dev.xuexue.pi.ai.UserText
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class FileOperations(
    val read: MutableSet<String> = linkedSetOf(),
    val written: MutableSet<String> = linkedSetOf(),
    val edited: MutableSet<String> = linkedSetOf(),
)

data class FileOperationLists(
    val readFiles: List<String>,
    val modifiedFiles: List<String>,
)

fun createFileOperations(): FileOperations = FileOperations()

fun extractFileOperations(
    message: AgentMessage,
    fileOperations: FileOperations,
) {
    val assistant = message as? AssistantMessage ?: return
    assistant.content.filterIsInstance<ToolCall>().forEach { toolCall ->
        val path = (toolCall.arguments["path"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
        when (toolCall.name) {
            "read" -> fileOperations.read += path
            "write" -> fileOperations.written += path
            "edit" -> fileOperations.edited += path
        }
    }
}

fun computeFileLists(fileOperations: FileOperations): FileOperationLists {
    val modified = (fileOperations.edited + fileOperations.written).toSet()
    return FileOperationLists(
        readFiles = fileOperations.read.filterNot(modified::contains).sorted(),
        modifiedFiles = modified.sorted(),
    )
}

fun formatFileOperations(
    readFiles: List<String>,
    modifiedFiles: List<String>,
): String {
    val sections = mutableListOf<String>()
    if (readFiles.isNotEmpty()) sections += "<read-files>\n${readFiles.joinToString("\n")}\n</read-files>"
    if (modifiedFiles.isNotEmpty()) {
        sections += "<modified-files>\n${modifiedFiles.joinToString("\n")}\n</modified-files>"
    }
    return if (sections.isEmpty()) "" else "\n\n${sections.joinToString("\n\n")}"
}

fun serializeConversation(messages: List<Message>): String =
    buildList {
        messages.forEach { message ->
            when (message) {
                is UserMessage -> serializeUserMessage(message)?.let(::add)
                is AssistantMessage -> addAll(serializeAssistantMessage(message))
                is ToolResultMessage -> serializeToolResult(message)?.let(::add)
            }
        }
    }.joinToString("\n\n")

private fun serializeUserMessage(message: UserMessage): String? {
    val content =
        when (val userContent = message.content) {
            is UserText -> userContent.value
            is UserBlocks -> userContent.value.filterIsInstance<TextContent>().joinToString("") { it.text }
        }
    return content.takeIf(String::isNotEmpty)?.let { "[User]: $it" }
}

private fun serializeAssistantMessage(message: AssistantMessage): List<String> {
    val text = message.content.filterIsInstance<TextContent>().map(TextContent::text)
    val thinking = message.content.filterIsInstance<ThinkingContent>().map(ThinkingContent::thinking)
    val toolCalls =
        message.content.filterIsInstance<ToolCall>().map { toolCall ->
            val arguments = toolCall.arguments.entries.joinToString(", ") { (key, value) -> "$key=$value" }
            "${toolCall.name}($arguments)"
        }
    return buildList {
        if (thinking.isNotEmpty()) add("[Assistant thinking]: ${thinking.joinToString("\n")}")
        if (text.isNotEmpty()) add("[Assistant]: ${text.joinToString("\n")}")
        if (toolCalls.isNotEmpty()) add("[Assistant tool calls]: ${toolCalls.joinToString("; ")}")
    }
}

private fun serializeToolResult(message: ToolResultMessage): String? {
    val content = message.content.filterIsInstance<TextContent>().joinToString("") { it.text }
    return content.takeIf(String::isNotEmpty)?.let { "[Tool result]: ${truncateForSummary(it)}" }
}

private fun truncateForSummary(text: String): String {
    if (text.length <= TOOL_RESULT_MAX_CHARS) return text
    val truncatedCharacters = text.length - TOOL_RESULT_MAX_CHARS
    return text.take(TOOL_RESULT_MAX_CHARS) + "\n\n[... $truncatedCharacters more characters truncated]"
}

const val SUMMARIZATION_SYSTEM_PROMPT: String =
    """You are a context summarization assistant. Your task is to read a conversation between a user and an AI assistant, then produce a structured summary following the exact format specified.

Do NOT continue the conversation. Do NOT respond to any questions in the conversation. ONLY output the structured summary."""

private const val TOOL_RESULT_MAX_CHARS = 2_000
