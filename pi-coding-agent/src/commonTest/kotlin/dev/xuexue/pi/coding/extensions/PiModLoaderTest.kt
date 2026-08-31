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
            val activeRun = loader.getRuns().single()
            assertEquals(
                secondRun,
                activeRun.info.runId,
            )
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

    @Test
    fun uiContributionsAreInvokableAndOwnedByTheRun() =
        runTest {
            var invoked = 0
            val loader = PiModLoader("/workspace")
            val result =
                loader.load(
                    testMod("example.ui") { context ->
                        context.ui.registerPanel(UiPanelContribution("review", "Review", UiSlot.RIGHT_DOCK))
                        context.ui.registerAction(
                            UiActionContribution(
                                id = "run",
                                label = "Run review",
                                location = UiSlot.TOOLBAR,
                                invoke = { invoked++ },
                            ),
                        )
                        null
                    },
                )

            assertTrue(result.loaded)
            assertEquals("example.ui:review", loader.getUiPanels().single().id)
            assertTrue(loader.invokeUiAction("example.ui:run"))
            assertEquals(1, invoked)
            assertTrue(loader.disable(ModId("example.ui")))
            assertTrue(loader.getUiPanels().isEmpty())
            assertTrue(loader.getUiActions().isEmpty())
            assertFalse(loader.invokeUiAction("example.ui:run"))
        }

    @Test
    fun dependencyResolutionSelectsHighestCompatibleVersionAndDropsCycles() {
        val mod = { id: String, version: String, dependencies: List<ModDependency> ->
            ModCandidate(
                ModPackage(
                    id = ModId(id),
                    version = ModVersion(version),
                    apiVersion = PI_PLUGIN_API_VERSION,
                    runtime = PiPluginRuntime.JVM,
                    dependencies = dependencies,
                ),
                id,
            )
        }
        val resolution =
            ModDependencyResolver.resolve(
                listOf(
                    mod("example.api", "1.0.0", emptyList()),
                    mod("example.api", "2.0.0", emptyList()),
                    mod("example.feature", "1.0.0", listOf(ModDependency(ModId("example.api"), ModVersionRange.AtLeast(ModVersion("2.0.0"))))),
                    mod("example.a", "1.0.0", listOf(ModDependency(ModId("example.b")))),
                    mod("example.b", "1.0.0", listOf(ModDependency(ModId("example.a")))),
                ),
            )

        assertEquals(listOf("example.api", "example.feature"), resolution.selected.map { it.value })
        assertTrue(resolution.diagnostics.any { it.modId == ModId("example.a") })
        assertTrue(resolution.diagnostics.any { it.modId == ModId("example.b") })
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
