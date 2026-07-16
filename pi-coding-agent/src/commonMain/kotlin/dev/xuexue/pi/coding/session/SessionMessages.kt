package dev.xuexue.pi.coding.session

import dev.xuexue.pi.ai.AgentMessage
import dev.xuexue.pi.ai.AssistantMessage
import dev.xuexue.pi.ai.Message
import dev.xuexue.pi.ai.TextContent
import dev.xuexue.pi.ai.ToolResultMessage
import dev.xuexue.pi.ai.UserBlocks
import dev.xuexue.pi.ai.UserContent
import dev.xuexue.pi.ai.UserContentSerializer
import dev.xuexue.pi.ai.UserMessage
import dev.xuexue.pi.ai.UserText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.time.Instant

const val COMPACTION_SUMMARY_PREFIX: String =
    "The conversation history before this point was compacted into the following summary:\n\n<summary>\n"
const val COMPACTION_SUMMARY_SUFFIX: String = "\n</summary>"
const val BRANCH_SUMMARY_PREFIX: String =
    "The following is a summary of a branch that this conversation came back from:\n\n<summary>\n"
const val BRANCH_SUMMARY_SUFFIX: String = "</summary>"

data class BashExecutionMessage(
    val command: String,
    val output: String,
    val exitCode: Int?,
    val cancelled: Boolean,
    val truncated: Boolean,
    val fullOutputPath: String? = null,
    override val timestamp: Long,
    val excludeFromContext: Boolean? = null,
) : AgentMessage

data class CustomMessage(
    val customType: String,
    val content: UserContent,
    val display: Boolean,
    val details: JsonElement? = null,
    override val timestamp: Long,
) : AgentMessage

data class BranchSummaryMessage(
    val summary: String,
    val fromId: String,
    override val timestamp: Long,
) : AgentMessage

data class CompactionSummaryMessage(
    val summary: String,
    val tokensBefore: Long,
    override val timestamp: Long,
) : AgentMessage

data class RawAgentMessage(
    val role: String,
    val payload: JsonObject,
    override val timestamp: Long,
) : AgentMessage

fun bashExecutionToText(message: BashExecutionMessage): String =
    buildString {
        append("Ran `")
        append(message.command)
        append("`\n")
        if (message.output.isNotEmpty()) {
            append("```\n")
            append(message.output)
            append("\n```")
        } else {
            append("(no output)")
        }
        if (message.cancelled) {
            append("\n\n(command cancelled)")
        } else if (message.exitCode != null && message.exitCode != 0) {
            append("\n\nCommand exited with code ")
            append(message.exitCode)
        }
        if (message.truncated && message.fullOutputPath != null) {
            append("\n\n[Output truncated. Full output: ")
            append(message.fullOutputPath)
            append(']')
        }
    }

fun createBranchSummaryMessage(
    summary: String,
    fromId: String,
    timestamp: String,
): BranchSummaryMessage = BranchSummaryMessage(summary, fromId, timestamp.toEpochMilliseconds())

fun createCompactionSummaryMessage(
    summary: String,
    tokensBefore: Long,
    timestamp: String,
): CompactionSummaryMessage = CompactionSummaryMessage(summary, tokensBefore, timestamp.toEpochMilliseconds())

fun createCustomMessage(
    customType: String,
    content: UserContent,
    display: Boolean,
    details: JsonElement?,
    timestamp: String,
): CustomMessage = CustomMessage(customType, content, display, details, timestamp.toEpochMilliseconds())

fun convertToLlm(messages: List<AgentMessage>): List<Message> =
    messages.mapNotNull { message ->
        when (message) {
            is BashExecutionMessage -> {
                if (message.excludeFromContext == true) {
                    null
                } else {
                    UserMessage(UserBlocks(listOf(TextContent(bashExecutionToText(message)))), message.timestamp)
                }
            }

            is CustomMessage -> {
                UserMessage(message.content, message.timestamp)
            }

            is BranchSummaryMessage -> {
                UserMessage(
                    UserBlocks(listOf(TextContent(BRANCH_SUMMARY_PREFIX + message.summary + BRANCH_SUMMARY_SUFFIX))),
                    message.timestamp,
                )
            }

            is CompactionSummaryMessage -> {
                UserMessage(
                    UserBlocks(
                        listOf(TextContent(COMPACTION_SUMMARY_PREFIX + message.summary + COMPACTION_SUMMARY_SUFFIX)),
                    ),
                    message.timestamp,
                )
            }

            is Message -> {
                message
            }

            is RawAgentMessage -> {
                null
            }

            else -> {
                null
            }
        }
    }

internal fun decodeUserContent(element: JsonElement?): UserContent =
    runCatching {
        SessionJson.decodeFromJsonElement(UserContentSerializer, normalizeUserContent(element))
    }.getOrElse { UserBlocks(emptyList()) }

private fun String.toEpochMilliseconds(): Long =
    runCatching { Instant.parse(this).toEpochMilliseconds() }
        .getOrDefault(0L)

internal fun AgentMessage.roleName(): String =
    when (this) {
        is UserMessage -> "user"
        is AssistantMessage -> "assistant"
        is ToolResultMessage -> "toolResult"
        is BashExecutionMessage -> "bashExecution"
        is CustomMessage -> "custom"
        is BranchSummaryMessage -> "branchSummary"
        is CompactionSummaryMessage -> "compactionSummary"
        is RawAgentMessage -> role
        else -> "unknown"
    }
