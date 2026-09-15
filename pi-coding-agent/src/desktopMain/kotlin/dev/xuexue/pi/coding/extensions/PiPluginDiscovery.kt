@file:Suppress("TooGenericExceptionCaught")

package dev.xuexue.pi.coding.extensions

import dev.xuexue.pi.coding.resources.ResourceOrigin
import dev.xuexue.pi.coding.resources.ResourceScope
import dev.xuexue.pi.coding.resources.syntheticResourceSourceInfo
import dev.xuexue.pi.coding.settings.SettingsManager
import java.io.File
import java.util.jar.JarFile

enum class PiPluginScope {
    USER,
    PROJECT,
    TEMPORARY,
}

data class PiPluginCandidate(
    val path: String,
    val manifest: PiPluginManifest,
    val scope: PiPluginScope,
)

data class PiPluginLoadError(
    val path: String,
    val message: String,
)

data class PiPluginLoadResult(
    val candidates: List<PiPluginCandidate>,
    val extensions: List<PiExtension>,
    val errors: List<PiPluginLoadError>,
    val mods: List<PiPluginMod> = emptyList(),
) {
    /** Converts discovery into the host-neutral inventory shown by management UIs. */
    fun toModInventory(projectTrusted: Boolean): List<ModDiscoveredPackage> {
        val errorsByPath = errors.associateBy(PiPluginLoadError::path)
        return candidates.map { candidate ->
            val error = errorsByPath[candidate.path]
            val trusted = projectTrusted || candidate.scope != PiPluginScope.PROJECT
            ModDiscoveredPackage(
                id = ModId.parse(candidate.manifest.id),
                version = ModVersion(candidate.manifest.version),
                runtime = candidate.manifest.runtime,
                sourcePath = candidate.path,
                scope = candidate.scope.name,
                trusted = trusted,
                status =
                    when {
                        candidate.scope == PiPluginScope.PROJECT && !projectTrusted -> ModDiscoveryStatus.BLOCKED
                        error != null -> ModDiscoveryStatus.FAILED
                        else -> ModDiscoveryStatus.DISCOVERED
                    },
                error = error?.message,
            )
        }
    }

    fun toModDiagnostics(): List<ModDiscoveryDiagnostic> =
        errors.map { error -> ModDiscoveryDiagnostic(error.path, error.message) }
}

data class PiPluginMod(
    val mod: PiMod,
    val packageInfo: ModPackage,
    val candidate: PiPluginCandidate,
)

data class PiPluginDiscoveryOptions(
    val cwd: String,
    val agentDirectory: String,
    val projectTrusted: Boolean,
    val configuredUserPaths: List<String> = emptyList(),
    val configuredProjectPaths: List<String> = emptyList(),
    val additionalPaths: List<String> = emptyList(),
    val additionalUserPaths: List<String> = emptyList(),
    val additionalProjectPaths: List<String> = emptyList(),
    val scopesToLoad: Set<PiPluginScope> = PiPluginScope.entries.toSet(),
) {
    companion object {
        fun fromSettings(
            cwd: String,
            agentDirectory: String,
            settingsManager: SettingsManager,
            projectTrusted: Boolean = settingsManager.isProjectTrusted(),
            additionalPaths: List<String> = emptyList(),
            additionalUserPaths: List<String> = emptyList(),
            additionalProjectPaths: List<String> = emptyList(),
            scopesToLoad: Set<PiPluginScope> = PiPluginScope.entries.toSet(),
        ): PiPluginDiscoveryOptions =
            PiPluginDiscoveryOptions(
                cwd = cwd,
                agentDirectory = agentDirectory,
                projectTrusted = projectTrusted,
                configuredUserPaths = settingsManager.getGlobalExtensionPaths(),
                configuredProjectPaths = settingsManager.getProjectExtensionPaths(),
                additionalPaths = additionalPaths,
                additionalUserPaths = additionalUserPaths,
                additionalProjectPaths = additionalProjectPaths,
                scopesToLoad = scopesToLoad,
            )
    }
}

