@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package dev.xuexue.pi.coding.extensions

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.time.Clock

/** Stable, namespaced identity for a mod and its contributed capabilities. */
@JvmInline
value class ModId(
    val value: String,
) {
    init {
        require(MOD_ID_PATTERN.matches(value)) {
            "Invalid mod id '$value'; use [namespace:]name with lowercase letters, digits, '.', '_' or '-'."
        }
    }

    /** Returns a namespaced contribution ID, preserving already-qualified values. */
    fun qualify(name: String): String {
        val normalized = name.trim()
        require(MOD_NAME_PATTERN.matches(normalized)) {
            "Invalid contribution name '$name' for mod $value"
        }
        return if (normalized.contains(':')) normalized else "$value:$normalized"
    }

    override fun toString(): String = value

    companion object {
        fun parse(raw: String): ModId = ModId(raw.trim())
    }
}

/** Identity for one exact activation of a mod package. */
@JvmInline
value class ModRunId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Mod run id must not be blank" }
    }

    override fun toString(): String = value
}

/** Small immutable version value used by package selection and diagnostics. */
data class ModVersion(
    val value: String,
) : Comparable<ModVersion> {
    init {
        require(MOD_VERSION_PATTERN.matches(value)) {
            "Invalid mod version '$value'; expected semantic version syntax."
        }
    }

    private val core: List<Int>
        get() = value.substringBefore('-').substringBefore('+').split('.').map(String::toInt)

    private val prerelease: List<String>
        get() = value.substringAfter('-', missingDelimiterValue = "").substringBefore('+')
            .takeIf(String::isNotEmpty)
            ?.split('.')
            .orEmpty()

    override fun compareTo(other: ModVersion): Int {
        val leftCore = core
        val rightCore = other.core
        repeat(3) { index ->
            val comparison = leftCore[index].compareTo(rightCore[index])
            if (comparison != 0) return comparison
        }
        val leftPrerelease = prerelease
        val rightPrerelease = other.prerelease
        if (leftPrerelease.isEmpty() && rightPrerelease.isEmpty()) return 0
        if (leftPrerelease.isEmpty()) return 1
        if (rightPrerelease.isEmpty()) return -1
        val length = maxOf(leftPrerelease.size, rightPrerelease.size)
        repeat(length) { index ->
            val left = leftPrerelease.getOrNull(index) ?: return -1
            val right = rightPrerelease.getOrNull(index) ?: return 1
            val comparison = comparePrereleasePart(left, right)
            if (comparison != 0) return comparison
        }
        return 0
    }

    override fun toString(): String = value

    private fun comparePrereleasePart(
        left: String,
        right: String,
    ): Int {
        val leftNumber = left.toIntOrNull()
        val rightNumber = right.toIntOrNull()
        return when {
            leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
            leftNumber != null -> -1
            rightNumber != null -> 1
            else -> left.compareTo(right)
        }
    }
}

/** Dependency declaration. A blank range means any compatible version. */
data class ModDependency(
    val id: ModId,
    val versionRange: ModVersionRange = ModVersionRange.Any,
    val optional: Boolean = false,
) {
    companion object {
        fun parse(raw: String): ModDependency {
            val value = raw.trim()
            require(value.isNotEmpty()) { "Mod dependency must not be blank" }
            val separator = value.indexOf('@')
            val id = if (separator < 0) value else value.substring(0, separator)
            val range = if (separator < 0) "" else value.substring(separator + 1)
            return ModDependency(ModId.parse(id), ModVersionRange.parse(range))
        }
    }
}

sealed interface ModVersionRange {
    fun accepts(version: ModVersion): Boolean

    data object Any : ModVersionRange {
        override fun accepts(version: ModVersion): Boolean = true
    }

    data class Exact(
        val version: ModVersion,
    ) : ModVersionRange {
        override fun accepts(version: ModVersion): Boolean = this.version == version
    }

    data class AtLeast(
        val version: ModVersion,
    ) : ModVersionRange {
        override fun accepts(version: ModVersion): Boolean = version >= this.version
    }

    companion object {
        fun parse(raw: String): ModVersionRange {
            val value = raw.trim()
            if (value.isEmpty() || value == "*") return Any
            return when {
                value.startsWith(">=") -> AtLeast(ModVersion(value.removePrefix(">=").trim()))
                value.startsWith("=") -> Exact(ModVersion(value.removePrefix("=").trim()))
                else -> Exact(ModVersion(value))
            }
        }
    }
}

/** Immutable package metadata separated from a package's live run state. */
data class ModPackage(
    val id: ModId,
    val version: ModVersion,
    val apiVersion: String,
    val runtime: PiPluginRuntime,
    val entrypoint: String? = null,
    val executable: String? = null,
    val dependencies: List<ModDependency> = emptyList(),
    val permissions: Set<String> = emptySet(),
    val contributes: Set<String> = emptySet(),
    val sourcePath: String? = null,
) {
    init {
        require(apiVersion.isNotBlank()) { "Mod $id API version must not be blank" }
        require(dependencies.none { it.id == id }) { "Mod $id cannot depend on itself" }
    }
}

enum class ModRunState {
    DEFINED,
    STARTING,
    RUNNING,
    STOPPING,
    STOPPED,
    FAILED,
}

