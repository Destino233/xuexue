package dev.xuexue.pi.coding.compaction

import dev.xuexue.pi.agent.StreamFunction
import dev.xuexue.pi.ai.AbortSignal
import dev.xuexue.pi.ai.AgentMessage
import dev.xuexue.pi.ai.Context
import dev.xuexue.pi.ai.Model
import dev.xuexue.pi.ai.SimpleStreamOptions
import dev.xuexue.pi.ai.StopReason
import dev.xuexue.pi.ai.StreamOptions
import dev.xuexue.pi.ai.TextContent
import dev.xuexue.pi.ai.ToolResultMessage
import dev.xuexue.pi.ai.UserBlocks
import dev.xuexue.pi.ai.UserMessage
import dev.xuexue.pi.coding.session.BranchSummaryEntry
import dev.xuexue.pi.coding.session.CompactionEntry
import dev.xuexue.pi.coding.session.CustomMessageEntry
import dev.xuexue.pi.coding.session.SessionEntry
import dev.xuexue.pi.coding.session.SessionManager
import dev.xuexue.pi.coding.session.SessionMessageEntry
import dev.xuexue.pi.coding.session.convertToLlm
import dev.xuexue.pi.coding.session.createBranchSummaryMessage
import dev.xuexue.pi.coding.session.createCompactionSummaryMessage
import dev.xuexue.pi.coding.session.createCustomMessage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Clock

data class BranchSummaryResult(
    val summary: String? = null,
    val readFiles: List<String>? = null,
    val modifiedFiles: List<String>? = null,
    val aborted: Boolean = false,
    val error: String? = null,
)

data class BranchSummaryDetails(
    val readFiles: List<String>,
    val modifiedFiles: List<String>,
) {
    fun toJson(): JsonObject =
        buildJsonObject {
            put("readFiles", JsonArray(readFiles.map(::JsonPrimitive)))
            put("modifiedFiles", JsonArray(modifiedFiles.map(::JsonPrimitive)))
        }
}

data class BranchPreparation(
    val messages: List<AgentMessage>,
    val fileOperations: FileOperations,
    val totalTokens: Long,
)

data class CollectEntriesResult(
    val entries: List<SessionEntry>,
    val commonAncestorId: String?,
)

data class GenerateBranchSummaryOptions(
    val model: Model,
    val apiKey: String,
    val headers: Map<String, String>? = null,
    val environment: Map<String, String>? = null,
    val signal: AbortSignal,
    val customInstructions: String? = null,
    val replaceInstructions: Boolean? = null,
    val reserveTokens: Long = DEFAULT_BRANCH_SUMMARY_RESERVE_TOKENS,
    val streamFunction: StreamFunction,
)

fun collectEntriesForBranchSummary(
    session: SessionManager,
    oldLeafId: String?,
    targetId: String,
): CollectEntriesResult {
    if (oldLeafId == null) return CollectEntriesResult(emptyList(), null)

    val oldPath = session.getBranch(oldLeafId).mapTo(linkedSetOf(), SessionEntry::id)
    val targetPath = session.getBranch(targetId)
    val commonAncestorId = targetPath.asReversed().firstOrNull { it.id in oldPath }?.id

    val entries = mutableListOf<SessionEntry>()
    var currentId: String? = oldLeafId
    while (currentId != null && currentId != commonAncestorId) {
        val entry = session.getEntry(currentId) ?: break
        entries += entry
        currentId = entry.parentId
    }
    entries.reverse()
    return CollectEntriesResult(entries, commonAncestorId)
}

fun prepareBranchEntries(
    entries: List<SessionEntry>,
    tokenBudget: Long = 0,
): BranchPreparation {
    val messages = mutableListOf<AgentMessage>()
    val fileOperations = createFileOperations()
    var totalTokens = 0L

    entries.forEach { entry ->
        if (entry is BranchSummaryEntry && entry.fromHook != true) {
            entry.details.branchSummaryDetails()?.let { details ->
                fileOperations.read += details.readFiles
                fileOperations.edited += details.modifiedFiles
            }
        }
    }

    for (index in entries.indices.reversed()) {
        val entry = entries[index]
        val message = entry.branchSummaryMessage()
        if (message != null) {
            extractFileOperations(message, fileOperations)
            val tokens = estimateTokens(message)
            val exceedsBudget = tokenBudget > 0 && totalTokens + tokens > tokenBudget
            if (exceedsBudget) {
                val isSummary = entry is CompactionEntry || entry is BranchSummaryEntry
                if (isSummary && totalTokens < tokenBudget * SUMMARY_FIT_RATIO) {
                    messages.add(0, message)
                    totalTokens += tokens
                }
                break
            } else {
                messages.add(0, message)
                totalTokens += tokens
            }
        }
    }

    return BranchPreparation(messages, fileOperations, totalTokens)
}

