package dev.xuexue.pi.ai

import kotlin.test.Test
import kotlin.test.assertEquals

class SanitizeUnicodeTest {
    @Test
    fun preservesValidSurrogatePairs() {
        assertEquals("Hello 🙈 👍 🚀", sanitizeSurrogates("Hello 🙈 👍 🚀"))
    }

    @Test
    fun removesUnpairedHighSurrogate() {
        val unpaired = 0xD83D.toChar()

        assertEquals("Text  here", sanitizeSurrogates("Text $unpaired here"))
    }

    @Test
    fun removesUnpairedLowSurrogate() {
        val unpaired = 0xDC00.toChar()

        assertEquals("beforeafter", sanitizeSurrogates("before${unpaired}after"))
    }

    @Test
    fun handlesAdjacentValidAndInvalidSurrogates() {
        val high = 0xD83D.toChar()
        val low = 0xDE80.toChar()

        assertEquals("x🚀y", sanitizeSurrogates("x$high$low${high}y$low"))
    }
}
