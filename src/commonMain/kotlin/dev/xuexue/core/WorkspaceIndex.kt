package dev.xuexue.core

data class IndexedNote(
    val relativePath: String,
    val title: String,
    val analysis: MarkdownAnalysis,
)

class WorkspaceIndex(notes: List<IndexedNote>) {
    private val notesByPath = notes.associateBy { normalize(it.relativePath) }
    private val notesByStem = notes.groupBy { stem(it.relativePath).lowercase() }

    fun resolve(sourcePath: String, target: String): IndexedNote? {
        val normalizedTarget = normalize(target.removeSuffix(".md") + ".md")
        val sourceParent = normalize(sourcePath).substringBeforeLast('/', "")
        val relativeCandidate = if (sourceParent.isBlank()) normalizedTarget else "$sourceParent/$normalizedTarget"
        return notesByPath[normalize(relativeCandidate)]
            ?: notesByPath[normalizedTarget]
            ?: notesByStem[stem(target).lowercase()]?.firstOrNull()
    }

    fun backlinks(targetPath: String): List<IndexedNote> = notesByPath.values.filter { note ->
        note.analysis.links.any { link -> resolve(note.relativePath, link.target)?.relativePath == targetPath }
    }.sortedBy { it.title.lowercase() }

    fun outgoing(sourcePath: String): List<IndexedNote> = notesByPath[normalize(sourcePath)]
        ?.analysis
        ?.links
        ?.mapNotNull { resolve(sourcePath, it.target) }
        ?.distinctBy { it.relativePath }
        .orEmpty()

    fun notePaths(): List<String> = notesByPath.values.map(IndexedNote::relativePath).sortedBy(String::lowercase)

    fun tags(): Set<String> = notesByPath.values.flatMap { it.analysis.tags }.toSet()

    fun blockIds(): Set<String> = notesByPath.values.flatMap { it.analysis.blockIds }.toSet()

    fun properties(): Set<String> = notesByPath.values.flatMap { it.analysis.properties }.toSet()

    private fun normalize(path: String): String = path.replace('\\', '/').removePrefix("./")
    private fun stem(path: String): String = normalize(path).substringAfterLast('/').substringBeforeLast('.')
}
