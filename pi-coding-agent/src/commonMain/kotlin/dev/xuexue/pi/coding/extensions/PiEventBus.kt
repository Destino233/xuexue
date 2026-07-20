// Event handlers may throw arbitrary values; Pi isolates every handler failure.
@file:Suppress("TooGenericExceptionCaught")
@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package dev.xuexue.pi.coding.extensions

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference

/** Channel-based event bus exposed to headless extensions. */
interface PiEventBus {
    fun emit(
        channel: String,
        data: Any? = null,
    )

    fun on(
        channel: String,
        handler: (Any?) -> Unit,
    ): () -> Unit
}

/** Mutable event-bus controller owned by one Runtime/extension host. */
class PiEventBusController(
    private val onError: (channel: String, error: Throwable) -> Unit = { _, _ -> },
) : PiEventBus {
    private val listeners = AtomicReference<Map<String, List<Listener>>>(emptyMap())

    override fun emit(
        channel: String,
        data: Any?,
    ) {
        listeners.load()[channel].orEmpty().toList().forEach { listener ->
            if (!listener.active.load()) return@forEach
            try {
                listener.handler(data)
            } catch (error: Throwable) {
                onError(channel, error)
            }
        }
    }

    override fun on(
        channel: String,
        handler: (Any?) -> Unit,
    ): () -> Unit {
        val listener = Listener(handler)
        update { current ->
            current + (channel to (current[channel].orEmpty() + listener))
        }
        return {
            if (listener.active.compareAndSet(expectedValue = true, newValue = false)) {
                update { current ->
                    val remaining = current[channel].orEmpty().filterNot { it === listener }
                    if (remaining.isEmpty()) current - channel else current + (channel to remaining)
                }
            }
        }
    }

    fun clear() {
        listeners.exchange(emptyMap()).values.flatten().forEach { listener ->
            listener.active.store(false)
        }
    }

    private fun update(transform: (Map<String, List<Listener>>) -> Map<String, List<Listener>>) {
        while (true) {
            val current = listeners.load()
            val next = transform(current)
            if (listeners.compareAndSet(current, next)) return
        }
    }

    private class Listener(
        val handler: (Any?) -> Unit,
    ) {
        val active = AtomicBoolean(true)
    }
}
