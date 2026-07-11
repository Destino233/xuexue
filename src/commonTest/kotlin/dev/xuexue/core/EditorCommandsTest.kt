package dev.xuexue.core

import kotlin.test.Test
import kotlin.test.assertEquals

class EditorCommandsTest {
    @Test
    fun wrapsSelection() {
        val result = EditorCommands.apply(EditorCommand.BOLD, "hello world", TextSelection(6, 11))
        assertEquals("hello **world**", result.text)
        assertEquals(TextSelection(8, 13), result.selection)
    }

    @Test
    fun prefixesEverySelectedLine() {
        val result = EditorCommands.apply(EditorCommand.BULLET_LIST, "one\ntwo", TextSelection(0, 7))
        assertEquals("- one\n- two", result.text)
    }
}

