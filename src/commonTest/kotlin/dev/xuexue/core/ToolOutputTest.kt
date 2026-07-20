package dev.xuexue.core

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolOutputTest {
    @Test
    fun headTruncationStopsAtCompleteLinesAndReportsByteLimit() {
        val result = truncateToolHead("a\nb\nc", maxLines = 10, maxBytes = 3)
        assertEquals("a\nb", result.content)
        assertTrue(result.truncated)
        assertEquals(ToolTruncatedBy.BYTES, result.truncatedBy)
        assertEquals(3, result.totalLines)
    }

    @Test
    fun tailTruncationKeepsMultibyteBoundariesAndLatestLines() {
        val result = truncateToolTail("old\n中间\n最后", maxLines = 2, maxBytes = 20)
        assertEquals("中间\n最后", result.content)

        val byteLimited = truncateToolTail("prefix\n这是很长的一行", maxLines = 2, maxBytes = 8)
        assertTrue(byteLimited.content.encodeToByteArray().size <= 8)
        assertTrue(byteLimited.content.isNotEmpty())
    }

    @Test
    fun mutationQueueSerializesSamePathButAllowsDifferentPaths() =
        runTest {
            val queue = FileMutationQueue()
            val events = mutableListOf<String>()
            val first =
                async {
                    queue.withPath("a.txt") {
                        events += "a-start"
                        delay(20)
                        events += "a-end"
                    }
                }
            val second =
                async {
                    queue.withPath("a.txt") {
                        events += "b-start"
                        events += "b-end"
                    }
                }
            first.await()
            second.await()
            assertEquals(listOf("a-start", "a-end", "b-start", "b-end"), events)
        }
}
