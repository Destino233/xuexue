package dev.xuexue.ui

import dev.xuexue.core.MarkdownEditing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DocumentViewsTest {
    @Test
    fun structuredNodeReplacementRejectsStaleRanges() {
        assertNull(
            MarkdownEditing.replaceNodeBody(
                source = "short\n",
                start = 7,
                prefix = "",
                suffix = "\n",
                currentBody = "old",
                replacementBody = "new",
            ),
        )
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
    }

    @Test
    fun structuredNodeReplacementPreservesSyntaxMarkers() {
        assertEquals(
            "before\n# New title\nafter\n",
            MarkdownEditing.replaceNodeBody(
                source = "before\n# Old title\nafter\n",
                start = 7,
                prefix = "# ",
                suffix = "\n",
                currentBody = "Old title",
                replacementBody = "New title",
            ),
        )
    }
}
