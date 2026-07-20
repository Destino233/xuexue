package dev.xuexue.pi.coding.trust

/** Serialized trust-store access. Implementations must make each operation atomic across host processes. */
interface ProjectTrustStorage {
    fun <T> withLock(read: (current: String?) -> T): T

    fun update(transform: (current: String?) -> String?)
}

class InMemoryProjectTrustStorage(
    initial: String? = null,
) : ProjectTrustStorage {
    private val lock = Any()
    private var content = initial

    override fun <T> withLock(read: (current: String?) -> T): T = synchronized(lock) { read(content) }

    override fun update(transform: (current: String?) -> String?) {
        synchronized(lock) {
            transform(content)?.let { content = it }
        }
    }

    fun content(): String? = synchronized(lock) { content }
}

expect object PlatformProjectTrustStorage {
    fun create(agentDirectory: String): ProjectTrustStorage
}
