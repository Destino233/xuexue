package dev.xuexue.pi.coding.compaction

import dev.xuexue.pi.ai.AgentMessage
import dev.xuexue.pi.ai.AssistantMessage
import dev.xuexue.pi.ai.Context
import dev.xuexue.pi.ai.Model
import dev.xuexue.pi.ai.ModelThinkingLevel
import dev.xuexue.pi.ai.SimpleStreamOptions
import dev.xuexue.pi.ai.StopReason
import dev.xuexue.pi.ai.StreamOptions
import dev.xuexue.pi.ai.TextContent
import dev.xuexue.pi.ai.ThinkingLevel
import dev.xuexue.pi.ai.UserBlocks
import dev.xuexue.pi.ai.UserMessage
import dev.xuexue.pi.coding.session.convertToLlm
import kotlin.math.floor

suspend fun generateSummary(
    currentMessages: List<AgentMessage>,
    reserveTokens: Long,
    request: CompactionRequest,
    previousSummary: String? = null,
): String {
    val maxTokens = summaryTokenLimit(reserveTokens, request.model.maxTokens, SUMMARY_TOKEN_RATIO)
    var instructions = if (previousSummary != null) UPDATE_SUMMARIZATION_PROMPT else SUMMARIZATION_PROMPT
    if (request.customInstructions != null) {
        instructions += "\n\nAdditional focus: ${request.customInstructions}"
    }

    val conversation = serializeConversation(convertToLlm(currentMessages))
    val prompt =
        buildString {
            append("<conversation>\n")
            append(conversation)
            append("\n</conversation>\n\n")
            if (previousSummary != null) {
                append("<previous-summary>\n")
                append(previousSummary)
                append("\n</previous-summary>\n\n")
            }
            append(instructions)
        }
    val response =
        completeSummarization(
            request = request,
            prompt = prompt,
            maxTokens = maxTokens,
        )
    check(response.stopReason != StopReason.ERROR) {
        "Summarization failed: ${response.errorMessage ?: "Unknown error"}"
    }
    return response.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
}

suspend fun compact(
    preparation: CompactionPreparation,
    request: CompactionRequest,
): CompactionResult {
    val historySummary =
        if (preparation.isSplitTurn && preparation.turnPrefixMessages.isNotEmpty()) {
            val history =
                if (preparation.messagesToSummarize.isNotEmpty()) {
                    generateSummary(
                        currentMessages = preparation.messagesToSummarize,
                        reserveTokens = preparation.settings.reserveTokens,
                        request = request,
                        previousSummary = preparation.previousSummary,
                    )
                } else {
                    "No prior history."
                }
            val turnPrefix =
                generateTurnPrefixSummary(
                    messages = preparation.turnPrefixMessages,
                    reserveTokens = preparation.settings.reserveTokens,
                    request = request,
                )
            "$history\n\n---\n\n**Turn Context (split turn):**\n\n$turnPrefix"
        } else {
            generateSummary(
                currentMessages = preparation.messagesToSummarize,
                reserveTokens = preparation.settings.reserveTokens,
                request = request,
                previousSummary = preparation.previousSummary,
            )
        }
    val files = computeFileLists(preparation.fileOperations)
    val summary = historySummary + formatFileOperations(files.readFiles, files.modifiedFiles)
    check(preparation.firstKeptEntryId.isNotEmpty()) {
        "First kept entry has no UUID - session may need migration"
    }
    return CompactionResult(
        summary = summary,
        firstKeptEntryId = preparation.firstKeptEntryId,
        tokensBefore = preparation.tokensBefore,
        details = CompactionDetails(files.readFiles, files.modifiedFiles),
    )
}

private suspend fun generateTurnPrefixSummary(
    messages: List<AgentMessage>,
    reserveTokens: Long,
    request: CompactionRequest,
): String {
    val conversation = serializeConversation(convertToLlm(messages))
    val prompt = "<conversation>\n$conversation\n</conversation>\n\n$TURN_PREFIX_SUMMARIZATION_PROMPT"
    val response =
        completeSummarization(
            request = request,
            prompt = prompt,
            maxTokens = summaryTokenLimit(reserveTokens, request.model.maxTokens, TURN_PREFIX_TOKEN_RATIO),
        )
    check(response.stopReason != StopReason.ERROR) {
        "Turn prefix summarization failed: ${response.errorMessage ?: "Unknown error"}"
    }
    return response.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
}

