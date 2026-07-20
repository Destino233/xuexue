package dev.xuexue.pi.coding.resources

private const val MAX_SKILL_NAME_LENGTH = 64
private const val MAX_SKILL_DESCRIPTION_LENGTH = 1_024

class SkillLoader(
    private val fileSystem: ResourceFileSystem,
) {
    fun load(
        cwd: String,
        agentDirectory: String,
        projectTrusted: Boolean,
        skillPaths: List<String>,
        includeDefaults: Boolean,
    ): SkillsLoadResult {
        val projectConfigDirectory = fileSystem.join(cwd, PROJECT_CONFIG_DIRECTORY)
        val projectDirectory = fileSystem.join(projectConfigDirectory, "skills")
        val userDirectory = fileSystem.join(agentDirectory, "skills")
        val locations = mutableListOf<SkillLocation>()
        if (includeDefaults && projectTrusted) locations += SkillLocation(projectDirectory, ResourceScope.PROJECT)
        if (includeDefaults) locations += SkillLocation(userDirectory, ResourceScope.USER)
        locations +=
            skillPaths.map { path ->
                SkillLocation(fileSystem.resolvePath(path, cwd), ResourceScope.TEMPORARY)
            }

        val result = SkillsLoadResultBuilder()
        locations.forEach { location ->
            when {
                !fileSystem.exists(location.path) && location.scope == ResourceScope.TEMPORARY -> {
                    result.diagnostics += diagnostic("skill path does not exist", location.path)
                }

                !fileSystem.exists(location.path) -> {}

                else -> {
                    result.add(loadLocation(location))
                }
            }
        }
        return result.deduplicated(fileSystem)
    }

    private fun loadLocation(location: SkillLocation): SkillsLoadResult =
        if (fileSystem.isDirectory(location.path)) {
            loadDirectory(
                directory = location.path,
                scope = location.scope,
                includeRootFiles = true,
                rootDirectory = location.path,
                ignoreMatcher = ResourceIgnoreMatcher(fileSystem, location.path),
            )
        } else {
            loadFile(location.path, location.scope)
        }

    private fun loadDirectory(
        directory: String,
        scope: ResourceScope,
        includeRootFiles: Boolean,
        rootDirectory: String,
        ignoreMatcher: ResourceIgnoreMatcher,
    ): SkillsLoadResult {
        ignoreMatcher.addRules(directory)
        val entries = fileSystem.listDirectory(directory)
        val rootSkill =
            entries.firstOrNull { entry ->
                entry.name == SKILL_FILE_NAME &&
                    entry.isFile &&
                    !ignoreMatcher.ignores(relativePath(rootDirectory, entry.path))
            }
        if (rootSkill != null) return loadFile(rootSkill.path, scope)

        val result = SkillsLoadResultBuilder()
        entries.forEach { entry ->
            val relativeEntryPath = relativePath(rootDirectory, entry.path)
            when {
                entry.name.startsWith(".") || entry.name == "node_modules" -> {}

                ignoreMatcher.ignores(relativeEntryPath) -> {}

                entry.isDirectory -> {
                    result.add(
                        loadDirectory(
                            directory = entry.path,
                            scope = scope,
                            includeRootFiles = false,
                            rootDirectory = rootDirectory,
                            ignoreMatcher = ignoreMatcher,
                        ),
                    )
                }

                entry.isFile && includeRootFiles && entry.name.endsWith(".md") -> {
                    result.add(loadFile(entry.path, scope))
                }
            }
        }
        return result.build()
    }

    private fun relativePath(
        rootDirectory: String,
        path: String,
    ): String {
        val root = fileSystem.canonicalizePath(rootDirectory).trimEnd('/')
        val normalizedPath = fileSystem.canonicalizePath(path).trimEnd('/')
        return if (normalizedPath == root) "" else normalizedPath.removePrefix("$root/")
    }

    private fun loadFile(
        path: String,
        scope: ResourceScope,
    ): SkillsLoadResult {
        if (!path.endsWith(".md")) {
            return SkillsLoadResult(emptyList(), listOf(diagnostic("skill path is not a markdown file", path)))
        }
        return runCatching { fileSystem.readText(path) }
            .fold(
                onSuccess = { content -> loadSkill(path, content, scope) },
                onFailure = { error ->
                    SkillsLoadResult(
                        emptyList(),
                        listOf(diagnostic(error.message ?: "failed to parse skill file", path)),
                    )
                },
            )
    }

    private fun loadSkill(
        path: String,
        content: String,
        scope: ResourceScope,
    ): SkillsLoadResult =
        runCatching { parseFrontmatter(content) }
            .fold(
                onSuccess = { frontmatter -> buildSkill(path, frontmatter, scope) },
                onFailure = { error ->
                    SkillsLoadResult(
                        emptyList(),
                        listOf(diagnostic(error.message ?: "failed to parse skill file", path)),
                    )
                },
            )

    private fun buildSkill(
        path: String,
        frontmatter: ParsedFrontmatter,
        scope: ResourceScope,
    ): SkillsLoadResult {
        val diagnostics = mutableListOf<ResourceDiagnostic>()
        val description = frontmatter.fields.string("description")
        validateDescription(description).forEach { message ->
            diagnostics += diagnostic(message, path)
        }
        val baseDirectory = fileSystem.parent(path)
        val name = frontmatter.fields.string("name") ?: baseDirectory.fileName()
        validateName(name).forEach { message -> diagnostics += diagnostic(message, path) }
        if (description.isNullOrBlank()) return SkillsLoadResult(emptyList(), diagnostics)
        val skill =
            Skill(
                name = name,
                description = description,
                filePath = path,
                baseDirectory = baseDirectory,
                sourceInfo =
                    ResourceSourceInfo(
                        path = path,
                        source = "local",
                        scope = scope,
                        baseDirectory = baseDirectory,
                    ),
                disableModelInvocation =
                    frontmatter.fields.boolean("disable-model-invocation") ?: false,
            )
        return SkillsLoadResult(listOf(skill), diagnostics)
    }
}

