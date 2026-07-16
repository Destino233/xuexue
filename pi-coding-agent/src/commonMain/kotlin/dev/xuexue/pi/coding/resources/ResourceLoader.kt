package dev.xuexue.pi.coding.resources

import dev.xuexue.pi.coding.settings.SettingsManager

data class ResourceLoaderOptions(
    val cwd: String,
    val agentDirectory: String,
    val settingsManager: SettingsManager? = null,
    val additionalSkillPaths: List<String> = emptyList(),
    val additionalPromptTemplatePaths: List<String> = emptyList(),
    val noSkills: Boolean = false,
    val noPromptTemplates: Boolean = false,
    val noContextFiles: Boolean = false,
    val systemPrompt: String? = null,
    val appendSystemPrompt: List<String>? = null,
)

/** Headless Kotlin port of Pi's non-extension resource discovery and reload lifecycle. */
@Suppress("TooManyFunctions")
class DefaultResourceLoader(
    options: ResourceLoaderOptions,
    private val fileSystem: ResourceFileSystem = PlatformResourceFileSystem,
) {
    private val cwd = fileSystem.resolvePath(options.cwd)
    private val agentDirectory = fileSystem.resolvePath(options.agentDirectory)
    private val settingsManager = options.settingsManager ?: SettingsManager.create(cwd, agentDirectory)
    private val additionalSkillPaths = options.additionalSkillPaths
    private val additionalPromptTemplatePaths = options.additionalPromptTemplatePaths
    private val noSkills = options.noSkills
    private val noPromptTemplates = options.noPromptTemplates
    private val noContextFiles = options.noContextFiles
    private val systemPromptSource = options.systemPrompt
    private val appendSystemPromptSources = options.appendSystemPrompt
    private val skillLoader = SkillLoader(fileSystem)
    private val promptTemplateLoader = PromptTemplateLoader(fileSystem)

    private var state =
        ResourceLoaderState(
            skills = SkillsLoadResult(emptyList(), emptyList()),
            prompts = PromptTemplatesLoadResult(emptyList(), emptyList()),
            contextFiles = emptyList(),
            systemPrompt = null,
            appendSystemPrompt = emptyList(),
            diagnostics = emptyList(),
        )

    fun reload() {
        settingsManager.reload()
        val diagnostics = mutableListOf<ResourceDiagnostic>()
        val skillPaths = mergePaths(settingsManager.getSkillPaths(), additionalSkillPaths)
        val skills =
            skillLoader.load(
                cwd = cwd,
                agentDirectory = agentDirectory,
                projectTrusted = settingsManager.isProjectTrusted(),
                skillPaths = skillPaths,
                includeDefaults = !noSkills,
            )
        val promptPaths = mergePaths(settingsManager.getPromptTemplatePaths(), additionalPromptTemplatePaths)
        val prompts =
            promptTemplateLoader.load(
                cwd = cwd,
                agentDirectory = agentDirectory,
                projectTrusted = settingsManager.isProjectTrusted(),
                promptPaths = promptPaths,
                includeDefaults = !noPromptTemplates,
            )
        val contextFiles =
            if (noContextFiles) emptyList() else loadProjectContextFiles(cwd, agentDirectory, fileSystem)
        val systemPrompt =
            resolvePromptInput(
                systemPromptSource ?: discoverSystemPrompt(),
                "system prompt",
                diagnostics,
            )
        val appendSources =
            appendSystemPromptSources ?: discoverAppendSystemPrompt()
                ?.let(::listOf)
                .orEmpty()
        val appendSystemPrompt =
            appendSources.mapNotNull { source ->
                resolvePromptInput(source, "append system prompt", diagnostics)
            }
        state =
            ResourceLoaderState(
                skills = skills,
                prompts = prompts,
                contextFiles = contextFiles,
                systemPrompt = systemPrompt,
                appendSystemPrompt = appendSystemPrompt,
                diagnostics = diagnostics,
            )
    }

    fun getSkills(): SkillsLoadResult = state.skills

    fun getPrompts(): PromptTemplatesLoadResult = state.prompts

    fun getAgentsFiles(): List<ContextFile> = state.contextFiles

    fun getSystemPrompt(): String? = state.systemPrompt

    fun getAppendSystemPrompt(): List<String> = state.appendSystemPrompt

    fun getCurrentWorkingDirectory(): String = cwd

    fun readResourceText(path: String): String? = runCatching { fileSystem.readText(path) }.getOrNull()

    fun getDiagnostics(): List<ResourceDiagnostic> {
        val resourceDiagnostics = state.diagnostics
        return resourceDiagnostics + state.skills.diagnostics + state.prompts.diagnostics
    }

    private fun discoverSystemPrompt(): String? {
        val projectPrompt = discoverProjectResource("SYSTEM.md")
        return projectPrompt ?: discoverUserResource("SYSTEM.md")
    }

    private fun discoverAppendSystemPrompt(): String? {
        val projectPrompt = discoverProjectResource("APPEND_SYSTEM.md")
        return projectPrompt ?: discoverUserResource("APPEND_SYSTEM.md")
    }

    private fun discoverProjectResource(name: String): String? {
        if (!settingsManager.isProjectTrusted()) return null
        return fileSystem
            .join(fileSystem.join(cwd, PROJECT_RESOURCE_CONFIG_DIRECTORY), name)
            .takeIf(fileSystem::exists)
    }

    private fun discoverUserResource(name: String): String? {
        val path = fileSystem.join(agentDirectory, name)
        return path.takeIf(fileSystem::exists)
    }

    private fun resolvePromptInput(
        input: String?,
        description: String,
        diagnostics: MutableList<ResourceDiagnostic>,
    ): String? =
        input
            ?.takeIf(String::isNotEmpty)
            ?.let { source ->
                val path = fileSystem.resolvePath(source, cwd)
                if (!fileSystem.exists(path)) {
                    source
                } else {
                    readPromptInput(path, source, description, diagnostics)
                }
            }

    private fun readPromptInput(
        path: String,
        source: String,
        description: String,
        diagnostics: MutableList<ResourceDiagnostic>,
    ): String =
        runCatching { fileSystem.readText(path) }
            .fold(
                onSuccess = { it },
                onFailure = { error ->
                    diagnostics +=
                        ResourceDiagnostic(
                            type = ResourceDiagnosticType.WARNING,
                            message = "Could not read $description file $path: ${error.message.orEmpty()}",
                            path = path,
                        )
                    source
                },
            )

    private fun mergePaths(
        primary: List<String>,
        additional: List<String>,
    ): List<String> {
        val merged = mutableListOf<String>()
        val seenPaths = mutableSetOf<String>()
        (primary + additional).forEach { rawPath ->
            val path = fileSystem.resolvePath(rawPath, cwd)
            if (seenPaths.add(fileSystem.canonicalizePath(path))) merged += path
        }
        return merged
    }
}