data class ModInfo(
    val id: ModId,
    val version: ModVersion,
    val runId: ModRunId,
    val sourcePath: String? = null,
    val trusted: Boolean = false,
)

data class ModRunDiagnostic(
    val runId: ModRunId,
    val state: ModRunState,
    val message: String,
    val cause: String? = null,
)

/** A registration owned by exactly one ModRun. Implementations must be idempotent. */
interface Registration {
    val id: String
    val owner: ModRunId
    val kind: ModContributionKind
    val isActive: Boolean

    fun unregister()
}

enum class ModContributionKind {
    EVENT,
    TOOL,
    COMMAND,
    PROVIDER,
    IMAGE_PROVIDER,
    RESOURCE,
    UI,
    RUNTIME,
    TASK,
}

/** Handle returned by a mod's enable function. */
fun interface ModHandle {
    suspend fun disable()
}

interface PiMod {
    val id: ModId
    val version: ModVersion

    suspend fun enable(context: ModContext): ModHandle?
}

internal class ModRun(
    val packageInfo: ModPackage,
    val info: ModInfo,
    private val diagnostics: MutableList<ModRunDiagnostic>,
) {
    internal companion object {
        val sequence = AtomicLong(0)

        fun create(
            packageInfo: ModPackage,
            trusted: Boolean,
            diagnostics: MutableList<ModRunDiagnostic>,
        ): ModRun {
            val stamp = Clock.System.now().toEpochMilliseconds()
            val serial = sequence.addAndFetch(1)
            val runId = ModRunId("${packageInfo.id.value}-$stamp-$serial")
            return ModRun(
                packageInfo = packageInfo,
                info =
                    ModInfo(
                        id = packageInfo.id,
                        version = packageInfo.version,
                        runId = runId,
                        sourcePath = packageInfo.sourcePath,
                        trusted = trusted,
                    ),
                diagnostics = diagnostics,
            )
        }
    }

    private val mutableState = AtomicReference(ModRunState.DEFINED)
    private val registrations = AtomicReference<List<Registration>>(emptyList())
    private val job = SupervisorJob()

    val scope: CoroutineScope = CoroutineScope(job + Dispatchers.Default)

    val state: ModRunState
        get() = mutableState.load()

    val runId: ModRunId
        get() = info.runId

    fun beginStart() {
        transition(ModRunState.DEFINED, ModRunState.STARTING)
    }

    fun markRunning() {
        transition(ModRunState.STARTING, ModRunState.RUNNING)
    }

    fun markFailed(
        message: String,
        cause: Throwable? = null,
    ) {
        mutableState.store(ModRunState.FAILED)
        diagnostics +=
            ModRunDiagnostic(
                runId = runId,
                state = ModRunState.FAILED,
                message = message,
                cause = cause?.message,
            )
    }

    fun requireActive() {
        check(state == ModRunState.STARTING || state == ModRunState.RUNNING) {
            "Mod ${info.id} run $runId is stale ($state)"
        }
    }

    fun own(registration: Registration) {
        requireActive()
        while (true) {
            val current = registrations.load()
            if (state != ModRunState.STARTING && state != ModRunState.RUNNING) {
                registration.unregister()
                error("Mod ${info.id} run $runId stopped while registering ${registration.id}")
            }
            if (registrations.compareAndSet(current, current + registration)) return
        }
    }

    fun ownedRegistrations(): List<Registration> = registrations.load().asReversed()

    fun forget(registration: Registration) {
        while (true) {
            val current = registrations.load()
            val next = current.filterNot { it === registration }
            if (current === next || registrations.compareAndSet(current, next)) return
        }
    }

    suspend fun stop(
        disable: (suspend () -> Unit)? = null,
    ) {
        val previous = mutableState.exchange(ModRunState.STOPPING)
        if (previous == ModRunState.STOPPING || previous == ModRunState.STOPPED) return
        job.cancel()
        disable?.let {
            runCatching { it() }
                .onFailure { error ->
                    diagnostics +=
                        ModRunDiagnostic(
                            runId = runId,
                            state = ModRunState.STOPPING,
                            message = "Mod disable failed",
                            cause = error.message,
                        )
                }
        }
        ownedRegistrations().forEach { registration ->
            runCatching { registration.unregister() }
                .onFailure { error ->
                    diagnostics +=
                        ModRunDiagnostic(
                            runId = runId,
                            state = ModRunState.STOPPING,
                            message = "Registration cleanup failed: ${registration.id}",
                            cause = error.message,
                        )
                }
        }
        registrations.store(emptyList())
        mutableState.store(ModRunState.STOPPED)
    }

    private fun transition(
        expected: ModRunState,
        next: ModRunState,
    ) {
        check(mutableState.compareAndSet(expected, next)) {
            "Invalid mod ${info.id} run $runId transition ${state} -> $next"
        }
    }
}

private val MOD_ID_PATTERN = Regex("[a-z0-9][a-z0-9_.-]*(?::[a-z0-9][a-z0-9_.-]*)?")
internal val MOD_NAME_PATTERN = Regex("[a-zA-Z0-9][a-zA-Z0-9_.:-]*")
private val MOD_VERSION_PATTERN = Regex("0|[1-9]\\d*\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?")
