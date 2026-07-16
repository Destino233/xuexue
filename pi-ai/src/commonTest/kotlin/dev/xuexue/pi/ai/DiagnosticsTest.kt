package dev.xuexue.pi.ai

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiagnosticsTest {
    @Test
    fun nonThrowableUsesThrownValueShape() {
        val error = extractDiagnosticError("bad value")

        assertEquals("ThrownValue", error.name)
        assertEquals("bad value", error.message)
    }

    @Test
    fun throwableKeepsNameMessageStackAndCode() {
        val error = extractDiagnosticError(CodedException())

        assertEquals("CodedException", error.name)
        assertEquals("failed", error.message)
        assertEquals(JsonPrimitive("E_TEST"), error.code)
        assertTrue(error.stack.orEmpty().contains("CodedException"))
    }

    private class CodedException :
        IllegalStateException("failed"),
        DiagnosticCodeCarrier {
        override val diagnosticCode = JsonPrimitive("E_TEST")
    }
}
