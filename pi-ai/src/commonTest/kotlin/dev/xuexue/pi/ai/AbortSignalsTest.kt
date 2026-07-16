package dev.xuexue.pi.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AbortSignalsTest {
    @Test
    fun noSignalsProducesNoSignal() {
        val combined = combineAbortSignals(listOf(null, null))

        assertNull(combined.signal)
        combined.cleanup()
    }

    @Test
    fun oneSignalIsReturnedWithoutWrapping() {
        val controller = AbortController()

        val combined = combineAbortSignals(listOf(null, controller.signal))

        assertSame(controller.signal, combined.signal)
    }

    @Test
    fun firstAbortReasonWins() {
        val first = AbortController()
        val second = AbortController()
        val combined = combineAbortSignals(listOf(first.signal, second.signal))

        second.abort("second")
        first.abort("first")

        val signal = requireNotNull(combined.signal)
        assertTrue(signal.isAborted)
        assertEquals("second", signal.reason)
    }

    @Test
    fun cleanupRemovesCombinedListeners() {
        val first = AbortController()
        val second = AbortController()
        val combined = combineAbortSignals(listOf(first.signal, second.signal))

        combined.cleanup()
        first.abort("after cleanup")

        assertFalse(combined.signal?.isAborted == true)
    }

    @Test
    fun alreadyAbortedSignalAbortsImmediately() {
        val first = AbortController()
        val second = AbortController()
        first.abort("already")

        val combined = combineAbortSignals(listOf(first.signal, second.signal))

        val signal = requireNotNull(combined.signal)
        assertTrue(signal.isAborted)
        assertEquals("already", signal.reason)
    }
}
