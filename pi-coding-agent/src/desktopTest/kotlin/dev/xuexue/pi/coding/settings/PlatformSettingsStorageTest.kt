package dev.xuexue.pi.coding.settings

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlatformSettingsStorageTest {
    @Test
    fun projectSettingsUseXueXueDirectoryAndCreateItOnlyOnWrite() {
        val root = Files.createTempDirectory("xuexue-settings-")
        try {
            val agentDirectory = Files.createDirectories(root.resolve("agent"))
            val projectDirectory = Files.createDirectories(root.resolve("project"))
            Files.writeString(agentDirectory.resolve("settings.json"), """{"theme":"global"}""", StandardCharsets.UTF_8)

            val manager = SettingsManager.create(projectDirectory.toString(), agentDirectory.toString())
            val projectSettings = projectDirectory.resolve(".xuexue").resolve("pi").resolve("settings.json")

            assertEquals("global", manager.getTheme())
            assertFalse(Files.exists(projectSettings))

            manager.setProjectExtensionPaths(listOf("extensions/example.jar"))

            assertTrue(Files.exists(projectSettings))
            assertTrue(Files.readString(projectSettings).contains("extensions/example.jar"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
