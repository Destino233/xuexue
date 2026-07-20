package dev.xuexue.pi.coding.resources

import dev.xuexue.pi.coding.settings.SettingsManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResourceLoaderTest {
    @Test
    fun contextFilesLoadGlobalThenRootToWorkingDirectory() {
        val fileSystem =
            ResourceTestFileSystem(
                mapOf(
                    "/agent/AGENTS.md" to "global",
                    "/AGENTS.md" to "root",
                    "/workspace/AGENTS.md" to "workspace",
                    "/workspace/app/CLAUDE.md" to "app",
                ),
            )

        val contextFiles = loadProjectContextFiles("/workspace/app", "/agent", fileSystem)

        assertEquals(
            listOf("/agent/AGENTS.md", "/AGENTS.md", "/workspace/AGENTS.md", "/workspace/app/CLAUDE.md"),
            contextFiles.map(ContextFile::path),
        )
    }

    @Test
    fun projectResourcesWinNameCollisionsAndDiagnosticsNameTheLoser() {
        val fileSystem =
            ResourceTestFileSystem(
                mapOf(
                    "/agent/skills/review/SKILL.md" to skill("user"),
                    "/workspace/.xuexue/pi/skills/review/SKILL.md" to skill("project"),
                    "/agent/prompts/commit.md" to "User prompt",
                    "/workspace/.xuexue/pi/prompts/commit.md" to "Project prompt",
                ),
            )
        val loader = loader(fileSystem)

        loader.reload()

        assertEquals(
            "project",
            loader
                .getSkills()
                .skills
                .single()
                .description,
        )
        assertEquals(
            "Project prompt",
            loader
                .getPrompts()
                .prompts
                .single()
                .content,
        )
        assertTrue(loader.getDiagnostics().any { it.type == ResourceDiagnosticType.COLLISION })
    }

    @Test
    fun untrustedProjectSkipsProjectResourcesButKeepsWorkingDirectoryContext() {
        val fileSystem =
            ResourceTestFileSystem(
                mapOf(
                    "/agent/SYSTEM.md" to "global system",
                    "/workspace/.xuexue/pi/SYSTEM.md" to "project system",
                    "/workspace/AGENTS.md" to "workspace context",
                    "/workspace/.xuexue/pi/skills/project/SKILL.md" to skill("project"),
                    "/agent/skills/user/SKILL.md" to skill("user"),
                ),
            )
        val settings = SettingsManager.inMemory(projectTrusted = false)
        val loader = loader(fileSystem, settings)

        loader.reload()

        assertEquals("global system", loader.getSystemPrompt())
        assertEquals(listOf("user"), loader.getSkills().skills.map(Skill::description))
        assertTrue(loader.getAgentsFiles().any { it.path == "/workspace/AGENTS.md" })
    }

    @Test
    fun reloadReadsProjectSystemAndAppendSystemFilesWhenTrusted() {
        val fileSystem =
            ResourceTestFileSystem(
                mapOf(
                    "/workspace/.xuexue/pi/SYSTEM.md" to "project system",
                    "/workspace/.xuexue/pi/APPEND_SYSTEM.md" to "append system",
                ),
            )
        val loader = loader(fileSystem)

        loader.reload()

        assertEquals("project system", loader.getSystemPrompt())
        assertEquals(listOf("append system"), loader.getAppendSystemPrompt())
    }

    @Test
    fun skillAndPromptDiscoveryPreservePiValidationAndFrontmatterDetails() {
        val fileSystem =
            ResourceTestFileSystem(
                mapOf(
                    "/extra/root/SKILL.md" to skill("root", disabled = true),
                    "/extra/root/nested/SKILL.md" to skill("nested"),
                    "/extra/missing/SKILL.md" to "---\nname: missing\n---\nNo description",
                    "/templates/review.md" to "---\nargument-hint: \"<path>\"\n---\nReview this file.",
                ),
            )
        val settings = SettingsManager.inMemory()
        val loader =
            DefaultResourceLoader(
                ResourceLoaderOptions(
                    cwd = "/workspace",
                    agentDirectory = "/agent",
                    settingsManager = settings,
                    additionalSkillPaths = listOf("/extra/root", "/extra/missing"),
                    additionalPromptTemplatePaths = listOf("/templates"),
                ),
                fileSystem,
            )

        loader.reload()

        assertEquals(listOf("review"), loader.getSkills().skills.map(Skill::name))
        assertTrue(
            loader
                .getSkills()
                .skills
                .single()
                .disableModelInvocation,
        )
        assertTrue(loader.getSkills().diagnostics.any { "description is required" in it.message })
        val prompt = loader.getPrompts().prompts.single()
        assertEquals("review", prompt.name)
        assertEquals("Review this file.", prompt.description)
        assertEquals("<path>", prompt.argumentHint)
        assertFalse(loader.getPrompts().diagnostics.isNotEmpty())
    }

    @Test
    fun extensionResourcesCarrySourceMetadataAndRemainLowerPriorityThanProjectResources() {
        val fileSystem =
            ResourceTestFileSystem(
                mapOf(
                    "/workspace/.xuexue/pi/skills/review/SKILL.md" to skill("project"),
                    "/extension/skills/review/SKILL.md" to skill("extension"),
                    "/extension/prompts/review.md" to "Extension prompt",
                ),
            )
        val loader = loader(fileSystem)

        loader.reload()
        loader.extendResources(
            ResourceExtensionPaths(
                skillPaths =
                    listOf(
                        ResourcePathContribution(
                            path = "/extension/skills",
                            metadata =
                                ResourcePathMetadata(
                                    source = "extension:review",
                                    scope = ResourceScope.TEMPORARY,
                                    origin = ResourceOrigin.PACKAGE,
                                    baseDirectory = "/extension/skills",
                                ),
                        ),
                    ),
                promptPaths =
                    listOf(
                        ResourcePathContribution(
                            path = "/extension/prompts/review.md",
                            metadata =
                                ResourcePathMetadata(
                                    source = "extension:review",
                                    scope = ResourceScope.TEMPORARY,
                                    origin = ResourceOrigin.PACKAGE,
                                    baseDirectory = "/extension/prompts",
                                ),
                        ),
                    ),
            ),
        )

        val selectedSkill = loader.getSkills().skills.single()
        assertEquals("project", selectedSkill.description)
        assertEquals(
            "extension:review",
            loader
                .getPrompts()
                .prompts
                .single()
                .sourceInfo.source,
        )
        assertEquals(
            ResourceOrigin.PACKAGE,
            loader
                .getPrompts()
                .prompts
                .single()
                .sourceInfo.origin,
        )
    }

    @Test
    fun skillDiscoveryHonorsNestedIgnoreFilesAndNegatedRules() {
        val fileSystem =
            ResourceTestFileSystem(
                mapOf(
                    "/extra/.gitignore" to "ignored/\n",
                    "/extra/ignored/SKILL.md" to skill("ignored", name = "ignored"),
                    "/extra/kept/SKILL.md" to skill("kept", name = "kept"),
                    "/extra/nested/.ignore" to "blocked/\n!keep/\n",
                    "/extra/nested/blocked/SKILL.md" to skill("blocked", name = "blocked"),
                    "/extra/nested/keep/SKILL.md" to skill("keep", name = "keep"),
                ),
            )
        val loader =
            DefaultResourceLoader(
                ResourceLoaderOptions(
                    cwd = "/workspace",
                    agentDirectory = "/agent",
                    settingsManager = SettingsManager.inMemory(),
                    additionalSkillPaths = listOf("/extra"),
                ),
                fileSystem,
            )

        loader.reload()

        assertEquals(listOf("kept", "keep"), loader.getSkills().skills.map(Skill::description))
    }

    private fun loader(
        fileSystem: ResourceTestFileSystem,
        settings: SettingsManager = SettingsManager.inMemory(),
    ) = DefaultResourceLoader(
        ResourceLoaderOptions(
            cwd = "/workspace",
            agentDirectory = "/agent",
            settingsManager = settings,
        ),
        fileSystem,
    )

    private fun skill(
        description: String,
        disabled: Boolean = false,
        name: String = "review",
    ): String =
        """
        ---
        name: $name
        description: $description
        disable-model-invocation: $disabled
        ---
        Skill content
        """.trimIndent()
}
