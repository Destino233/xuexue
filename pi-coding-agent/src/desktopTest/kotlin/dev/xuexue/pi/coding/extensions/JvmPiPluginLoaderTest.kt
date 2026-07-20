package dev.xuexue.pi.coding.extensions

import dev.xuexue.pi.agent.AgentTool
import dev.xuexue.pi.agent.AgentToolResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.io.FileOutputStream
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JvmPiPluginLoaderTest {
    @Test
    fun trustedManifestJarLoadsEntrypointAndUntrustedJarIsRejected() =
        runTest {
            val directory =
                kotlin.io.path
                    .createTempDirectory("xuexue-jvm-plugin")
                    .toFile()
            val jar = directory.resolve("plugin.jar")
            writePluginJar(jar)
            try {
                val loader = JvmPiPluginLoader(javaClass.classLoader)
                assertFailsWith<IllegalArgumentException> { loader.load(jar.path, trusted = false) }

                val extension = loader.load(jar.path, trusted = true)
                val runner = PiExtensionRunner(directory.path)
                runner.load(extension)

                assertEquals("jar-tool", runner.getAllRegisteredTools().single().name)
                runner.dispose()

                val discovered =
                    PiPluginDiscovery(
                        PiPluginDiscoveryOptions(
                            cwd = directory.path,
                            agentDirectory = directory.path,
                            projectTrusted = true,
                            additionalPaths = listOf(jar.path),
                        ),
                    ).loadAll()
                assertEquals(1, discovered.extensions.size)
                val discoveredId =
                    discovered.candidates
                        .single()
                        .manifest.id
                assertEquals("test.jvm.plugin", discoveredId)
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun configuredProjectPluginsRemainDiscoveredButAreNotLoadedBeforeTrust() {
        val directory =
            kotlin.io.path
                .createTempDirectory("xuexue-project-plugin")
                .toFile()
        val pluginDirectory = directory.resolve("project-plugin")
        val manifest = pluginDirectory.resolve(PI_PLUGIN_MANIFEST_PATH)
        manifest.parentFile.mkdirs()
        manifest.writeText(
            """
            {"schemaVersion":1,"id":"project.plugin","version":"1.0.0","apiVersion":"1","runtime":"process","executable":"plugin.sh"}
            """.trimIndent(),
        )
        try {
            val result =
                PiPluginDiscovery(
                    PiPluginDiscoveryOptions(
                        cwd = directory.path,
                        agentDirectory = directory.path,
                        projectTrusted = false,
                        configuredProjectPaths = listOf(pluginDirectory.relativeTo(directory).path),
                    ),
                ).loadAll()

            val candidateIds = result.candidates.map { it.manifest.id }
            assertEquals(listOf("project.plugin"), candidateIds)
            assertEquals(emptyList(), result.extensions)
            assertEquals(emptyList(), result.errors)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun writePluginJar(jar: File) {
        val classPath = "/${TestJvmPluginProvider::class.java.name.replace('.', '/')}.class"
        val classBytes = requireNotNull(TestJvmPluginProvider::class.java.getResourceAsStream(classPath)).readBytes()
        JarOutputStream(FileOutputStream(jar)).use { output ->
            output.putNextEntry(JarEntry(PI_PLUGIN_MANIFEST_PATH))
            output.write(
                """
                {"schemaVersion":1,"id":"test.jvm.plugin","version":"1.0.0","apiVersion":"1","runtime":"jvm","entrypoint":"${TestJvmPluginProvider::class.java.name}"}
                """.trimIndent().encodeToByteArray(),
            )
            output.closeEntry()
            output.putNextEntry(JarEntry(classPath.removePrefix("/")))
            output.write(classBytes)
            output.closeEntry()
        }
    }
}

class TestJvmPluginProvider : PiExtensionProvider {
    override fun createPiExtension(): PiExtension =
        KotlinPiExtension("test.jvm.plugin") {
            registerTool(
                AgentTool(
                    name = "jar-tool",
                    label = "Jar tool",
                    description = "Jar tool",
                    parameters = buildJsonObject {},
                    execute = { _, _, _, _ -> AgentToolResult(emptyList(), buildJsonObject {}) },
                ),
            )
            null
        }
}
