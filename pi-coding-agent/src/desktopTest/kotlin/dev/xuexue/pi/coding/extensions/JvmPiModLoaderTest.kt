package dev.xuexue.pi.coding.extensions

import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.FileOutputStream
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JvmPiModLoaderTest {
    @Test
    fun trustedJarLoadsNewModAndDiscoveryKeepsItsPackageMetadata() =
        runTest {
            val directory =
                kotlin.io.path
                    .createTempDirectory("xuexue-jvm-mod")
                    .toFile()
            val jar = directory.resolve("mod.jar")
            writeModJar(jar)
            try {
                val loader = JvmPiPluginLoader(javaClass.classLoader)
                val mod = loader.loadMod(jar.path, trusted = true)
                val manifest =
                    PiPluginManifestCodec.decode(
                        """
                        {"schemaVersion":1,"id":"example.jvm.mod","version":"1.2.3","apiVersion":"1","runtime":"jvm","entrypoint":"${TestJvmMod::class.java.name}"}
                        """.trimIndent(),
                    )
                val runtime = PiModLoader(directory.path)
                val result = runtime.load(mod, manifest.toModPackage(jar.path), trusted = true)

                assertTrue(result.loaded)
                assertEquals(
                    "example.jvm.mod",
                    result.run
                        ?.info
                        ?.id
                        ?.value,
                )
                assertEquals(
                    "1.2.3",
                    result.run
                        ?.packageInfo
                        ?.version
                        ?.value,
                )
                assertTrue(runtime.disable(ModId("example.jvm.mod")))

                val discovered =
                    PiPluginDiscovery(
                        PiPluginDiscoveryOptions(
                            cwd = directory.path,
                            agentDirectory = directory.path,
                            projectTrusted = true,
                            additionalPaths = listOf(jar.path),
                        ),
                    ).loadAll()
                assertEquals(1, discovered.mods.size)
                assertEquals(
                    "example.jvm.mod",
                    discovered.mods
                        .single()
                        .packageInfo
                        .id
                        .value,
                )
                assertEquals(emptyList(), discovered.extensions)
            } finally {
                directory.deleteRecursively()
            }
        }

    private fun writeModJar(jar: File) {
        val classPath = "/${TestJvmMod::class.java.name.replace('.', '/')}.class"
        val classBytes = requireNotNull(TestJvmMod::class.java.getResourceAsStream(classPath)).readBytes()
        JarOutputStream(FileOutputStream(jar)).use { output ->
            output.putNextEntry(JarEntry(PI_PLUGIN_MANIFEST_PATH))
            output.write(
                """
                {"schemaVersion":1,"id":"example.jvm.mod","version":"1.2.3","apiVersion":"1","runtime":"jvm","entrypoint":"${TestJvmMod::class.java.name}"}
                """.trimIndent().encodeToByteArray(),
            )
            output.closeEntry()
            output.putNextEntry(JarEntry(classPath.removePrefix("/")))
            output.write(classBytes)
            output.closeEntry()
        }
    }
}

class TestJvmMod : PiMod {
    override val id = ModId("example.jvm.mod")
    override val version = ModVersion("1.2.3")

    override suspend fun enable(context: ModContext): ModHandle? = null
}
