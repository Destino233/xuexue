// Cleanup callbacks may throw any Throwable; Pi keeps every failure while continuing cleanup.
@file:Suppress("TooGenericExceptionCaught")
@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package dev.xuexue.pi.ai

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference

/** Cleanup callback registered by a provider for resources shared by sessions. */
fun interface SessionResourceCleanup {
    fun cleanup(sessionId: String?)
}

/**
 * Raised after every registered cleanup callback has been given a chance to run.
 * The first failure is the primary cause and the remaining failures are retained
 * in [failures] so one broken provider cannot leak resources owned by another.
 */
class SessionResourceCleanupException(
    val failures: List<Throwable>,
) : RuntimeException("Failed to cleanup session resources (${failures.size} failure(s))") {
    init {
        failures.drop(1).forEach(::addSuppressed)
    }
}

/**
 * Process-wide cleanup registry matching Pi's session-resources module.
 *
 * Providers register once for resources such as connection pools or cached
 * WebSocket sessions. The returned handle is idempotent and should be called
 * when the provider is unloaded. Session disposal invokes [cleanup] and keeps
 * running callbacks even when one of them throws.
 */
object SessionResources {
    private val callbacks = AtomicReference<List<Registration>>(emptyList())

    fun register(cleanup: SessionResourceCleanup): () -> Unit {
        val registration = Registration(cleanup)
        update { current -> current + registration }
        return {
            if (registration.active.compareAndSet(expectedValue = true, newValue = false)) {
                update { current -> current.filterNot { it === registration } }
            }
        }
    }

    fun cleanup(sessionId: String? = null) {
        val failures = mutableListOf<Throwable>()
        callbacks.load().forEach { registration ->
            if (!registration.active.load()) return@forEach
            try {
                registration.cleanup.cleanup(sessionId)
            } catch (error: Throwable) {
                failures += error
            }
        }
        if (failures.isNotEmpty()) throw SessionResourceCleanupException(failures)
    }

    private fun update(transform: (List<Registration>) -> List<Registration>) {
        while (true) {
            val current = callbacks.load()
            val next = transform(current)
            if (callbacks.compareAndSet(current, next)) return
        }
    }

    private class Registration(
        val cleanup: SessionResourceCleanup,
    ) {
        val active = AtomicBoolean(true)
    }
}

/** Pi-compatible top-level aliases for callers that do not need the registry name. */
fun registerSessionResourceCleanup(cleanup: SessionResourceCleanup): () -> Unit = SessionResources.register(cleanup)

fun cleanupSessionResources(sessionId: String? = null) = SessionResources.cleanup(sessionId)