/** Discovers manifest-bearing JVM JARs and process plugin bundles in deterministic scope order. */
class PiPluginDiscovery(
    private val options: PiPluginDiscoveryOptions,
) {
    fun discover(): Pair<List<PiPluginCandidate>, List<PiPluginLoadError>> {
        val projectRoot = File(options.cwd, ".xuexue/pi/extensions")
        val userRoot = File(options.agentDirectory, "extensions")
        val roots =
            listOf(
                projectRoot to PiPluginScope.PROJECT,
                userRoot to PiPluginScope.USER,
            ) + options.configuredProjectPaths.map { resolveConfiguredPath(it) to PiPluginScope.PROJECT } +
                options.configuredUserPaths.map { resolveConfiguredPath(it) to PiPluginScope.USER } +
                options.additionalProjectPaths.map { resolveConfiguredPath(it) to PiPluginScope.PROJECT } +
                options.additionalUserPaths.map { resolveConfiguredPath(it) to PiPluginScope.USER } +
                options.additionalPaths.map { resolveConfiguredPath(it) to PiPluginScope.TEMPORARY }
        val candidates = mutableListOf<PiPluginCandidate>()
        val errors = mutableListOf<PiPluginLoadError>()
        val seen = mutableSetOf<String>()
        roots.forEach { (root, scope) ->
            discoverRoot(root, scope, seen, candidates, errors)
        }
        return candidates to errors
    }

    private fun resolveConfiguredPath(path: String): File {
        val file = File(path)
        return if (file.isAbsolute) file else File(options.cwd, path)
    }

    private fun PiPluginCandidate.sourceInfo() =
        syntheticResourceSourceInfo(
            path = path,
            source = "local",
            scope =
                when (scope) {
                    PiPluginScope.USER -> ResourceScope.USER
                    PiPluginScope.PROJECT -> ResourceScope.PROJECT
                    PiPluginScope.TEMPORARY -> ResourceScope.TEMPORARY
                },
            origin = ResourceOrigin.PACKAGE,
            baseDirectory = File(path).absoluteFile.parent,
        )

    fun loadAll(): PiPluginLoadResult {
        val (candidates, discoveryErrors) = discover()
        val extensions = mutableListOf<PiExtension>()
        val mods = mutableListOf<PiPluginMod>()
        val errors = discoveryErrors.toMutableList()
        candidates.forEach { candidate ->
            if (candidate.scope == PiPluginScope.PROJECT && !options.projectTrusted) return@forEach
            if (candidate.scope !in options.scopesToLoad) return@forEach
            runCatching {
                when (candidate.manifest.runtime) {
                    PiPluginRuntime.JVM -> {
                        val loaded =
                            JvmPiPluginLoader().loadAny(
                                candidate.path,
                                trusted = options.projectTrusted || candidate.scope != PiPluginScope.PROJECT,
                                sourceInfo = candidate.sourceInfo(),
                            )
                        loaded.extension?.let(extensions::add)
                        loaded.mod?.let { mod ->
                            mods +=
                                PiPluginMod(
                                    mod = mod,
                                    packageInfo = candidate.manifest.toModPackage(candidate.path),
                                    candidate = candidate,
                                )
                        }
                    }

                    PiPluginRuntime.PROCESS -> {
                        extensions +=
                            ProcessPiExtension.fromManifest(
                                candidate.manifest,
                                candidate.path,
                                sourceInfo = candidate.sourceInfo(),
                            )
                    }
                }
            }.onFailure { error -> errors += PiPluginLoadError(candidate.path, error.message.orEmpty()) }
        }
        return PiPluginLoadResult(candidates, extensions, errors, mods)
    }

    private fun discoverRoot(
        root: File,
        scope: PiPluginScope,
        seen: MutableSet<String>,
        candidates: MutableList<PiPluginCandidate>,
        errors: MutableList<PiPluginLoadError>,
    ) {
        val files =
            when {
                !root.exists() -> {
                    emptyList()
                }

                root.isFile -> {
                    listOf(root)
                }

                hasPluginManifest(root) -> {
                    listOf(root)
                }

                else -> {
                    root
                        .listFiles()
                        ?.sortedBy(File::getName)
                        ?.toList()
                        .orEmpty()
                }
            }
        files.forEach { file ->
            val candidate = readCandidate(file, scope, errors) ?: return@forEach
            val canonicalPath = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
            if (seen.add(canonicalPath)) candidates += candidate
        }
    }

    private fun hasPluginManifest(
        directory: File,
    ): Boolean =
        File(directory, PI_PLUGIN_MANIFEST_PATH).isFile ||
            File(directory, "xuexue-pi-plugin.json").isFile

    private fun readCandidate(
        file: File,
        scope: PiPluginScope,
        errors: MutableList<PiPluginLoadError>,
    ): PiPluginCandidate? =
        when {
            file.isFile && file.extension == "jar" -> {
                readManifestFromJar(file, errors)?.let { PiPluginCandidate(file.path, it, scope) }
            }

            file.isDirectory -> {
                val manifestFile =
                    File(file, PI_PLUGIN_MANIFEST_PATH)
                        .takeIf(File::isFile)
                        ?: File(file, "xuexue-pi-plugin.json").takeIf(File::isFile)
                manifestFile?.let { manifest ->
                    readManifestFile(manifest, errors)?.let { PiPluginCandidate(file.path, it, scope) }
                }
            }

            else -> {
                null
            }
        }

    private fun readManifestFromJar(
        file: File,
        errors: MutableList<PiPluginLoadError>,
    ): PiPluginManifest? =
        runCatching {
            JarFile(file).use { jar ->
                val entry =
                    jar.getJarEntry(PI_PLUGIN_MANIFEST_PATH)
                        ?: error("Missing $PI_PLUGIN_MANIFEST_PATH")
                jar.getInputStream(entry).bufferedReader().use { reader ->
                    PiPluginManifestCodec.decode(reader.readText())
                }
            }
        }.onFailure { error -> errors += PiPluginLoadError(file.path, error.message.orEmpty()) }.getOrNull()

    private fun readManifestFile(
        file: File,
        errors: MutableList<PiPluginLoadError>,
    ): PiPluginManifest? =
        runCatching {
            PiPluginManifestCodec.decode(file.readText())
        }.onFailure { error -> errors += PiPluginLoadError(file.path, error.message.orEmpty()) }.getOrNull()
}