suspend fun generateBranchSummary(
    entries: List<SessionEntry>,
    options: GenerateBranchSummaryOptions,
): BranchSummaryResult {
    val contextWindow = options.model.contextWindow.takeIf { it > 0 } ?: DEFAULT_CONTEXT_WINDOW
    val tokenBudget = contextWindow - options.reserveTokens
    val preparation = prepareBranchEntries(entries, tokenBudget)
    return if (preparation.messages.isEmpty()) {
        BranchSummaryResult(summary = "No content to summarize")
    } else {
        generatePreparedBranchSummary(preparation, options)
    }
}

private suspend fun generatePreparedBranchSummary(
    preparation: BranchPreparation,
    options: GenerateBranchSummaryOptions,
): BranchSummaryResult {
    val conversationText = serializeConversation(convertToLlm(preparation.messages))
    val instructions = branchSummaryInstructions(options.customInstructions, options.replaceInstructions)
    val promptText = "<conversation>\n$conversationText\n</conversation>\n\n$instructions"
    val messages =
        listOf(
            UserMessage(
                content = UserBlocks(listOf(TextContent(promptText))),
                timestamp = Clock.System.now().toEpochMilliseconds(),
            ),
        )
    val response =
        options.streamFunction
            .stream(
                options.model,
                Context(systemPrompt = SUMMARIZATION_SYSTEM_PROMPT, messages = messages),
                SimpleStreamOptions(
                    stream =
                        StreamOptions(
                            apiKey = options.apiKey,
                            headers = options.headers,
                            env = options.environment,
                            signal = options.signal,
                            maxTokens = BRANCH_SUMMARY_MAX_TOKENS,
                        ),
                ),
            ).result()
    return when (response.stopReason) {
        StopReason.ABORTED -> BranchSummaryResult(aborted = true)
        StopReason.ERROR -> BranchSummaryResult(error = response.errorMessage ?: "Summarization failed")
        else -> completedBranchSummary(response.content.filterIsInstance<TextContent>(), preparation.fileOperations)
    }
}

private fun completedBranchSummary(
    content: List<TextContent>,
    fileOperations: FileOperations,
): BranchSummaryResult {
    var summary = BRANCH_SUMMARY_PREAMBLE + content.joinToString("\n", transform = TextContent::text)
    val files = computeFileLists(fileOperations)
    summary += formatFileOperations(files.readFiles, files.modifiedFiles)
    return BranchSummaryResult(
        summary = summary.ifEmpty { "No summary generated" },
        readFiles = files.readFiles,
        modifiedFiles = files.modifiedFiles,
    )
}

private fun SessionEntry.branchSummaryMessage(): AgentMessage? =
    when (this) {
        is SessionMessageEntry -> message.takeUnless { it is ToolResultMessage }
        is CustomMessageEntry -> createCustomMessage(customType, content, display, details, timestamp)
        is BranchSummaryEntry -> createBranchSummaryMessage(summary, fromId, timestamp)
        is CompactionEntry -> createCompactionSummaryMessage(summary, tokensBefore, timestamp)
        else -> null
    }

private fun kotlinx.serialization.json.JsonElement?.branchSummaryDetails(): BranchSummaryDetails? {
    val details = this as? JsonObject ?: return null
    return BranchSummaryDetails(
        readFiles = details.stringArray("readFiles"),
        modifiedFiles = details.stringArray("modifiedFiles"),
    )
}

private fun JsonObject.stringArray(name: String): List<String> =
    (this[name] as? JsonArray)
        ?.mapNotNull { element -> runCatching { element.jsonPrimitive.contentOrNull }.getOrNull() }
        .orEmpty()

private fun branchSummaryInstructions(
    customInstructions: String?,
    replaceInstructions: Boolean?,
): String =
    when {
        replaceInstructions == true && !customInstructions.isNullOrEmpty() -> customInstructions
        !customInstructions.isNullOrEmpty() -> "$BRANCH_SUMMARY_PROMPT\n\nAdditional focus: $customInstructions"
        else -> BRANCH_SUMMARY_PROMPT
    }

const val DEFAULT_BRANCH_SUMMARY_RESERVE_TOKENS: Long = 16_384

private const val DEFAULT_CONTEXT_WINDOW = 128_000L
private const val BRANCH_SUMMARY_MAX_TOKENS = 2_048
private const val SUMMARY_FIT_RATIO = 0.9

private const val BRANCH_SUMMARY_PREAMBLE =
    """The user explored a different conversation branch before returning here.
Summary of that exploration:

"""

private const val BRANCH_SUMMARY_PROMPT =
    """Create a structured summary of this conversation branch for context when returning later.

Use this EXACT format:

## Goal
[What was the user trying to accomplish in this branch?]

## Constraints & Preferences
- [Any constraints, preferences, or requirements mentioned]
- [Or "(none)" if none were mentioned]

## Progress
### Done
- [x] [Completed tasks/changes]

### In Progress
- [ ] [Work that was started but not finished]

### Blocked
- [Issues preventing progress, if any]

## Key Decisions
- **[Decision]**: [Brief rationale]

## Next Steps
1. [What should happen next to continue this work]

Keep each section concise. Preserve exact file paths, function names, and error messages."""
