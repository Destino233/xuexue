@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package dev.xuexue.pi.coding.extensions

import dev.xuexue.pi.agent.AgentTool
import dev.xuexue.pi.ai.PiImageProvider
import dev.xuexue.pi.ai.PiProvider
import dev.xuexue.pi.coding.resources.ResourceExtensionPaths
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference

/** A value in a global registry together with the run that owns it. */
data class RegisteredModEntry<T>(
    val id: String,
    val value: T,
    val owner: ModInfo,
)

/**
 * Lock-free copy-on-write registry. Registrations are removed by identity so a
 * stale run can never remove a replacement installed by a newer run.
 */
class ModOwnedRegistry<T>(
    private val kind: ModContributionKind,
) {
    private val entries = AtomicReference<Map<String, OwnedEntry<T>>>(emptyMap())

    internal fun register(
        owner: ModRun,
        id: String,
        value: T,
        replace: Boolean = false,
    ): Registration {
        owner.requireActive()
        val normalizedId = normalizeContributionId(id)
        val registration = OwnedRegistration(normalizedId, owner, this)
        while (true) {
            val current = entries.load()
            val previous = current[normalizedId]
            if (previous != null && !replace) {
                error(
                    "${kind.name.lowercase()} '$normalizedId' is already registered by " +
                        "${previous.owner.info.id} (run ${previous.owner.runId})",
                )
            }
            val next = current.toMutableMap()
            next[normalizedId] = OwnedEntry(value, owner, registration)
            if (entries.compareAndSet(current, next.toMap())) {
                previous?.registration?.deactivate()
                break
            }
        }
        try {
            owner.own(registration)
        } catch (error: Throwable) {
            registration.unregister()
            throw error
        }
        return registration
    }

    fun get(id: String): T? = entries.load()[id.trim()]?.value

    fun snapshot(): List<RegisteredModEntry<T>> =
        entries.load().map { (id, entry) ->
            RegisteredModEntry(id = id, value = entry.value, owner = entry.owner.info)
        }

    fun removeOwner(owner: ModRunId): Int {
        var removed = 0
        while (true) {
            val current = entries.load()
            val next = current.filterValues { entry ->
                val keep = entry.owner.runId != owner
                if (!keep) removed++
                keep
            }
            if (entries.compareAndSet(current, next)) {
                current
                    .filterValues { it.owner.runId == owner }
                    .values
                    .forEach { it.registration.deactivate() }
                return removed
            }
            removed = 0
        }
    }

    fun clear() {
        val previous = entries.exchange(emptyMap())
        previous.values.forEach { it.registration.deactivate() }
    }

    private fun remove(
        id: String,
        registration: OwnedRegistration<T>,
    ) {
        while (true) {
            val current = entries.load()
            if (current[id]?.registration !== registration) return
            val next = current - id
            if (entries.compareAndSet(current, next)) return
        }
    }

    private data class OwnedEntry<T>(
        val value: T,
        val owner: ModRun,
        val registration: OwnedRegistration<T>,
    )

    private class OwnedRegistration<T>(
        override val id: String,
        private val run: ModRun,
        private val registry: ModOwnedRegistry<T>,
    ) : Registration {
        private val active = AtomicBoolean(true)

        override val owner: ModRunId
            get() = run.runId

        override val kind: ModContributionKind
            get() = registry.kind

        override val isActive: Boolean
            get() = active.load()

        override fun unregister() {
            if (!active.compareAndSet(true, false)) return
            registry.remove(id, this)
            run.forget(this)
        }

        fun deactivate() {
            active.store(false)
            run.forget(this)
        }
    }
}

class ToolRegistry internal constructor(
    private val registry: ModOwnedRegistry<AgentTool>,
    private val owner: ModRun,
) {
    fun register(
        id: String,
        tool: AgentTool,
        replace: Boolean = false,
    ): Registration = registry.register(owner, owner.info.id.qualify(id), tool, replace)

    fun get(id: String): AgentTool? = registry.get(id)

    fun entries(): List<RegisteredModEntry<AgentTool>> = registry.snapshot()
}

class CommandRegistry internal constructor(
    private val registry: ModOwnedRegistry<ModCommand>,
    private val owner: ModRun,
) {
    fun register(
        id: String,
        command: ModCommand,
        replace: Boolean = false,
    ): Registration = registry.register(owner, owner.info.id.qualify(id), command, replace)

    fun get(id: String): ModCommand? = registry.get(id)

    fun entries(): List<RegisteredModEntry<ModCommand>> = registry.snapshot()
}

class ProviderRegistry internal constructor(
    private val registry: ModOwnedRegistry<PiProvider>,
    private val owner: ModRun,
) {
    fun register(
        id: String,
        provider: PiProvider,
        replace: Boolean = false,
    ): Registration = registry.register(owner, owner.info.id.qualify(id), provider, replace)

    fun entries(): List<RegisteredModEntry<PiProvider>> = registry.snapshot()
}

class ImageProviderRegistry internal constructor(
    private val registry: ModOwnedRegistry<PiImageProvider>,
    private val owner: ModRun,
) {
    fun register(
        id: String,
        provider: PiImageProvider,
        replace: Boolean = false,
    ): Registration = registry.register(owner, owner.info.id.qualify(id), provider, replace)

    fun entries(): List<RegisteredModEntry<PiImageProvider>> = registry.snapshot()
}

class ResourceRegistry internal constructor(
    private val registry: ModOwnedRegistry<ResourceExtensionPaths>,
    private val owner: ModRun,
) {
    fun register(
        id: String,
        paths: ResourceExtensionPaths,
        replace: Boolean = false,
    ): Registration = registry.register(owner, owner.info.id.qualify(id), paths, replace)

    fun entries(): List<RegisteredModEntry<ResourceExtensionPaths>> = registry.snapshot()
}

data class ModCommand(
    val description: String? = null,
    val handler: suspend (args: String, context: ModContext) -> Unit,
)

internal fun normalizeContributionId(raw: String): String {
    val normalized = raw.trim()
    require(MOD_NAME_PATTERN.matches(normalized)) {
        "Invalid contribution id '$raw'; use [namespace:]name."
    }
    return normalized
}
