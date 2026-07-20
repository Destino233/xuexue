package dev.xuexue.pi.coding.packages

import dev.xuexue.pi.coding.settings.SettingsManager
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DefaultPiPackageManagerTest {
    @Test
    fun resolvesLocalPackageResourcesAndPersistsProjectSource() =
        runTest {
            val root = Files.createTempDirectory("xuexue-package-")
            try {
                val packageRoot = Files.createDirectories(root.resolve("review-package"))
                Files.writeString(
                    packageRoot.resolve("xuexue-pi-package.json"),
                    """
                    {"schemaVersion":1,"id":"review.package","version":"1.0.0","extensions":["extensions"],"skills":["skills"],"prompts":["prompts"]}
                    """.trimIndent(),
                )
                Files.createDirectories(packageRoot.resolve("extensions"))
                Files.createDirectories(packageRoot.resolve("skills"))
                Files.createDirectories(packageRoot.resolve("prompts"))
                val settings = SettingsManager.inMemory(projectTrusted = true)
                val manager =
                    DefaultPiPackageManager(
                        root.toString(),
                        root.resolve("agent").toString(),
                        settings,
                        offlineOverride = true,
                    )
                val installed =
                    manager.install(
                        PiPackageSpec(packageRoot.toString()),
                        PiPackageScope.PROJECT,
                        persist = true,
                    )
                assertEquals("review.package", installed.record.manifest.id)
                val configured = settings.getProjectSettings()["packages"] as JsonArray
                assertEquals(1, configured.size)

                val resolution = manager.resolve()
                assertEquals(1, resolution.packages.size)
                assertEquals(1, resolution.extensionPaths.size)
                assertEquals(packageRoot.resolve("extensions").toString(), resolution.extensionPaths.single().path)
                assertEquals(1, resolution.resources.skillPaths.size)
                assertEquals(1, resolution.resources.promptPaths.size)
                assertTrue(
                    resolution.packages
                        .single()
                        .contentHash
                        .isNotBlank(),
                )
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    @Test
    fun offlineMissingRemotePackageProducesDiagnosticAndInstallFailsClearly() =
        runTest {
            val root = Files.createTempDirectory("xuexue-package-offline-")
            try {
                val settings = SettingsManager.inMemory(projectTrusted = true)
                settings.setPackages(listOf(JsonPrimitive("git:github.com/example/missing")))
                val manager =
                    DefaultPiPackageManager(
                        root.toString(),
                        root.resolve("agent").toString(),
                        settings,
                        offlineOverride = true,
                    )
                val resolution = manager.resolve()
                assertEquals(1, resolution.diagnostics.size)
                assertTrue(
                    resolution.diagnostics
                        .single()
                        .message
                        .contains("Offline mode"),
                )
                assertFailsWith<IllegalStateException> {
                    manager.install(PiPackageSpec("git:github.com/example/missing"))
                }
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    @Test
    fun firstInstallDoesNotReportADeletedBackupPath() =
        runTest {
            val root = Files.createTempDirectory("xuexue-package-first-install-")
            try {
                val packageRoot = Files.createDirectories(root.resolve("package"))
                Files.writeString(
                    packageRoot.resolve("xuexue-pi-package.json"),
                    """{"schemaVersion":1,"id":"first","version":"1.0.0"}""",
                )
                val manager =
                    DefaultPiPackageManager(
                        root.toString(),
                        root.resolve("agent").toString(),
                        SettingsManager.inMemory(projectTrusted = true),
                        offlineOverride = true,
                    )

                val result = manager.install(PiPackageSpec(packageRoot.toString()))

                assertEquals(null, result.replacedPath)
                assertTrue(
                    Files.isDirectory(
                        java.nio.file.Path
                            .of(result.record.installedPath),
                    ),
                )
            } finally {
                root.toFile().deleteRecursively()
            }
        }
}
