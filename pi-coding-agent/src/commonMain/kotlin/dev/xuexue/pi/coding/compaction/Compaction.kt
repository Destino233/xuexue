@file:Suppress("TooManyFunctions")

package dev.xuexue.pi.coding.compaction

import dev.xuexue.pi.ai.AgentMessage
import dev.xuexue.pi.ai.AssistantMessage
import dev.xuexue.pi.ai.StopReason
import dev.xuexue.pi.ai.Usage
import dev.xuexue.pi.ai.calculateContextTokens
import dev.xuexue.pi.ai.estimateMessageTokens
import dev.xuexue.pi.coding.session.BashExecutionMessage
import dev.xuexue.pi.coding.session.BranchSummaryMessage
import dev.xuexue.pi.coding.session.CompactionEntry
import dev.xuexue.pi.coding.session.CompactionSummaryMessage
import dev.xuexue.pi.coding.session.CustomMessage
import dev.xuexue.pi.coding.session.RawAgentMessage
import dev.xuexue.pi.coding.session.SessionEntry
import dev.xuexue.pi.coding.session.SessionMessageEntry
import dev.xuexue.pi.coding.session.buildSessionContext
import dev.xuexue.pi.coding.session.sessionEntryToContextMessages
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

data class CompactionSettings(
    val enabled: Boolean = true,
    val reserveTokens: Long = 16_384,
    val keepRecentTokens: Long = 20_000,
)

val DEFAULT_COMPACTION_SETTINGS = CompactionSettings()

data class CompactionDetails(
    val readFiles: List<String>,
    val modifiedFiles: List<String>,
)

data class CompactionContextUsageEstimate(
    val tokens: Long,
    val usageTokens: Long,
    val trailingTokens: Long,
    val lastUsageIndex: Int?,
)

data class CutPointResult(
    val firstKeptEntryIndex: Int,
    val turnStartIndex: Int,
    val isSplitTurn: Boolean,
)

data class CompactionPreparation(
    val firstKeptEntryId: String,
    val messagesToSummarize: List<AgentMessage>,
    val turnPrefixMessages: List<AgentMessage>,
    val isSplitTurn: Boolean,
    val tokensBefore: Long,
    val previousSummary: String?,
    val fileOperations: FileOperations,
    val settings: CompactionSettings,
)

fun getLastAssistantUsage(entries: List<SessionEntry>): Usage? =
    entries.asReversed().firstNotNullOfOrNull { entry ->
        (entry as? SessionMessageEntry)?.message?.validAssistantUsage()
    }

fun estimateContextTokens(messages: List<AgentMessage>): CompactionContextUsageEstimate {
    val usageIndex = messages.indexOfLast { it.validAssistantUsage() != null }
    if (usageIndex == -1) {
        val estimated = messages.sumOf(::estimateTokens)
        return CompactionContextUsageEstimate(estimated, 0, estimated, null)
    }

    val usageTokens = calculateContextTokens(requireNotNull(messages[usageIndex].validAssistantUsage()))
    val trailingTokens = messages.drop(usageIndex + 1).sumOf(::estimateTokens)
    return CompactionContextUsageEstimate(
        tokens = usageTokens + trailingTokens,
        usageTokens = usageTokens,
        trailingTokens = trailingTokens,
        lastUsageIndex = usageIndex,
    )
}

fun shouldCompact(
    contextTokens: Long,
    contextWindow: Long,
    settings: CompactionSettings,
): Boolean = settings.enabled && contextTokens > contextWindow - settings.reserveTokens

fun estimateTokens(message: AgentMessage): Long =
    when (message) {
        is dev.xuexue.pi.ai.Message -> estimateMessageTokens(message)
        is CustomMessage -> estimateUserContentTokens(message.content)
        is BashExecutionMessage -> estimateCharacters(message.command.length + message.output.length)
        is BranchSummaryMessage -> estimateCharacters(message.summary.length)
        is CompactionSummaryMessage -> estimateCharacters(message.summary.length)
        is RawAgentMessage -> 0
        else -> 0
    }

fun findTurnStartIndex(
    entries: List<SessionEntry>,
    entryIndex: Int,
    startIndex: Int,
): Int {
    for (index in entryIndex downTo startIndex) {
        if (isTurnStartEntry(entries[index])) return index
    }
    return -1
}

