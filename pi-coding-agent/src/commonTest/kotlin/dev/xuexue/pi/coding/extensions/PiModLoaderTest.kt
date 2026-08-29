package dev.xuexue.pi.coding.extensions

import dev.xuexue.pi.agent.AgentTool
import dev.xuexue.pi.agent.AgentToolResult
import dev.xuexue.pi.ai.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PiModLoaderTest {
    @Test
    fun modCanRegisterNamespacedCapabilitiesAndReceiveEvents() =
        runTest {
            val received = mutableListOf<String>()
            val loader = PiModLoader("/workspace")
            val mod =
                testMod("example.review") { context ->
                    context.tools.register("review", testTool("review"))
                    context.commands.register(
                        "inspect",
                        ModCommand("Inspect changes") { args, _ -> received += args },
                    )
                    context.events.on("review.done") { data -> received += data.toString() }
                    null
                }

            val result = loader.load(mod)

            assertTrue(result.loaded)
            assertEquals("review", loader.getToolDefinition("example.review:review")?.name)
            assertEquals("example.review:inspect", loader.getRegisteredCommands().single().id)
            loader.events.emit("review.done", "ok")
            assertTrue(loader.executeCommand("example.review:inspect", "changes"))
            assertEquals(listOf("ok", "changes"), received)
        }

    @Test
    fun reloadStopsPreviousRunBeforeInstallingReplacement() =
        runTest {
            val disabled = mutableListOf<String>()
            val loader = PiModLoader("/workspace")
            val first =
                testMod("example.reload") { context ->
                    context.tools.register("tool", testTool("first"))
                    ModHandle { disabled += "first:${context.mod.runId}" }
                }
            val firstResult = loader.load(first)
            val firstRun = requireNotNull(firstResult.run).info.runId

            val second =
                testMod("example.reload") { context ->
                    context.tools.register("tool", testTool("second"))
                    ModHandle { disabled += "second:${context.mod.runId}" }
                }
            val secondResult = loader.load(second)
            val secondRun = requireNotNull(secondResult.run).info.runId

            assertNotEquals(firstRun, secondRun)
            assertEquals(listOf("first:$firstRun"), disabled)
            assertEquals("second", loader.getAllRegisteredTools().single().label)
            assertEquals(secondRun, loader.getRuns().single().info.runId)
        }

    @Test
    fun failedEnableIsolatedAndDisableRemovesAllOwnedEntries() =
        runTest {
            val loader = PiModLoader("/workspace")
            val result =
                loader.load(
                    testMod("example.broken") { context ->
                        context.tools.register("temporary", testTool("temporary"))
                        error("enable boom")
                    },
                )

            assertFalse(result.loaded)
            assertEquals(ModRunState.FAILED, result.run?.state)
            assertTrue(result.diagnostics.any { it.cause == "enable boom" })
            assertTrue(loader.getAllRegisteredTools().isEmpty())
            assertTrue(loader.getDiagnostics().any { it.cause == "enable boom" })
        }

    @Test
    fun legacyExtensionCanBeLoadedThroughModLoader() =
        runTest {
            val loader = PiModLoader("/workspace")
            val result =
                loader.load(
                    KotlinPiExtension("legacy") {
                        registerTool(testTool("legacy-tool"))
                        null
                    },
                )

            assertTrue(result.loaded)
            assertEquals("legacy-tool", loader.getAllRegisteredTools().single().name)
            assertTrue(loader.disable(ModId("legacy")))
            assertNull(loader.getToolDefinition("legacy-tool"))
        }

    private fun testMod(
        id: String,
        enable: suspend (ModContext) -> ModHandle?,
    ): PiMod =
        object : PiMod {
            override val id = ModId(id)
            override val version = ModVersion("1.0.0")

            override suspend fun enable(context: ModContext): ModHandle? = enable(context)
        }

    private fun testTool(name: String) =
        AgentTool(
            name = name,
            label = name,
            description = name,
            parameters = buildJsonObject {},
            execute = { _, _, _, _ -> AgentToolResult(listOf(TextContent("ok")), buildJsonObject {}) },
        )
}
