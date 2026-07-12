package dev.xuexue.core

import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownCompletionTest {
    @Test
    fun suggestsWikiLinks() {
        val source = "See [[pro"
        val result = suggestions(source, notePaths = listOf("notes/Project.md", "Archive.md"))

        assertEquals(MarkdownCompletionKind.WIKI_LINK, result.single().kind)
        assertEquals("[[notes/Project]]", result.single().insertText)
        assertEquals(4, result.single().replaceStart)
    }

    @Test
    fun suggestsBlockReferences() {
        val source = "[[Project#^sta"
        val result = suggestions(source, blockIds = listOf("stable-block", "other"))

        assertEquals(MarkdownCompletionKind.BLOCK_REFERENCE, result.single().kind)
        assertEquals("[[Project#^stable-block]]", result.single().insertText)
    }

    @Test
    fun suggestsTags() {
        val source = "Topic #kot"
        val result = suggestions(source, tags = listOf("kotlin", "learning"))

        assertEquals("#kotlin", result.single().insertText)
        assertEquals(6, result.single().replaceStart)
    }

    @Test
    fun suggestsFrontmatterPropertiesOnlyInsideFrontmatter() {
        val source = "---\ntit\n---\nbody"
        val result = suggestions(source, cursor = 7)

        assertEquals(MarkdownCompletionKind.PROPERTY, result.single().kind)
        assertEquals("title: ", result.single().insertText)
        assertEquals(emptyList(), suggestions(source, cursor = source.length))
    }

    private fun suggestions(
        source: String,
        cursor: Int = source.length,
        notePaths: Collection<String> = emptyList(),
        tags: Collection<String> = emptyList(),
        blockIds: Collection<String> = emptyList(),
    ) = MarkdownCompletionEngine.suggest(source, cursor, notePaths, tags, blockIds, emptyList())
}
