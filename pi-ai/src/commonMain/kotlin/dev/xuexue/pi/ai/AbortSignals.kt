@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package dev.xuexue.pi.ai

import kotlinx.coroutines.Job
import kotlin.concurrent.atomics.AtomicReference

data class CombinedAbortSignal(
    val signal: AbortSignal? = null,
    val cleanup: () -> Unit,
)

class AbortController {
    private val state = AtomicReference(AbortState())
    private val job = Job()

    val signal: AbortSignal =
        object : AbortSignal {
            override val isAborted: Boolean
                get() = state.load().aborted

            override val reason: Any?
                get() = state.load().reason

            override fun onAbort(listener: () -> Unit): AbortSubscription {
                if (isAborted) return AbortSubscription {}
                val handle = job.invokeOnCompletion { if (isAborted) listener() }
                return AbortSubscription(handle::dispose)
            }
        }

    fun abort(reason: Any? = null) {
        val aborted = AbortState(aborted = true, reason = reason)
        while (true) {
            val current = state.load()
            if (current.aborted) return
            if (state.compareAndSet(expectedValue = current, newValue = aborted)) {
                job.cancel()
                return
            }
        }
    }

    private data class AbortState(
        val aborted: Boolean = false,
        val reason: Any? = null,
    )
}

fun combineAbortSignals(signals: List<AbortSignal?>): CombinedAbortSignal {
    val activeSignals = signals.filterNotNull()
    return when (activeSignals.size) {
        0 -> CombinedAbortSignal(cleanup = {})
        1 -> CombinedAbortSignal(activeSignals.single()) {}
        else -> combineMultipleAbortSignals(activeSignals)
    }
}

private fun combineMultipleAbortSignals(activeSignals: List<AbortSignal>): CombinedAbortSignal {
    val controller = AbortController()
    val subscriptions = mutableListOf<AbortSubscription>()
    for (signal in activeSignals) {
        if (signal.isAborted) {
            controller.abort(signal.reason)
            break
        }
        subscriptions += signal.onAbort { controller.abort(signal.reason) }
    }

    return CombinedAbortSignal(
        signal = controller.signal,
        cleanup = { subscriptions.forEach(AbortSubscription::dispose) },
    )
}
