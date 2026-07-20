package dev.xuexue.pi.coding

import dev.xuexue.pi.agent.AgentTool
import dev.xuexue.pi.coding.resources.DefaultResourceLoader
import dev.xuexue.pi.coding.resources.ResourceLoaderOptions
import dev.xuexue.pi.coding.resources.ResourceTestFileSystem
import dev.xuexue.pi.coding.settings.SettingsManager
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentSessionResourcesTest {
    @Test
    fun sessionBuildsResourcePromptAndExpandsSkillAndTemplateCommands() =
        runTest {
            val fileSystem =
                ResourceTestFileSystem(
                    mapOf(
                        "/workspace/AGENTS.md" to "workspace instructions",
                        "/workspace/.xuexue/pi/SYSTEM.md" to "project system",
                        "/workspace/.xuexue/pi/APPEND_SYSTEM.md" to "append system",
                        "/agent/skills/review/SKILL.md" to
                            """
                            ---
                            name: review
                            description: Review source files
                            ---
                            Review the target carefully.
                            """.trimIndent(),
                        "/agent/prompts/template.md" to "Template ${'$'}1",
                    ),
                )
            val resourceLoader =
                DefaultResourceLoader(
                    ResourceLoaderOptions(
                        cwd = "/workspace",
                        agentDirectory = "/agent",
                        settingsManager = SettingsManager.inMemory(),
                    ),
                    fileSystem,
                )
            val contexts = mutableListOf<dev.xuexue.pi.ai.Context>()
            val harness =
                AgentSessionHarness(
                    responses =
                        listOf(
                            { context ->
                                contexts += context
                                testAssistant("first")
                            },
                            { context ->
                                contexts += context
                                testAssistant("second")
                            },
                        ),
                    tools = listOf(promptAwareReadTool()),
                    resourceLoader = resourceLoader,
                )

            harness.session.prompt("/skill:review src/Main.kt")
            harness.session.prompt("/template value")

            val systemPrompt = requireNotNull(contexts.first().systemPrompt)
            assertTrue(systemPrompt.contains("project system"))
            assertTrue(systemPrompt.contains("append system"))
            assertTrue(systemPrompt.contains("workspace instructions"))
            assertTrue(systemPrompt.contains("<available_skills>"))
            assertTrue(systemPrompt.contains("Available tools").not())
            assertTrue(systemPrompt.contains("Guidelines").not())
            assertEquals(
                """
                <skill name="review" location="/agent/skills/review/SKILL.md">
                References are relative to /agent/skills/review.

                Review the target carefully.
                </skill>

                src/Main.kt
                """.trimIndent(),
                messageText(contexts[0].messages.last()),
            )
            assertEquals(
                "Template value",
                messageText(contexts[1].messages.last()),
            )
        }

    @Test
    fun defaultResourcePromptIncludesToolMetadata() =
        runTest {
            val resourceLoader =
                DefaultResourceLoader(
                    ResourceLoaderOptions(
                        cwd = "/workspace",
                        agentDirectory = "/agent",
                        settingsManager = SettingsManager.inMemory(),
                    ),
                    ResourceTestFileSystem(emptyMap()),
                )
            val contexts = mutableListOf<dev.xuexue.pi.ai.Context>()
            val harness =
                AgentSessionHarness(
                    responses =
                        listOf({ context ->
                            contexts += context
                            testAssistant("done")
                        }),
                    tools = listOf(promptAwareReadTool()),
                    resourceLoader = resourceLoader,
                )

            harness.session.prompt("inspect")

            val systemPrompt = requireNotNull(contexts.single().systemPrompt)
            assertTrue(systemPrompt.contains("- read: Read a file"))
            assertTrue(systemPrompt.contains("- Read only project files"))
        }

    @Test
    fun resourceReloadRebuildsTheSystemPrompt() {
        val fileSystem =
            ResourceTestFileSystem(
                mapOf("/workspace/.xuexue/pi/SYSTEM.md" to "first system"),
            )
        val resourceLoader =
            DefaultResourceLoader(
                ResourceLoaderOptions(
                    cwd = "/workspace",
                    agentDirectory = "/agent",
                    settingsManager = SettingsManager.inMemory(),
                ),
                fileSystem,
            )
        val harness = AgentSessionHarness(resourceLoader = resourceLoader)

        fileSystem.replace("/workspace/.xuexue/pi/SYSTEM.md", "second system")
        val diagnostics = harness.session.reloadResources()

        assertTrue(diagnostics.isEmpty())
        assertTrue(harness.session.systemPrompt.contains("second system"))
        assertEquals(harness.session.systemPrompt, harness.session.currentState.agent.systemPrompt)
    }

    private fun promptAwareReadTool() =
        AgentTool(
            name = "read",
            label = "Read",
            description = "Read a file",
            parameters = buildJsonObject {},
            execute = { _, _, _, _ -> error("The test does not call tools") },
            promptSnippet = "Read a file",
            promptGuidelines = listOf("Read only project files"),
        )
}
