@file:Suppress("TooGenericExceptionCaught")

package dev.xuexue.pi.coding.extensions

import dev.xuexue.pi.coding.resources.ResourceSourceInfo
import dev.xuexue.pi.coding.resources.syntheticResourceSourceInfo
import java.io.File
import java.net.URLClassLoader
import java.util.jar.JarFile

data class LoadedJvmPiPlugin(
    val manifest: PiPluginManifest,
    val extension: PiExtension,
)

data class LoadedJvmPiMod(
    val manifest: PiPluginManifest,
    val mod: PiMod,
)

internal data class LoadedJvmPiEntrypoint(
    val manifest: PiPluginManifest,
    val extension: PiExtension? = null,
    val mod: PiMod? = null,
    val close: () -> Unit,
)

/** Loads a trusted JVM plugin from a manifest-bearing JAR. JVM classloaders are not a sandbox. */
class JvmPiPluginLoader(
    private val parentClassLoader: ClassLoader =
        Thread.currentThread().contextClassLoader ?: JvmPiPluginLoader::class.java.classLoader,
) {
    fun load(
        jarPath: String,
        trusted: Boolean,
        sourceInfo: ResourceSourceInfo =
            syntheticResourceSourceInfo(
                path = jarPath,
                source = "local",
                baseDirectory = File(jarPath).absoluteFile.parent,
            ),
    ): PiExtension {
        val loaded = loadAny(jarPath, trusted, sourceInfo)
        return loaded.extension ?: run {
            loaded.close()
            error("JVM plugin ${loaded.manifest.id} does not expose a PiExtension entrypoint")
        }
    }

    fun loadMod(
        jarPath: String,
        trusted: Boolean,
        sourceInfo: ResourceSourceInfo =
            syntheticResourceSourceInfo(
                path = jarPath,
                source = "local",
                baseDirectory = File(jarPath).absoluteFile.parent,
            ),
    ): PiMod {
        val loaded = loadAny(jarPath, trusted, sourceInfo)
        return loaded.mod ?: run {
            loaded.close()
            error("JVM plugin ${loaded.manifest.id} does not expose a PiMod entrypoint")
        }
    }

    internal fun loadAny(
        jarPath: String,
        trusted: Boolean,
        sourceInfo: ResourceSourceInfo,
    ): LoadedJvmPiEntrypoint {
        require(trusted) { "JVM plugin loading requires an explicit trust decision: $jarPath" }
        val jarFile = File(jarPath).canonicalFile
        require(jarFile.isFile) { "JVM plugin JAR does not exist: $jarFile" }
        val classLoader = URLClassLoader(arrayOf(jarFile.toURI().toURL()), parentClassLoader)
        try {
            val manifest = readManifest(jarFile)
            manifest.validate()
            require(manifest.runtime == PiPluginRuntime.JVM) {
                "Plugin ${manifest.id} declares runtime ${manifest.runtime}, expected JVM"
            }
            val entrypoint =
                requireNotNull(manifest.entrypoint) {
                    "JVM plugin ${manifest.id} is missing entrypoint"
                }
            val instance = Class.forName(entrypoint, true, classLoader).getDeclaredConstructor().newInstance()
            return when (instance) {
                is PiModProvider -> {
                    LoadedJvmPiEntrypoint(
                        manifest = manifest,
                        mod = ClassLoaderBackedMod(manifest, instance.createPiMod(), classLoader),
                        close = classLoader::close,
                    )
                }

                is PiMod -> {
                    LoadedJvmPiEntrypoint(
                        manifest = manifest,
                        mod = ClassLoaderBackedMod(manifest, instance, classLoader),
                        close = classLoader::close,
                    )
                }

                is PiExtensionProvider -> {
                    LoadedJvmPiEntrypoint(
                        manifest = manifest,
                        extension = ClassLoaderBackedExtension(manifest, instance.createPiExtension(), classLoader, sourceInfo),
                        close = classLoader::close,
                    )
                }

                is PiExtension -> {
                    LoadedJvmPiEntrypoint(
                        manifest = manifest,
                        extension = ClassLoaderBackedExtension(manifest, instance, classLoader, sourceInfo),
                        close = classLoader::close,
                    )
                }

                else -> {
                    error(
                        "JVM plugin entrypoint must implement PiMod, PiModProvider, " +
                            "PiExtension or PiExtensionProvider",
                    )
                }
            }
        } catch (error: Throwable) {
            classLoader.close()
            throw error
        }
    }

    private fun readManifest(jarFile: File): PiPluginManifest =
        JarFile(jarFile).use { jar ->
            val entry =
                jar.getJarEntry(PI_PLUGIN_MANIFEST_PATH)
                    ?: error("JVM plugin is missing $PI_PLUGIN_MANIFEST_PATH: $jarFile")
            jar.getInputStream(entry).bufferedReader().use { reader ->
                PiPluginManifestCodec.decode(reader.readText())
            }
        }
}

private class ClassLoaderBackedExtension(
    private val manifest: PiPluginManifest,
    private val delegate: PiExtension,
    private val classLoader: URLClassLoader,
    override val sourceInfo: ResourceSourceInfo,
) : PiExtension {
    override val id: String = manifest.id

    override suspend fun activate(api: PiExtensionApi): PiExtensionHandle =
        try {
            val delegateHandle = delegate.activate(api)
            PiExtensionHandle {
                try {
                    delegateHandle?.dispose()
                } finally {
                    classLoader.close()
                }
            }
        } catch (error: Throwable) {
            classLoader.close()
            throw error
        }
}

private class ClassLoaderBackedMod(
    private val manifest: PiPluginManifest,
    private val delegate: PiMod,
    private val classLoader: URLClassLoader,
) : PiMod {
    override val id: ModId = ModId.parse(manifest.id)
    override val version: ModVersion = ModVersion(manifest.version)

    override suspend fun enable(context: ModContext): ModHandle? =
        try {
            delegate.enable(context)?.let { delegateHandle ->
                ModHandle {
                    try {
                        delegateHandle.disable()
                    } finally {
                        classLoader.close()
                    }
                }
            } ?: ModHandle { classLoader.close() }
        } catch (error: Throwable) {
            classLoader.close()
            throw error
        }
}
