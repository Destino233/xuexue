@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package dev.xuexue.pi.agent

import dev.xuexue.pi.ai.AgentMessage
import kotlin.concurrent.atomics.AtomicReference

internal class PendingMessageQueue(
    mode: QueueMode,
) {
    private val messages = AtomicReference<List<AgentMessage>>(emptyList())
    private val queueMode = AtomicReference(mode)

    var mode: QueueMode
        get() = queueMode.load()
        set(value) = queueMode.store(value)

    val size: Int
        get() = messages.load().size

    fun enqueue(message: AgentMessage) {
        updateMessages { current -> current + message }
    }

    fun hasItems(): Boolean = messages.load().isNotEmpty()

    fun drain(): List<AgentMessage> {
        while (true) {
            val current = messages.load()
            if (current.isEmpty()) return emptyList()
            val drained = if (mode == QueueMode.ALL) current else current.take(1)
            val remaining = if (mode == QueueMode.ALL) emptyList() else current.drop(1)
            if (messages.compareAndSet(current, remaining)) return drained
        }
    }

    fun clear() {
        updateMessages { emptyList() }
    }

    private fun updateMessages(transform: (List<AgentMessage>) -> List<AgentMessage>) {
        while (true) {
            val current = messages.load()
            if (messages.compareAndSet(current, transform(current))) return
        }
    }
}
