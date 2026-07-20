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
            val extension =
                when (instance) {
                    is PiExtensionProvider -> instance.createPiExtension()
                    is PiExtension -> instance
                    else -> error("JVM plugin entrypoint must implement PiExtension or PiExtensionProvider")
                }
            return ClassLoaderBackedExtension(manifest, extension, classLoader, sourceInfo)
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