private data class SkillLocation(
    val path: String,
    val scope: ResourceScope,
)

private class SkillsLoadResultBuilder {
    val skills = mutableListOf<Skill>()
    val diagnostics = mutableListOf<ResourceDiagnostic>()

    fun add(result: SkillsLoadResult) {
        skills += result.skills
        diagnostics += result.diagnostics
    }

    fun build(): SkillsLoadResult = SkillsLoadResult(skills, diagnostics)

    fun deduplicated(fileSystem: ResourceFileSystem): SkillsLoadResult {
        val selectedSkills = linkedMapOf<String, Skill>()
        val seenPaths = mutableSetOf<String>()
        skills.forEach { skill ->
            val canonicalPath = fileSystem.canonicalizePath(skill.filePath)
            if (!seenPaths.add(canonicalPath)) return@forEach
            val winner = selectedSkills[skill.name]
            if (winner == null) {
                selectedSkills[skill.name] = skill
            } else {
                diagnostics +=
                    ResourceDiagnostic(
                        type = ResourceDiagnosticType.COLLISION,
                        message = "name \"${skill.name}\" collision",
                        path = skill.filePath,
                        collision =
                            ResourceCollision(
                                resourceType = "skill",
                                name = skill.name,
                                winnerPath = winner.filePath,
                                loserPath = skill.filePath,
                            ),
                    )
            }
        }
        return SkillsLoadResult(selectedSkills.values.toList(), diagnostics)
    }
}

private fun validateName(name: String): List<String> =
    buildList {
        if (name.length > MAX_SKILL_NAME_LENGTH) add("name exceeds 64 characters (${name.length})")
        if (!SKILL_NAME_PATTERN.matches(name)) {
            add("name contains invalid characters (must be lowercase a-z, 0-9, hyphens only)")
        }
        if (name.startsWith('-') || name.endsWith('-')) add("name must not start or end with a hyphen")
        if ("--" in name) add("name must not contain consecutive hyphens")
    }

private fun validateDescription(description: String?): List<String> =
    when {
        description.isNullOrBlank() -> {
            listOf("description is required")
        }

        description.length > MAX_SKILL_DESCRIPTION_LENGTH -> {
            listOf("description exceeds 1024 characters (${description.length})")
        }

        else -> {
            emptyList()
        }
    }

private fun diagnostic(
    message: String,
    path: String,
): ResourceDiagnostic =
    ResourceDiagnostic(
        type = ResourceDiagnosticType.WARNING,
        message = message,
        path = path,
    )

private fun String.fileName(): String = substringAfterLast('/').substringAfterLast('\\')

private const val PROJECT_CONFIG_DIRECTORY = ".xuexue/pi"
private const val SKILL_FILE_NAME = "SKILL.md"
private val SKILL_NAME_PATTERN = Regex("^[a-z0-9-]+$")
