@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package dev.xuexue.pi.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelIterator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.concurrent.atomics.AtomicBoolean

open class EventStream<T, R : Any>(
    private val isComplete: (T) -> Boolean,
    private val extractResult: (T) -> R,
) {
    private val queue = Channel<T>(Channel.UNLIMITED)
    private val finalResult = CompletableDeferred<R>()
    private val done = AtomicBoolean(false)

    fun push(event: T) {
        if (done.load()) return

        val terminal = isComplete(event)
        if (terminal && !done.compareAndSet(expectedValue = false, newValue = true)) return

        if (terminal) finalResult.complete(extractResult(event))
        queue.trySend(event)
        if (terminal) queue.close()
    }

    fun end(result: R? = null) {
        if (!done.compareAndSet(expectedValue = false, newValue = true)) return
        if (result != null) finalResult.complete(result)
        queue.close()
    }

    operator fun iterator(): ChannelIterator<T> = queue.iterator()

    fun asFlow(): Flow<T> = queue.receiveAsFlow()

    suspend fun result(): R = finalResult.await()
}

class AssistantMessageEventStream :
    EventStream<AssistantMessageEvent, AssistantMessage>(
        isComplete = { event -> event is AssistantMessageEvent.Done || event is AssistantMessageEvent.Error },
        extractResult = { event ->
            when (event) {
                is AssistantMessageEvent.Done -> event.message
                is AssistantMessageEvent.Error -> event.error
                else -> error("Unexpected event type for final result")
            }
        },
    )

fun createAssistantMessageEventStream(): AssistantMessageEventStream = AssistantMessageEventStream()
