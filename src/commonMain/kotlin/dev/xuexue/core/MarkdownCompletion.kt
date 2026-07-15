package dev.xuexue.core

enum class MarkdownCompletionKind {
    WIKI_LINK,
    BLOCK_REFERENCE,
    TAG,
    PROPERTY,
}

data class MarkdownCompletion(
    val kind: MarkdownCompletionKind,
    val label: String,
    val detail: String,
    val insertText: String,
    val replaceStart: Int,
    val replaceEnd: Int,
)

object MarkdownCompletionEngine {
    private val tagContext = Regex("(?:^|\\s)#([\\p{L}\\p{N}_/-]*)$")
    private val propertyContext = Regex("^\\s*([A-Za-z0-9_.-]*)$")
    private val defaultProperties = setOf("title", "aliases", "tags", "created", "updated", "status", "cssclasses")

    fun suggest(
        source: String,
        cursor: Int,
        notePaths: Collection<String>,
        tags: Collection<String>,
        blockIds: Collection<String>,
        properties: Collection<String>,
        limit: Int = 8,
    ): List<MarkdownCompletion> {
        val safeCursor = cursor.coerceIn(0, source.length)
        val before = source.substring(0, safeCursor)
        val wikiStart = before.lastIndexOf("[[")
        if (wikiStart >= 0 && before.lastIndexOf("]]") < wikiStart) {
            val typed = before.substring(wikiStart + 2)
            val blockMarker = typed.lastIndexOf("#^")
            if (blockMarker >= 0) {
                val document = typed.substring(0, blockMarker)
                val query = typed.substring(blockMarker + 2)
                return blockIds
                    .asSequence()
                    .filter { it.contains(query, ignoreCase = true) }
                    .sortedBy(String::lowercase)
                    .take(limit)
                    .map { id ->
                        MarkdownCompletion(
                            MarkdownCompletionKind.BLOCK_REFERENCE,
                            "^$id",
                            document.ifBlank { "当前工作区块" },
                            "[[$document#^$id]]",
                            wikiStart,
                            safeCursor,
                        )
                    }.toList()
            }
            val query = typed.substringBefore('|').trim()
            return notePaths
                .asSequence()
                .filter { path -> path.contains(query, ignoreCase = true) || path.substringAfterLast('/').substringBeforeLast('.').contains(query, ignoreCase = true) }
                .sortedBy(String::lowercase)
                .take(limit)
                .map { path ->
                    val target = path.removeSuffix(".md")
                    MarkdownCompletion(MarkdownCompletionKind.WIKI_LINK, target.substringAfterLast('/'), path, "[[$target]]", wikiStart, safeCursor)
                }.toList()
        }

        val lineStart = before.lastIndexOf('\n').let { if (it < 0) 0 else it + 1 }
        val lineBefore = before.substring(lineStart)
        tagContext.find(lineBefore)?.let { match ->
            val query = match.groupValues[1]
            val hashOffset = lineStart + match.range.last - query.length
            return tags
                .asSequence()
                .filter { it.contains(query, ignoreCase = true) }
                .sortedBy(String::lowercase)
                .take(limit)
                .map { tag -> MarkdownCompletion(MarkdownCompletionKind.TAG, "#$tag", "标签", "#$tag", hashOffset, safeCursor) }
                .toList()
        }

        if (isInsideFrontmatter(source, safeCursor)) {
            propertyContext.matchEntire(lineBefore)?.let { match ->
                val query = match.groupValues[1]
                return (defaultProperties + properties)
                    .asSequence()
                    .filter { it.contains(query, ignoreCase = true) }
                    .sortedBy(String::lowercase)
                    .take(limit)
                    .map { key -> MarkdownCompletion(MarkdownCompletionKind.PROPERTY, key, "属性", "$key: ", lineStart, safeCursor) }
                    .toList()
            }
        }
        return emptyList()
    }

    private fun isInsideFrontmatter(
        source: String,
        cursor: Int,
    ): Boolean {
        if (!source.startsWith("---")) return false
        val firstEnd = source.indexOf('\n')
        if (firstEnd < 0 || cursor <= firstEnd) return false
        val closing = source.indexOf("\n---", firstEnd)
        return closing < 0 || cursor <= closing
    }
}
