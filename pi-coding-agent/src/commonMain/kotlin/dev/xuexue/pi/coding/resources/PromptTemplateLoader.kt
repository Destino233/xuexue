package dev.xuexue.pi.coding.resources

class PromptTemplateLoader(
    private val fileSystem: ResourceFileSystem,
) {
    fun load(
        cwd: String,
        agentDirectory: String,
        projectTrusted: Boolean,
        promptPaths: List<String>,
        includeDefaults: Boolean,
    ): PromptTemplatesLoadResult {
        val projectDirectory = fileSystem.join(fileSystem.join(cwd, PROJECT_PROMPT_CONFIG_DIRECTORY), "prompts")
        val userDirectory = fileSystem.join(agentDirectory, "prompts")
        val locations = mutableListOf<PromptLocation>()
        if (includeDefaults && projectTrusted) locations += PromptLocation(projectDirectory, ResourceScope.PROJECT)
        if (includeDefaults) locations += PromptLocation(userDirectory, ResourceScope.USER)
        locations +=
            promptPaths.map { path ->
                PromptLocation(fileSystem.resolvePath(path, cwd), ResourceScope.TEMPORARY)
            }

        val result = PromptTemplatesLoadResultBuilder()
        locations.forEach { location ->
            when {
                !fileSystem.exists(location.path) && location.scope == ResourceScope.TEMPORARY -> {
                    result.diagnostics += warning("prompt template path does not exist", location.path)
                }

                !fileSystem.exists(location.path) -> {}

                fileSystem.isDirectory(location.path) -> {
                    result.add(loadDirectory(location.path, location.scope))
                }

                fileSystem.isFile(location.path) && location.path.endsWith(".md") -> {
                    result.add(loadFile(location.path, location.scope))
                }

                else -> {
                    result.diagnostics += warning("prompt template path is not a markdown file", location.path)
                }
            }
        }
        return result.deduplicated(fileSystem)
    }

    private fun loadDirectory(
        directory: String,
        scope: ResourceScope,
    ): PromptTemplatesLoadResult {
        val result = PromptTemplatesLoadResultBuilder()
        fileSystem
            .listDirectory(directory)
            .filter { it.isFile && it.name.endsWith(".md") }
            .forEach { entry -> result.add(loadFile(entry.path, scope)) }
        return result.build()
    }

    private fun loadFile(
        path: String,
        scope: ResourceScope,
    ): PromptTemplatesLoadResult =
        runCatching { fileSystem.readText(path) }
            .fold(
                onSuccess = { content -> parseTemplate(path, content, scope) },
                onFailure = { error ->
                    PromptTemplatesLoadResult(
                        emptyList(),
                        listOf(warning(error.message ?: "failed to read prompt template", path)),
                    )
                },
            )

    private fun parseTemplate(
        path: String,
        content: String,
        scope: ResourceScope,
    ): PromptTemplatesLoadResult =
        runCatching { parseFrontmatter(content) }
            .fold(
                onSuccess = { parsed ->
                    val description = parsed.fields.string("description") ?: firstDescriptionLine(parsed.body)
                    PromptTemplatesLoadResult(
                        prompts =
                            listOf(
                                PromptTemplate(
                                    name = path.fileName().removeSuffix(".md"),
                                    description = description,
                                    argumentHint = parsed.fields.string("argument-hint"),
                                    content = parsed.body,
                                    sourceInfo =
                                        ResourceSourceInfo(
                                            path = path,
                                            source = "local",
                                            scope = scope,
                                            baseDirectory = fileSystem.parent(path),
                                        ),
                                    filePath = path,
                                ),
                            ),
                        diagnostics = emptyList(),
                    )
                },
                onFailure = { error ->
                    PromptTemplatesLoadResult(
                        emptyList(),
                        listOf(warning(error.message ?: "failed to parse prompt template", path)),
                    )
                },
            )
}

private data class PromptLocation(
    val path: String,
    val scope: ResourceScope,
)

private class PromptTemplatesLoadResultBuilder {
    val prompts = mutableListOf<PromptTemplate>()
    val diagnostics = mutableListOf<ResourceDiagnostic>()

    fun add(result: PromptTemplatesLoadResult) {
        prompts += result.prompts
        diagnostics += result.diagnostics
    }

    fun build(): PromptTemplatesLoadResult = PromptTemplatesLoadResult(prompts, diagnostics)

    fun deduplicated(fileSystem: ResourceFileSystem): PromptTemplatesLoadResult {
        val selectedPrompts = linkedMapOf<String, PromptTemplate>()
        val seenPaths = mutableSetOf<String>()
        prompts.forEach { prompt ->
            if (!seenPaths.add(fileSystem.canonicalizePath(prompt.filePath))) return@forEach
            val winner = selectedPrompts[prompt.name]
            if (winner == null) {
                selectedPrompts[prompt.name] = prompt
            } else {
                diagnostics +=
                    ResourceDiagnostic(
                        type = ResourceDiagnosticType.COLLISION,
                        message = "name \"/${prompt.name}\" collision",
                        path = prompt.filePath,
                        collision =
                            ResourceCollision(
                                resourceType = "prompt",
                                name = prompt.name,
                                winnerPath = winner.filePath,
                                loserPath = prompt.filePath,
                            ),
                    )
            }
        }
        return PromptTemplatesLoadResult(selectedPrompts.values.toList(), diagnostics)
    }
}

private fun firstDescriptionLine(content: String): String =
    content
        .lineSequence()
        .firstOrNull { it.isNotBlank() }
        ?.let { line -> line.take(60).let { if (line.length > 60) "$it..." else it } }
        .orEmpty()

private fun warning(
    message: String,
    path: String,
): ResourceDiagnostic =
    ResourceDiagnostic(
        type = ResourceDiagnosticType.WARNING,
        message = message,
        path = path,
    )

private fun String.fileName(): String = substringAfterLast('/').substringAfterLast('\\')

private const val PROJECT_PROMPT_CONFIG_DIRECTORY = ".xuexue/pi"