private suspend fun completeSummarization(
    request: CompactionRequest,
    prompt: String,
    maxTokens: Int,
): AssistantMessage {
    val messages =
        listOf(
            UserMessage(
                content = UserBlocks(listOf(TextContent(prompt))),
                timestamp =
                    kotlin.time.Clock.System
                        .now()
                        .toEpochMilliseconds(),
            ),
        )
    val options =
        SimpleStreamOptions(
            stream =
                StreamOptions(
                    maxTokens = maxTokens,
                    signal = request.signal,
                    apiKey = request.apiKey,
                    headers = request.headers,
                    env = request.env,
                ),
            reasoning = request.thinkingLevel.toReasoning(request.model),
        )
    return request.streamFunction
        .stream(
            request.model,
            Context(systemPrompt = SUMMARIZATION_SYSTEM_PROMPT, messages = messages),
            options,
        ).result()
}

private fun summaryTokenLimit(
    reserveTokens: Long,
    modelMaxTokens: Long,
    ratio: Double,
): Int {
    val reserveLimit = floor(ratio * reserveTokens).toLong()
    val modelLimit = modelMaxTokens.takeIf { it > 0 } ?: Long.MAX_VALUE
    return minOf(reserveLimit, modelLimit, Int.MAX_VALUE.toLong()).toInt()
}

private fun ModelThinkingLevel?.toReasoning(model: Model): ThinkingLevel? =
    if (!model.reasoning) {
        null
    } else {
        when (this) {
            null,
            ModelThinkingLevel.OFF,
            -> null

            ModelThinkingLevel.MINIMAL -> ThinkingLevel.MINIMAL

            ModelThinkingLevel.LOW -> ThinkingLevel.LOW

            ModelThinkingLevel.MEDIUM -> ThinkingLevel.MEDIUM

            ModelThinkingLevel.HIGH -> ThinkingLevel.HIGH

            ModelThinkingLevel.XHIGH -> ThinkingLevel.XHIGH

            ModelThinkingLevel.MAX -> ThinkingLevel.MAX
        }
    }

private const val SUMMARY_TOKEN_RATIO = 0.8
private const val TURN_PREFIX_TOKEN_RATIO = 0.5

private const val SUMMARIZATION_PROMPT =
    """The messages above are a conversation to summarize. Create a structured context checkpoint summary that another LLM will use to continue the work.

Use this EXACT format:

## Goal
[What is the user trying to accomplish? Can be multiple items if the session covers different tasks.]

## Constraints & Preferences
- [Any constraints, preferences, or requirements mentioned by user]
- [Or "(none)" if none were mentioned]

## Progress
### Done
- [x] [Completed tasks/changes]

### In Progress
- [ ] [Current work]

### Blocked
- [Issues preventing progress, if any]

## Key Decisions
- **[Decision]**: [Brief rationale]

## Next Steps
1. [Ordered list of what should happen next]

## Critical Context
- [Any data, examples, or references needed to continue]
- [Or "(none)" if not applicable]

Keep each section concise. Preserve exact file paths, function names, and error messages."""

private const val UPDATE_SUMMARIZATION_PROMPT =
    """The messages above are NEW conversation messages to incorporate into the existing summary provided in <previous-summary> tags.

Update the existing structured summary with new information. RULES:
- PRESERVE all existing information from the previous summary
- ADD new progress, decisions, and context from the new messages
- UPDATE the Progress section: move items from "In Progress" to "Done" when completed
- UPDATE "Next Steps" based on what was accomplished
- PRESERVE exact file paths, function names, and error messages
- If something is no longer relevant, you may remove it

Use this EXACT format:

## Goal
[Preserve existing goals, add new ones if the task expanded]

## Constraints & Preferences
- [Preserve existing, add new ones discovered]

## Progress
### Done
- [x] [Include previously done items AND newly completed items]

### In Progress
- [ ] [Current work - update based on progress]

### Blocked
- [Current blockers - remove if resolved]

## Key Decisions
- **[Decision]**: [Brief rationale] (preserve all previous, add new)

## Next Steps
1. [Update based on current state]

## Critical Context
- [Preserve important context, add new if needed]

Keep each section concise. Preserve exact file paths, function names, and error messages."""

private const val TURN_PREFIX_SUMMARIZATION_PROMPT =
    """This is the PREFIX of a turn that was too large to keep. The SUFFIX (recent work) is retained.

Summarize the prefix to provide context for the retained suffix:

## Original Request
[What did the user ask for in this turn?]

## Early Progress
- [Key decisions and work done in the prefix]

## Context for Suffix
- [Information needed to understand the retained recent work]

Be concise. Focus on what's needed to understand the kept suffix."""