fun findCutPoint(
    entries: List<SessionEntry>,
    startIndex: Int,
    endIndex: Int,
    keepRecentTokens: Long,
): CutPointResult {
    val cutPoints = findValidCutPoints(entries, startIndex, endIndex)
    if (cutPoints.isEmpty()) return CutPointResult(startIndex, -1, isSplitTurn = false)

    val thresholdEntryIndex = findRecentTokenThreshold(entries, startIndex, endIndex, keepRecentTokens)
    var cutIndex =
        thresholdEntryIndex?.let { threshold -> cutPoints.firstOrNull { it >= threshold } }
            ?: cutPoints.first()

    while (cutIndex > startIndex) {
        val previousEntry = entries[cutIndex - 1]
        if (previousEntry is CompactionEntry || sessionEntryToContextMessages(previousEntry).isNotEmpty()) break
        cutIndex--
    }

    val startsTurn = isTurnStartEntry(entries[cutIndex])
    val turnStartIndex = if (startsTurn) -1 else findTurnStartIndex(entries, cutIndex, startIndex)
    return CutPointResult(
        firstKeptEntryIndex = cutIndex,
        turnStartIndex = turnStartIndex,
        isSplitTurn = !startsTurn && turnStartIndex != -1,
    )
}

fun prepareCompaction(
    pathEntries: List<SessionEntry>,
    settings: CompactionSettings,
): CompactionPreparation? =
    pathEntries
        .takeUnless { it.lastOrNull() is CompactionEntry }
        ?.let { prepareUncompactedEntries(it, settings) }

private fun prepareUncompactedEntries(
    pathEntries: List<SessionEntry>,
    settings: CompactionSettings,
): CompactionPreparation? {
    val previousCompactionIndex = pathEntries.indexOfLast { it is CompactionEntry }
    val previousCompaction = pathEntries.getOrNull(previousCompactionIndex) as? CompactionEntry
    val previousSummary = previousCompaction?.summary
    val boundaryStart = compactionBoundaryStart(pathEntries, previousCompactionIndex, previousCompaction)
    val boundaryEnd = pathEntries.size
    val tokensBefore = estimateContextTokens(buildSessionContext(pathEntries).messages).tokens
    val cutPoint = findCutPoint(pathEntries, boundaryStart, boundaryEnd, settings.keepRecentTokens)
    val firstKeptEntry = pathEntries.getOrNull(cutPoint.firstKeptEntryIndex) ?: return null
    val historyEnd = if (cutPoint.isSplitTurn) cutPoint.turnStartIndex else cutPoint.firstKeptEntryIndex
    val messagesToSummarize = messagesBetween(pathEntries, boundaryStart, historyEnd)
    val turnPrefixMessages =
        if (cutPoint.isSplitTurn) {
            messagesBetween(pathEntries, cutPoint.turnStartIndex, cutPoint.firstKeptEntryIndex)
        } else {
            emptyList()
        }
    val fileOperations = extractCompactionFileOperations(pathEntries, previousCompactionIndex, messagesToSummarize)
    turnPrefixMessages.forEach { extractFileOperations(it, fileOperations) }
    return CompactionPreparation(
        firstKeptEntryId = firstKeptEntry.id,
        messagesToSummarize = messagesToSummarize,
        turnPrefixMessages = turnPrefixMessages,
        isSplitTurn = cutPoint.isSplitTurn,
        tokensBefore = tokensBefore,
        previousSummary = previousSummary,
        fileOperations = fileOperations,
        settings = settings,
    ).takeIf {
        it.messagesToSummarize.isNotEmpty() || it.turnPrefixMessages.isNotEmpty()
    }
}

private fun AgentMessage.validAssistantUsage(): Usage? {
    val assistant = this as? AssistantMessage ?: return null
    return assistant.usage.takeIf {
        assistant.stopReason != StopReason.ABORTED &&
            assistant.stopReason != StopReason.ERROR &&
            calculateContextTokens(it) > 0
    }
}

private fun estimateUserContentTokens(content: dev.xuexue.pi.ai.UserContent): Long =
    when (content) {
        is dev.xuexue.pi.ai.UserText -> {
            estimateCharacters(content.value.length)
        }

        is dev.xuexue.pi.ai.UserBlocks -> {
            content.value
                .sumOf { block ->
                    when (block) {
                        is dev.xuexue.pi.ai.TextContent -> block.text.length.toLong()
                        is dev.xuexue.pi.ai.ImageContent -> ESTIMATED_IMAGE_CHARS
                    }
                }.let(::estimateCharacters)
        }
    }

