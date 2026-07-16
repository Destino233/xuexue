package dev.xuexue.pi.coding.session

import dev.xuexue.pi.ai.AgentMessage
import dev.xuexue.pi.ai.AssistantMessage

fun getLatestCompactionEntry(entries: List<SessionEntry>): CompactionEntry? =
    entries
        .asReversed()
        .firstNotNullOfOrNull { it as? CompactionEntry }

fun sessionEntryToContextMessages(entry: SessionEntry): List<AgentMessage> =
    when (entry) {
        is SessionMessageEntry -> {
            listOf(entry.message)
        }

        is CustomMessageEntry -> {
            listOf(
                createCustomMessage(
                    customType = entry.customType,
                    content = entry.content,
                    display = entry.display,
                    details = entry.details,
                    timestamp = entry.timestamp,
                ),
            )
        }

        is BranchSummaryEntry -> {
            if (entry.summary.isEmpty()) {
                emptyList()
            } else {
                listOf(createBranchSummaryMessage(entry.summary, entry.fromId, entry.timestamp))
            }
        }

        is CompactionEntry -> {
            listOf(createCompactionSummaryMessage(entry.summary, entry.tokensBefore, entry.timestamp))
        }

        else -> {
            emptyList()
        }
    }

fun buildContextEntries(entries: List<SessionEntry>): List<SessionEntry> =
    buildContextEntries(
        entries,
        entries.lastOrNull()?.id,
    )

fun buildContextEntries(
    entries: List<SessionEntry>,
    leafId: String?,
    byId: Map<String, SessionEntry>? = null,
): List<SessionEntry> {
    val path = buildSessionPath(entries, leafId, byId)
    val compaction = path.asReversed().firstNotNullOfOrNull { it as? CompactionEntry }
    val compactionIndex = compaction?.let { selected -> path.indexOfFirst { it.id == selected.id } } ?: -1
    return if (compaction == null || compactionIndex < 0) {
        path
    } else {
        val contextEntries = mutableListOf<SessionEntry>(compaction)
        var foundFirstKept = false
        for (index in 0 until compactionIndex) {
            val entry = path[index]
            if (entry.id == compaction.firstKeptEntryId) foundFirstKept = true
            if (foundFirstKept) contextEntries += entry
        }
        contextEntries += path.drop(compactionIndex + 1)
        contextEntries
    }
}

fun buildSessionContext(entries: List<SessionEntry>): SessionContext =
    buildSessionContext(
        entries,
        entries.lastOrNull()?.id,
    )

fun buildSessionContext(
    entries: List<SessionEntry>,
    leafId: String?,
    byId: Map<String, SessionEntry>? = null,
): SessionContext {
    val path = buildSessionPath(entries, leafId, byId)
    var thinkingLevel = "off"
    var model: SessionModel? = null
    path.forEach { entry ->
        when (entry) {
            is ThinkingLevelChangeEntry -> {
                thinkingLevel = entry.thinkingLevel
            }

            is ModelChangeEntry -> {
                model = SessionModel(entry.provider, entry.modelId)
            }

            is SessionMessageEntry -> {
                val assistant = entry.message as? AssistantMessage
                if (assistant != null) model = SessionModel(assistant.provider, assistant.model)
            }

            else -> {}
        }
    }
    return SessionContext(
        messages = buildContextEntries(entries, leafId, byId).flatMap(::sessionEntryToContextMessages),
        thinkingLevel = thinkingLevel,
        model = model,
    )
}

private fun buildSessionPath(
    entries: List<SessionEntry>,
    leafId: String?,
    byId: Map<String, SessionEntry>?,
): List<SessionEntry> {
    val index = byId ?: entries.associateBy(SessionEntry::id)
    return if (leafId == null) {
        emptyList()
    } else {
        var current = index[leafId] ?: entries.lastOrNull()
        val path = mutableListOf<SessionEntry>()
        while (current != null) {
            path += current
            current = current.parentId?.let(index::get)
        }
        path.reverse()
        path
    }
}
