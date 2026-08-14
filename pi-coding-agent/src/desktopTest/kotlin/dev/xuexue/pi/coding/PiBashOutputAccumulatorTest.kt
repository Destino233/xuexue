package dev.xuexue.pi.coding

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PiBashOutputAccumulatorTest {
    @Test
    fun decodesUtf8CharactersSplitAcrossChunks() {
        val accumulator = PiBashOutputAccumulator()
        val bytes = "€\n".toByteArray()
        accumulator.append(bytes.copyOfRange(0, 1))
        accumulator.append(bytes.copyOfRange(1, bytes.size))
        accumulator.finish()

        val snapshot = accumulator.snapshot()
        assertEquals("€\n", snapshot.content)
        assertFalse(snapshot.truncated)
    }

    @Test
    fun countsTrailingNewlineWithoutAddingAnExtraLine() {
        val accumulator = PiBashOutputAccumulator(maxLines = 2, maxBytes = 50 * 1024)
        accumulator.append("one\ntwo\nthree\n".toByteArray())
        accumulator.finish()

        val snapshot = accumulator.snapshot(persistIfTruncated = true)
        assertEquals(3, snapshot.totalLines)
        assertEquals(2, snapshot.outputLines)
        assertEquals(PiBashTruncatedBy.LINES, snapshot.truncatedBy)
        assertEquals("two\nthree", snapshot.content)
        val fullOutputPath = assertNotNull(snapshot.fullOutputPath)
        val fullOutput = Files.readString(Path.of(fullOutputPath))
        assertTrue(fullOutput.contains("one\ntwo\nthree\n"))
    }

    @Test
    fun sanitizesControlAndUnicodeFormatCharactersLikePi() {
        val accumulator = PiBashOutputAccumulator()
        // BEL (0x07), unit separator (0x1f), and Unicode format chars 0xfff9/0xfffb
        // are dropped; tab, LF, and CR are kept by Pi's shell.ts sanitize.
        val input = "a\u0007b\u001fc\t\n\u0000d\ufff9e\ufffbf\r\n"
        accumulator.append(input.toByteArray())
        accumulator.finish()

        val snapshot = accumulator.snapshot()
        assertEquals("abc\t\ndef\n", snapshot.content)
    }

    @Test
    fun truncatesOnlyTheTailOfAnOversizedLastLine() {
        val accumulator = PiBashOutputAccumulator(maxLines = 2_000, maxBytes = 4)
        accumulator.append("123456789".toByteArray())
        accumulator.finish()

        val snapshot = accumulator.snapshot()
        assertEquals("6789", snapshot.content)
        assertEquals(PiBashTruncatedBy.BYTES, snapshot.truncatedBy)
        assertTrue(snapshot.lastLinePartial)
        assertEquals(1, snapshot.outputLines)
    }
}
