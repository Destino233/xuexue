package dev.xuexue.core

import kotlinx.serialization.Serializable

@Serializable
enum class DocumentKind {
    MARKDOWN,
    TEXT,
    PDF,
    IMAGE,
    UNSUPPORTED,
}

@Serializable
enum class EditorMode {
    EDIT,
    SOURCE,
    READ,
}

@Serializable
enum class ToolWindow {
    FILES,
    SEARCH,
    BACKLINKS,
    OUTLINE,
    AGENT,
}

@Serializable
data class WorkspaceEntry(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val children: List<WorkspaceEntry> = emptyList(),
)

@Serializable
data class Heading(
    val level: Int,
    val text: String,
    val line: Int,
)

@Serializable
data class WikiLink(
    val target: String,
    val alias: String?,
    val line: Int,
)

@Serializable
data class MarkdownAnalysis(
    val headings: List<Heading>,
    val links: List<WikiLink>,
    val tags: Set<String>,
)

sealed interface MarkdownBlock {
    data class HeadingBlock(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class ListItem(val text: String, val ordered: Boolean) : MarkdownBlock
    data class CodeBlock(val language: String?, val code: String) : MarkdownBlock
    data class MathBlock(val source: String) : MarkdownBlock
    data object Divider : MarkdownBlock
}

object MarkdownParser {
    private val wikiLink = Regex("!?\\[\\[([^]#^|]+)(?:[#^][^]|]+)?(?:\\|([^]]+))?]]")
    private val tag = Regex("(?<![\\p{L}\\p{N}_#])#([\\p{L}\\p{N}_/-]+)")
    private val orderedItem = Regex("^\\s*\\d+[.)]\\s+(.+)$")

    fun analyze(source: String): MarkdownAnalysis {
        val headings = mutableListOf<Heading>()
        val links = mutableListOf<WikiLink>()
        val tags = linkedSetOf<String>()

        source.lineSequence().forEachIndexed { index, line ->
            val trimmed = line.trimStart()
            val hashes = trimmed.takeWhile { it == '#' }.length
            if (hashes in 1..6 && trimmed.getOrNull(hashes) == ' ') {
                headings += Heading(hashes, trimmed.drop(hashes + 1).trim(), index + 1)
            }
            wikiLink.findAll(line).forEach { match ->
                links += WikiLink(
                    target = match.groupValues[1].trim(),
                    alias = match.groupValues[2].trim().ifBlank { null },
                    line = index + 1,
                )
            }
            tag.findAll(line).forEach { match -> tags += match.groupValues[1] }
        }
        return MarkdownAnalysis(headings, links, tags)
    }

    fun blocks(source: String): List<MarkdownBlock> {
        val result = mutableListOf<MarkdownBlock>()
        val paragraph = mutableListOf<String>()
        val fenced = mutableListOf<String>()
        var fenceLanguage: String? = null
        var inFence = false
        var inMath = false

        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                result += MarkdownBlock.Paragraph(paragraph.joinToString(" ").trim())
                paragraph.clear()
            }
        }

        source.lineSequence().forEach { rawLine ->
            val line = rawLine.trimEnd()
            if (line.trimStart().startsWith("```")) {
                if (inFence) {
                    result += MarkdownBlock.CodeBlock(fenceLanguage, fenced.joinToString("\n"))
                    fenced.clear()
                    fenceLanguage = null
                    inFence = false
                } else {
                    flushParagraph()
                    inFence = true
                    fenceLanguage = line.trim().removePrefix("```").ifBlank { null }
                }
                return@forEach
            }
            if (inFence) {
                fenced += rawLine
                return@forEach
            }
            if (line.trim() == "$$") {
                if (inMath) {
                    result += MarkdownBlock.MathBlock(fenced.joinToString("\n"))
                    fenced.clear()
                    inMath = false
                } else {
                    flushParagraph()
                    inMath = true
                }
                return@forEach
            }
            if (inMath) {
                fenced += rawLine
                return@forEach
            }
            if (line.isBlank()) {
                flushParagraph()
                return@forEach
            }

            val trimmed = line.trimStart()
            val hashes = trimmed.takeWhile { it == '#' }.length
            when {
                hashes in 1..6 && trimmed.getOrNull(hashes) == ' ' -> {
                    flushParagraph()
                    result += MarkdownBlock.HeadingBlock(hashes, trimmed.drop(hashes + 1).trim())
                }
                trimmed == "---" || trimmed == "***" -> {
                    flushParagraph()
                    result += MarkdownBlock.Divider
                }
                trimmed.startsWith("> ") -> {
                    flushParagraph()
                    result += MarkdownBlock.Quote(trimmed.removePrefix("> "))
                }
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    flushParagraph()
                    result += MarkdownBlock.ListItem(trimmed.drop(2), ordered = false)
                }
                orderedItem.matches(trimmed) -> {
                    flushParagraph()
                    result += MarkdownBlock.ListItem(orderedItem.matchEntire(trimmed)!!.groupValues[1], ordered = true)
                }
                else -> paragraph += trimmed
            }
        }
        if (inFence) result += MarkdownBlock.CodeBlock(fenceLanguage, fenced.joinToString("\n"))
        else if (inMath) result += MarkdownBlock.MathBlock(fenced.joinToString("\n"))
        flushParagraph()
        return result
    }
}