fun loadProjectContextFiles(
    cwd: String,
    agentDirectory: String,
    fileSystem: ResourceFileSystem = PlatformResourceFileSystem,
): List<ContextFile> {
    val contextFiles = mutableListOf<ContextFile>()
    val seenPaths = mutableSetOf<String>()
    loadContextFileFromDirectory(fileSystem.resolvePath(agentDirectory), fileSystem)?.let { file ->
        contextFiles += file
        seenPaths += file.path
    }

    val ancestorContextFiles = mutableListOf<ContextFile>()
    var currentDirectory = fileSystem.resolvePath(cwd)
    while (true) {
        loadContextFileFromDirectory(currentDirectory, fileSystem)?.let { file ->
            if (seenPaths.add(file.path)) ancestorContextFiles += file
        }
        val parent = fileSystem.parent(currentDirectory)
        if (parent == currentDirectory) break
        currentDirectory = parent
    }
    return contextFiles + ancestorContextFiles.asReversed()
}

private fun loadContextFileFromDirectory(
    directory: String,
    fileSystem: ResourceFileSystem,
): ContextFile? {
    CONTEXT_FILE_CANDIDATES.forEach { name ->
        val path = fileSystem.join(directory, name)
        if (fileSystem.exists(path)) {
            return runCatching { ContextFile(path, fileSystem.readText(path)) }.getOrNull()
        }
    }
    return null
}

private const val PROJECT_RESOURCE_CONFIG_DIRECTORY = ".xuexue/pi"
private val CONTEXT_FILE_CANDIDATES = listOf("AGENTS.md", "AGENTS.MD", "CLAUDE.md", "CLAUDE.MD")
