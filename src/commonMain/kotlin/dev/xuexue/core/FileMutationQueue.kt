package dev.xuexue.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes mutations for one canonical path while allowing unrelated files to proceed concurrently. */
class FileMutationQueue {
    private val registrationLock = Any()
    private val locks = linkedMapOf<String, Mutex>()

    suspend fun <T> withPath(
        path: String,
        block: suspend () -> T,
    ): T {
        val key = path.replace('\\', '/').trimEnd('/')
        val mutex = synchronized(registrationLock) { locks.getOrPut(key, ::Mutex) }
        try {
            return mutex.withLock { block() }
        } finally {
            synchronized(registrationLock) {
                if (!mutex.isLocked) locks.remove(key, mutex)
            }
        }
    }
}
