package dev.xuexue.pi.coding.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsManagerTest {
    @Test
    fun mergesProjectNestedSettingsOverGlobalDefaults() {
        val manager =
            manager(
                global =
                    """
                    {"compaction":{"reserveTokens":4096,"keepRecentTokens":8000},"retry":{"maxRetries":6}}
                    """.trimIndent(),
                project =
                    """
                    {"compaction":{"enabled":false},"retry":{"baseDelayMs":3000}}
                    """.trimIndent(),
            )

        assertEquals(false, manager.getCompactionSettings().enabled)
        assertEquals(4_096, manager.getCompactionSettings().reserveTokens)
        assertEquals(8_000, manager.getCompactionSettings().keepRecentTokens)
        assertEquals(6, manager.getRetrySettings().maxRetries)
        assertEquals(3_000, manager.getRetrySettings().baseDelayMs)
    }

    @Test
    fun migratesPiLegacySettingsWithoutDiscardingResourcePaths() {
        val manager =
            manager(
                global =
                    """
                    {
                      "queueMode":"all",
                      "websockets":false,
                      "skills":{"enableSkillCommands":false,"customDirectories":["skills/custom"]},
                      "retry":{"maxDelayMs":1234}
                    }
                    """.trimIndent(),
            )

        assertEquals("all", manager.getSteeringMode())
        assertEquals("sse", manager.getSetting("transport")?.jsonPrimitive?.content)
        assertEquals(listOf("skills/custom"), manager.getSkillPaths())
        val retry = manager.getSettings()["retry"]?.jsonObject
        assertEquals(
            1_234,
            retry
                ?.get("provider")
                ?.jsonObject
                ?.get("maxRetryDelayMs")
                ?.jsonPrimitive
                ?.content
                ?.toInt(),
        )
    }

    @Test
    fun preservesExternalFieldsWhenSavingAnUnrelatedGlobalSetting() {
        val storage =
            InMemorySettingsStorage(
                global = """{"theme":"dark","packages":["maven:old"]}""",
            )
        val manager = SettingsManager.fromStorage(storage)
        storage.replace(SettingsScope.GLOBAL, """{"theme":"dark","packages":[]}""")

        manager.setTheme("light")

        val persisted = parseObject(requireNotNull(storage.content(SettingsScope.GLOBAL)))
        assertEquals(emptyList(), persisted["packages"]?.jsonArray?.toList())
        assertEquals("light", persisted["theme"]?.jsonPrimitive?.content)
    }

    @Test
    fun rejectsProjectWritesUntilProjectIsTrustedThenLoadsProjectSettings() {
        val storage =
            InMemorySettingsStorage(
                global = """{"theme":"global"}""",
                project = """{"theme":"project"}""",
            )
        val manager = SettingsManager.fromStorage(storage, projectTrusted = false)

        assertFalse(manager.isProjectTrusted())
        assertEquals("global", manager.getTheme())
        assertFailsWith<IllegalStateException> {
            manager.setProjectExtensionPaths(listOf("extensions/local.jar"))
        }

        manager.setProjectTrusted(true)

        assertTrue(manager.isProjectTrusted())
        assertEquals("project", manager.getTheme())
    }

    @Test
    fun reloadKeepsValidSettingsWhenAnExternalFileIsInvalidAndReportsTheError() {
        val storage = InMemorySettingsStorage(global = """{"theme":"dark"}""")
        val manager = SettingsManager.fromStorage(storage)
        storage.replace(SettingsScope.GLOBAL, "{ invalid json")

        manager.reload()

        assertEquals("dark", manager.getTheme())
        assertEquals(listOf(SettingsScope.GLOBAL), manager.drainErrors().map(SettingsError::scope))
        assertTrue(manager.drainErrors().isEmpty())
    }

    @Test
    fun agentSessionSettingsPersistOnlyChangedNestedFields() {
        val storage =
            InMemorySettingsStorage(
                global =
                    """
                    {"retry":{"maxRetries":7},"compaction":{"reserveTokens":6000}}
                    """.trimIndent(),
            )
        val manager = SettingsManager.fromStorage(storage)

        manager.setRetryEnabled(false)
        manager.setCompactionEnabled(false)

        assertEquals(false, manager.getRetrySettings().enabled)
        assertEquals(7, manager.getRetrySettings().maxRetries)
        assertEquals(false, manager.getCompactionSettings().enabled)
        assertEquals(6_000, manager.getCompactionSettings().reserveTokens)
        val persisted = parseObject(requireNotNull(storage.content(SettingsScope.GLOBAL)))
        assertEquals(
            7,
            persisted["retry"]
                ?.jsonObject
                ?.get("maxRetries")
                ?.jsonPrimitive
                ?.content
                ?.toInt(),
        )
        assertEquals(
            6_000,
            persisted["compaction"]
                ?.jsonObject
                ?.get("reserveTokens")
                ?.jsonPrimitive
                ?.content
                ?.toInt(),
        )
    }

    @Test
    fun inMemorySettingsSurviveReload() {
        val manager = SettingsManager.inMemory(globalSettings = parseObject("""{"extensions":["tools/demo"]}"""))

        manager.reload()

        assertEquals(listOf("tools/demo"), manager.getExtensionPaths())
    }

    private fun manager(
        global: String,
        project: String = "{}",
    ): SettingsManager =
        SettingsManager.fromStorage(
            InMemorySettingsStorage(
                global = global,
                project = project,
            ),
        )

    private fun parseObject(content: String): JsonObject = Json.parseToJsonElement(content).jsonObject
}
