package dev.xuexue.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class DocumentSettingsTest {
    @Test
    fun wheelZoomMultiplierUsesAStableSettingsLabel() {
        assertEquals("1.00×", documentWheelZoomMultiplierLabel(1f))
        assertEquals("1.75×", documentWheelZoomMultiplierLabel(1.75f))
    }
}
