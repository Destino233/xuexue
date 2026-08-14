package dev.xuexue.pi.coding

import dev.xuexue.pi.agent.AgentTool
import dev.xuexue.pi.agent.AgentToolResult
import dev.xuexue.pi.coding.extensions.KotlinPiExtension
import dev.xuexue.pi.coding.extensions.PI_PLUGIN_MANIFEST_PATH
import dev.xuexue.pi.coding.extensions.PiExtension
import dev.xuexue.pi.coding.extensions.PiExtensionProvider
import dev.xuexue.pi.coding.trust.ProjectTrustInteraction
import dev.xuexue.pi.coding.trust.ProjectTrustStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PiRuntimeBootstrapTest {
    @Test
    fun userProcessAndJvmPluginsResolveTrustBeforeProjectPluginsLoad() =
        runTest {
            val root = Files.createTempDirectory("xuexue-runtime-bootstrap").toFile()
            val agentDirectory = root.resolve("agent")
            val projectDirectory = root.resolve("project")
            val projectMarker = projectDirectory.resolve("project-initialized")
            try {
                val userExtensions = agentDirectory.resolve("extensions").apply(File::mkdirs)
                writeJvmPluginJar(userExtensions.resolve("a-user-jvm.jar"))
                val userPackage = root.resolve("user-package")
                writeTrustPlugin(
                    directory = userPackage.resolve("extensions/user-process"),
                    decision = "yes",
                    remember = true,
                    projectMarker = projectMarker,
                )
                userPackage.resolve("xuexue-pi-package.json").writeText(
                    """
                    {"schemaVersion":1,"id":"bootstrap.user.package","version":"1.0.0","extensions":["extensions/user-process"]}
                    """.trimIndent(),
                )
                val encodedUserPackagePath = JsonPrimitive(userPackage.path)
                agentDirectory.resolve("settings.json").writeText(
                    """{"packages":[$encodedUserPackagePath]}""",
                )
                writeProjectPlugin(projectDirectory, projectMarker)

                val result =
                    PiRuntimeBootstrap(
                        PiRuntimeBootstrapOptions(
                            cwd = projectDirectory.path,
                            agentDirectory = agentDirectory.path,
                        ),
                    ).prepare()

                assertNotNull(result)
                assertTrue(result.settingsManager.isProjectTrusted())
                assertEquals(2, result.userPlugins.extensions.size)
                assertEquals(1, result.projectPlugins.extensions.size)
                assertEquals(
                    setOf("bootstrap-jvm-tool", "user-process-tool", "project-tool"),
                    result.extensionRunner
                        .getAllRegisteredTools()
                        .map { it.name }
                        .toSet(),
                )
                assertTrue(projectMarker.isFile)
                assertEquals(true, ProjectTrustStore.create(agentDirectory.path).get(projectDirectory.path))
                result.extensionRunner.dispose()
            } finally {
                root.deleteRecursively()
            }
        }

    @Test
    fun rejectedTrustDisposesUserRunnerWithoutLoadingProjectPlugins() =
        runTest {
            val root = Files.createTempDirectory("xuexue-runtime-bootstrap-rejected").toFile()
            val agentDirectory = root.resolve("agent")
            val projectDirectory = root.resolve("project")
            val projectMarker = projectDirectory.resolve("project-initialized")
            val disposedMarker = root.resolve("user-runner-disposed")
            try {
                writeTrustPlugin(
                    directory = agentDirectory.resolve("extensions/user-process"),
                    decision = "no",
                    remember = true,
                    projectMarker = projectMarker,
                    disposedMarker = disposedMarker,
                )
                writeProjectPlugin(projectDirectory, projectMarker)

                val result =
                    PiRuntimeBootstrap(
                        PiRuntimeBootstrapOptions(
                            cwd = projectDirectory.path,
                            agentDirectory = agentDirectory.path,
                        ),
                    ).prepare()

                assertNull(result)
                assertFalse(projectMarker.exists())
                assertTrue(disposedMarker.isFile)
                assertEquals(false, ProjectTrustStore.create(agentDirectory.path).get(projectDirectory.path))
            } finally {
                root.deleteRecursively()
            }
        }

    @Test
    fun undecidedUserPluginFallsBackToTrustInteraction() =
        runTest {
            val root = Files.createTempDirectory("xuexue-runtime-bootstrap-undecided").toFile()
            val agentDirectory = root.resolve("agent")
            val projectDirectory = root.resolve("project")
            val projectMarker = projectDirectory.resolve("project-initialized")
            var interactionCount = 0
            try {
                writeTrustPlugin(
                    directory = agentDirectory.resolve("extensions/user-process"),
                    decision = "undecided",
                    remember = false,
                    projectMarker = projectMarker,
                )
                writeProjectPlugin(projectDirectory, projectMarker)

                val result =
                    PiRuntimeBootstrap(
                        PiRuntimeBootstrapOptions(
                            cwd = projectDirectory.path,
                            agentDirectory = agentDirectory.path,
                            interaction =
                                ProjectTrustInteraction { _, options ->
                                    interactionCount++
                                    options.indexOf("Trust (this session only)")
                                },
                        ),
                    ).prepare()

                assertNotNull(result)
                assertEquals(1, interactionCount)
                assertTrue(projectMarker.isFile)
                assertNull(ProjectTrustStore.create(agentDirectory.path).get(projectDirectory.path))
                result.extensionRunner.dispose()
            } finally {
                root.deleteRecursively()
            }
        }

    private fun writeTrustPlugin(
        directory: File,
        decision: String,
        remember: Boolean,
        projectMarker: File,
        disposedMarker: File? = null,
    ) {
        val projectMarkerPath = shellPath(projectMarker)
        val manifest = directory.resolve(PI_PLUGIN_MANIFEST_PATH)
        manifest.parentFile.mkdirs()
        manifest.writeText(processManifest("bootstrap.user.process"))
        val script = directory.resolve("plugin.sh")
        val shutdownSubscription =
            disposedMarker?.let { "echo '{\"type\":\"subscribe\",\"event\":\"SESSION_SHUTDOWN\"}'" }.orEmpty()
        val shutdownHandler =
            disposedMarker
                ?.let { marker ->
                    """
                    *'"event":"session_shutdown"'*)
                      touch '${marker.path}'
                      echo '{"type":"event_result","result":"session_shutdown"}'
                      ;;
                    """.trimIndent()
                }.orEmpty()
        script.writeText(
            """
            #!/bin/sh
            while IFS= read -r line; do
              case "${'$'}line" in
                *initialize*)
                  echo '{"type":"subscribe","event":"PROJECT_TRUST"}'
                  $shutdownSubscription
                  echo '{"type":"register_tool","name":"user-process-tool","description":"User tool","parameters":{}}'
                  echo '{"type":"ready"}'
                  ;;
                *'"event":"project_trust"'*)
                   if [ -e '$projectMarkerPath' ]; then
                    echo '{"type":"event_result","result":"project_trust","trusted":"no","remember":false}'
                  else
                    echo '{"type":"event_result","result":"project_trust","trusted":"$decision","remember":$remember}'
                  fi
                  ;;
                $shutdownHandler
                *shutdown*)
                  exit 0
                  ;;
              esac
            done
            """.trimIndent(),
        )
        check(script.setExecutable(true))
    }

    private fun writeProjectPlugin(
        projectDirectory: File,
        projectMarker: File,
    ) {
        val projectMarkerPath = shellPath(projectMarker)
        val directory = projectDirectory.resolve(".xuexue/pi/extensions/project-process")
        val manifest = directory.resolve(PI_PLUGIN_MANIFEST_PATH)
        manifest.parentFile.mkdirs()
        manifest.writeText(processManifest("bootstrap.project.process"))
        val script = directory.resolve("plugin.sh")
        script.writeText(
            """
            #!/bin/sh
            while IFS= read -r line; do
              case "${'$'}line" in
                *initialize*)
                   touch '$projectMarkerPath'
                  echo '{"type":"register_tool","name":"project-tool","description":"Project tool","parameters":{}}'
                  echo '{"type":"ready"}'
                  ;;
                *shutdown*)
                  exit 0
                  ;;
              esac
            done
            """.trimIndent(),
        )
        check(script.setExecutable(true))
    }

    private fun shellPath(file: File): String {
        val path =
            file.absoluteFile
                .toPath()
                .normalize()
                .toString()
                .replace('\\', '/')
        return if (isWindows() && path.length >= 2 && path[1] == ':') {
            "/${path[0].lowercaseChar()}${path.substring(2)}"
        } else {
            path
        }
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").orEmpty().contains("win", ignoreCase = true)

    private fun writeJvmPluginJar(jar: File) {
        val classPath = "/${BootstrapJvmPluginProvider::class.java.name.replace('.', '/')}.class"
        val classBytes =
            requireNotNull(BootstrapJvmPluginProvider::class.java.getResourceAsStream(classPath))
                .readBytes()
        JarOutputStream(FileOutputStream(jar)).use { output ->
            output.putNextEntry(JarEntry(PI_PLUGIN_MANIFEST_PATH))
            output.write(
                """
                {"schemaVersion":1,"id":"bootstrap.user.jvm","version":"1.0.0","apiVersion":"1","runtime":"jvm","entrypoint":"${BootstrapJvmPluginProvider::class.java.name}"}
                """.trimIndent().encodeToByteArray(),
            )
            output.closeEntry()
            output.putNextEntry(JarEntry(classPath.removePrefix("/")))
            output.write(classBytes)
            output.closeEntry()
        }
    }

    private fun processManifest(id: String): String =
        """
        {"schemaVersion":1,"id":"$id","version":"1.0.0","apiVersion":"1","runtime":"process","executable":"plugin.sh"}
        """.trimIndent()
}

class BootstrapJvmPluginProvider : PiExtensionProvider {
    override fun createPiExtension(): PiExtension =
        KotlinPiExtension("bootstrap.user.jvm") {
            registerTool(
                AgentTool(
                    name = "bootstrap-jvm-tool",
                    label = "Bootstrap JVM tool",
                    description = "Bootstrap JVM tool",
                    parameters = buildJsonObject {},
                    execute = { _, _, _, _ -> AgentToolResult(emptyList(), buildJsonObject {}) },
                ),
            )
            null
        }
}
