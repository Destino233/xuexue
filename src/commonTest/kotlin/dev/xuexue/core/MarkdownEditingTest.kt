package dev.xuexue.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MarkdownEditingTest {
    @Test
    fun parserPreservesStructuralSyntax() {
        val source =
            """
            ---
            tags: [test]
            ---
            # Heading
            > [!note]
            > Callout body
            ```kotlin
            val answer = 42
            ```
            """.trimIndent() + "\n"

        val nodes = MarkdownEditing.parseNodes(source)

        assertEquals(listOf("frontmatter", "heading", "callout", "quote", "code"), nodes.map { it.type })
        assertEquals("Heading", nodes[1].body)
        assertEquals("# Heading\n", nodes[1].source)
        assertEquals("val answer = 42\n", nodes.last().body)
    }

    @Test
    fun replacementRejectsStaleRangesAndKeepsMarkers() {
        assertNull(
            MarkdownEditing.replaceNodeBody(
                source = "# externally changed\n",
                start = 0,
                prefix = "# ",
                suffix = "\n",
                currentBody = "old",
                replacementBody = "new",
            ),
        )
        assertEquals(
            "# New title\n",
            MarkdownEditing.replaceNodeBody(
                source = "# Old title\n",
                start = 0,
                prefix = "# ",
                suffix = "\n",
                currentBody = "Old title",
                replacementBody = "New title",
            ),
        )
    }

    @Test
    fun parserKeepsBlankLinesWithThePrecedingEditableNode() {
        val source = "first\n\nsecond"

        val nodes = MarkdownEditing.parseNodes(source)

        assertEquals(2, nodes.size)
        assertEquals("first\n\n", nodes.first().source)
        assertEquals(0, nodes.first().start)
        assertEquals(7, nodes.first().end)
        assertEquals("second", nodes.last().source)
        assertEquals(7, nodes.last().start)
    }

    @Test
    fun parserKeepsAnAllBlankDocumentEditable() {
        val nodes = MarkdownEditing.parseNodes("\n\n")

        assertEquals(1, nodes.size)
        assertEquals("paragraph", nodes.single().type)
        assertEquals("\n\n", nodes.single().body)
    }

    @Test
    fun headingKeepsInsertedBlankLinesInItsEditableBody() {
        val node = MarkdownEditing.parseNodes("# Heading\n\n").single()

        assertEquals("Heading\n", node.body)
        assertEquals("# ", node.prefix)
        assertEquals("\n", node.suffix)
    }
}
