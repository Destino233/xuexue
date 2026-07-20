package dev.xuexue.pi.coding.extensions

import kotlin.test.Test
import kotlin.test.assertEquals

class PiEventBusTest {
    @Test
    fun emitsInRegistrationOrderAndUnsubscribes() {
        val events = mutableListOf<String>()
        val bus = PiEventBusController()
        val first = bus.on("channel") { events += "first:$it" }
        val second = bus.on("channel") { events += "second:$it" }

        bus.emit("channel", "value")
        first()
        bus.emit("channel", "next")

        assertEquals(
            listOf("first:value", "second:value", "second:next"),
            events,
        )
        second()
    }

    @Test
    fun handlerFailureDoesNotPreventOtherHandlers() {
        val failures = mutableListOf<String>()
        val events = mutableListOf<String>()
        val bus =
            PiEventBusController { channel, error ->
                failures += "$channel:${error.message}"
            }
        val broken = bus.on("channel") { error("broken") }
        val healthy = bus.on("channel") { events += "$it" }

        bus.emit("channel", 7)

        assertEquals(listOf("channel:broken"), failures)
        assertEquals(listOf("7"), events)
        broken()
        healthy()
    }

    @Test
    fun clearInvalidatesExistingSubscriptions() {
        var calls = 0
        val bus = PiEventBusController()
        val unsubscribe = bus.on("channel") { calls++ }
        bus.clear()
        bus.emit("channel")
        assertEquals(0, calls)
        unsubscribe()
    }
}
