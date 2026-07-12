package dev.xuexue.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MarkdownParserTest {
    @Test
    fun extractsKnowledgeMetadata() {
        val analysis = MarkdownParser.analyze(
            """
            # Project
            See [[Notes/Idea|the idea]] and [[Book#chapter]].
            #learning #kotlin/mpp
            """.trimIndent(),
        )

        assertEquals(listOf(Heading(1, "Project", 1)), analysis.headings)
        assertEquals(listOf("Notes/Idea", "Book"), analysis.links.map { it.target })
        assertEquals(setOf("learning", "kotlin/mpp"), analysis.tags)
    }

    @Test
    fun extractsFrontmatterPropertiesAndBlockIds() {
        val analysis = MarkdownParser.analyze("---\ntitle: Note\naliases: [N]\n---\nParagraph ^stable-block\n")

        assertEquals(setOf("title", "aliases"), analysis.properties)
        assertEquals(setOf("stable-block"), analysis.blockIds)
    }

    @Test
    fun indexResolvesRelativeLinksAndBacklinks() {
        val source = IndexedNote(
            "folder/source.md",
            "Source",
            MarkdownParser.analyze("[[target]]"),
        )
        val target = IndexedNote("folder/target.md", "Target", MarkdownParser.analyze("empty"))
        val index = WorkspaceIndex(listOf(source, target))

        assertEquals(target, index.resolve(source.relativePath, "target"))
        assertEquals(listOf(source), index.backlinks(target.relativePath))
        assertNull(index.resolve(source.relativePath, "missing"))
    }
}