private fun estimateCharacters(characters: Int): Long = estimateCharacters(characters.toLong())

private fun estimateCharacters(characters: Long): Long = (characters + CHARACTERS_PER_TOKEN - 1) / CHARACTERS_PER_TOKEN

private fun isCutPointMessage(message: AgentMessage): Boolean =
    when (message) {
        is dev.xuexue.pi.ai.UserMessage,
        is AssistantMessage,
        is BashExecutionMessage,
        is CustomMessage,
        is BranchSummaryMessage,
        is CompactionSummaryMessage,
        -> true

        is dev.xuexue.pi.ai.ToolResultMessage,
        is RawAgentMessage,
        -> false

        else -> false
    }

private fun isTurnStartMessage(message: AgentMessage): Boolean =
    when (message) {
        is dev.xuexue.pi.ai.UserMessage,
        is BashExecutionMessage,
        is CustomMessage,
        is BranchSummaryMessage,
        is CompactionSummaryMessage,
        -> true

        is AssistantMessage,
        is dev.xuexue.pi.ai.ToolResultMessage,
        is RawAgentMessage,
        -> false

        else -> false
    }

private fun isTurnStartEntry(entry: SessionEntry): Boolean {
    val isCompaction = entry is CompactionEntry
    val hasTurnStartMessage = sessionEntryToContextMessages(entry).any(::isTurnStartMessage)
    return !isCompaction && hasTurnStartMessage
}

private fun findRecentTokenThreshold(
    entries: List<SessionEntry>,
    startIndex: Int,
    endIndex: Int,
    keepRecentTokens: Long,
): Int? {
    var accumulatedTokens = 0L
    for (index in endIndex - 1 downTo startIndex) {
        accumulatedTokens += sessionEntryToContextMessages(entries[index]).sumOf(::estimateTokens)
        if (accumulatedTokens >= keepRecentTokens) return index
    }
    return null
}

private fun findValidCutPoints(
    entries: List<SessionEntry>,
    startIndex: Int,
    endIndex: Int,
): List<Int> =
    (startIndex until endIndex).filter { index ->
        val entry = entries[index]
        entry !is CompactionEntry && sessionEntryToContextMessages(entry).any(::isCutPointMessage)
    }

private fun compactionBoundaryStart(
    entries: List<SessionEntry>,
    previousCompactionIndex: Int,
    previousCompaction: CompactionEntry?,
): Int {
    if (previousCompaction == null) return 0
    val firstKeptIndex = entries.indexOfFirst { it.id == previousCompaction.firstKeptEntryId }
    return if (firstKeptIndex >= 0) firstKeptIndex else previousCompactionIndex + 1
}

private fun messagesBetween(
    entries: List<SessionEntry>,
    startIndex: Int,
    endIndex: Int,
): List<AgentMessage> =
    (startIndex until endIndex).mapNotNull { index ->
        val entry = entries[index]
        if (entry is CompactionEntry) null else sessionEntryToContextMessages(entry).firstOrNull()
    }

private fun extractCompactionFileOperations(
    entries: List<SessionEntry>,
    previousCompactionIndex: Int,
    messages: List<AgentMessage>,
): FileOperations {
    val fileOperations = createFileOperations()
    val previousCompaction = entries.getOrNull(previousCompactionIndex) as? CompactionEntry
    if (previousCompaction?.fromHook != true) inheritFileOperations(previousCompaction?.details, fileOperations)
    messages.forEach { extractFileOperations(it, fileOperations) }
    return fileOperations
}

private fun inheritFileOperations(
    details: kotlinx.serialization.json.JsonElement?,
    fileOperations: FileOperations,
) {
    val fields = details as? JsonObject ?: return
    fields.stringArray("readFiles").forEach { fileOperations.read += it }
    fields.stringArray("modifiedFiles").forEach { fileOperations.edited += it }
}

private fun JsonObject.stringArray(name: String): List<String> =
    (this[name] as? JsonArray)
        .orEmpty()
        .mapNotNull { it.jsonPrimitive.contentOrNull }

private const val CHARACTERS_PER_TOKEN = 4L
private const val ESTIMATED_IMAGE_CHARS = 4_800L
