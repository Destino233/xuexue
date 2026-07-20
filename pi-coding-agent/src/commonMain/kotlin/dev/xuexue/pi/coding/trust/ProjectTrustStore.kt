package dev.xuexue.pi.coding.trust

import dev.xuexue.pi.coding.extensions.PiExtensionContext
import dev.xuexue.pi.coding.extensions.PiExtensionRunner
import dev.xuexue.pi.coding.extensions.ProjectTrustDecision
import dev.xuexue.pi.coding.resources.PlatformResourceFileSystem
import dev.xuexue.pi.coding.resources.ResourceFileSystem
import dev.xuexue.pi.coding.settings.DefaultProjectTrust

typealias ProjectTrustValue = Boolean?

data class ProjectTrustStoreEntry(
    val path: String,
    val decision: Boolean,
)

data class ProjectTrustUpdate(
    val path: String,
    val decision: ProjectTrustValue,
)

data class ProjectTrustOption(
    val label: String,
    val trusted: Boolean,
    val updates: List<ProjectTrustUpdate>,
    val savedPath: String? = null,
)

/** Pi-compatible persistent trust decisions with nearest-ancestor inheritance. */
class ProjectTrustStore(
    private val storage: ProjectTrustStorage,
    private val fileSystem: ResourceFileSystem = PlatformResourceFileSystem,
) {
    companion object {
        fun create(
            agentDirectory: String,
            fileSystem: ResourceFileSystem = PlatformResourceFileSystem,
        ): ProjectTrustStore =
            ProjectTrustStore(
                storage = PlatformProjectTrustStorage.create(agentDirectory),
                fileSystem = fileSystem,
            )
    }

    fun get(cwd: String): ProjectTrustValue = getEntry(cwd)?.decision

    fun getEntry(cwd: String): ProjectTrustStoreEntry? =
        storage.withLock { content ->
            findNearestTrustEntry(
                data = readProjectTrustFile(content),
                cwd = normalizeCwd(cwd, fileSystem),
                fileSystem = fileSystem,
            )
        }

    fun set(
        cwd: String,
        decision: ProjectTrustValue,
    ) {
        setMany(listOf(ProjectTrustUpdate(cwd, decision)))
    }

    fun setMany(decisions: List<ProjectTrustUpdate>) {
        if (decisions.isEmpty()) return
        storage.update { content ->
            val data = readProjectTrustFile(content).toMutableMap()
            decisions.forEach { update ->
                val path = normalizeCwd(update.path, fileSystem)
                if (update.decision == null) {
                    data.remove(path)
                } else {
                    data[path] = update.decision
                }
            }
            encodeProjectTrustFile(data)
        }
    }
}

/** Project resources that can execute code or alter the agent prompt/configuration. */
fun hasTrustRequiringProjectResources(
    cwd: String,
    homeDirectory: String? = null,
    fileSystem: ResourceFileSystem = PlatformResourceFileSystem,
): Boolean {
    val normalizedCwd = normalizeCwd(cwd, fileSystem)
    val home = normalizeCwd(homeDirectory ?: fileSystem.resolvePath("~"), fileSystem)
    val userAgentsSkills =
        fileSystem
            .join(home, AGENTS_DIRECTORY_NAME)
            .let { fileSystem.join(it, SKILLS_DIRECTORY_NAME) }
    val projectConfig = fileSystem.join(normalizedCwd, PROJECT_CONFIG_DIRECTORY)
    val hasProjectConfigResources =
        TRUST_REQUIRING_PROJECT_CONFIG_RESOURCES.any { name ->
            fileSystem.exists(fileSystem.join(projectConfig, name))
        }
    if (hasProjectConfigResources) return true

    return hasAncestorAgentsSkills(normalizedCwd, userAgentsSkills, fileSystem)
}

private fun hasAncestorAgentsSkills(
    cwd: String,
    userAgentsSkills: String,
    fileSystem: ResourceFileSystem,
): Boolean {
    var current = cwd
    var found = false
    while (!found) {
        val agentsSkills =
            fileSystem
                .join(current, AGENTS_DIRECTORY_NAME)
                .let { fileSystem.join(it, SKILLS_DIRECTORY_NAME) }
        found = normalizeCwd(agentsSkills, fileSystem) != userAgentsSkills && fileSystem.exists(agentsSkills)
        val parent = fileSystem.parent(current)
        if (found || parent == current) break
        current = normalizeCwd(parent, fileSystem)
    }
    return found
}

fun getProjectTrustParentPath(
    cwd: String,
    fileSystem: ResourceFileSystem = PlatformResourceFileSystem,
): String? {
    val normalized = normalizeCwd(cwd, fileSystem)
    return fileSystem.parent(normalized).takeUnless { it == normalized }
}

fun getProjectTrustOptions(
    cwd: String,
    includeSessionOnly: Boolean = false,
    fileSystem: ResourceFileSystem = PlatformResourceFileSystem,
): List<ProjectTrustOption> {
    val trustPath = normalizeCwd(cwd, fileSystem)
    val options =
        mutableListOf(
            ProjectTrustOption(
                label = "Trust",
                trusted = true,
                updates = listOf(ProjectTrustUpdate(trustPath, true)),
                savedPath = trustPath,
            ),
        )
    getProjectTrustParentPath(trustPath, fileSystem)?.let { parentPath ->
        options +=
            ProjectTrustOption(
                label = "Trust parent folder ($parentPath)",
                trusted = true,
                updates =
                    listOf(
                        ProjectTrustUpdate(parentPath, true),
                        ProjectTrustUpdate(trustPath, null),
                    ),
                savedPath = parentPath,
            )
    }
    if (includeSessionOnly) {
        options += ProjectTrustOption("Trust (this session only)", trusted = true, updates = emptyList())
    }
    options +=
        ProjectTrustOption(
            label = "Do not trust",
            trusted = false,
            updates = listOf(ProjectTrustUpdate(trustPath, false)),
            savedPath = trustPath,
        )
    if (includeSessionOnly) {
        options += ProjectTrustOption("Do not trust (this session only)", trusted = false, updates = emptyList())
    }
    return options
}

data class ProjectTrustResolutionOptions(
    val cwd: String,
    val trustStore: ProjectTrustStore,
    val trustOverride: Boolean? = null,
    val defaultProjectTrust: DefaultProjectTrust = DefaultProjectTrust.ASK,
    val extensionRunner: PiExtensionRunner? = null,
    val extensionContext: PiExtensionContext? = null,
    val interaction: ProjectTrustInteraction? = null,
    val includeSessionOnlyOption: Boolean = true,
    val fileSystem: ResourceFileSystem = PlatformResourceFileSystem,
)

fun interface ProjectTrustInteraction {
    suspend fun select(
        title: String,
        options: List<String>,
    ): Int?
}

/** Resolves trust in Pi's order: override, resource gate, extension, saved decision, default, UI. */
suspend fun resolveProjectTrusted(options: ProjectTrustResolutionOptions): Boolean =
    options.trustOverride
        ?: if (!hasTrustRequiringProjectResources(options.cwd, fileSystem = options.fileSystem)) {
            true
        } else {
            resolveFromExtension(options)
                ?: options.trustStore.get(options.cwd)
                ?: defaultTrustDecision(options.defaultProjectTrust)
                ?: resolveFromInteraction(options)
                ?: false
        }

private suspend fun resolveFromExtension(options: ProjectTrustResolutionOptions): Boolean? {
    val result =
        options.extensionRunner?.let { runner ->
            options.extensionContext?.let { context -> runner.emitProjectTrust(context) }
        }
    return result?.let {
        val trusted = it.trusted == ProjectTrustDecision.YES
        if (it.remember) options.trustStore.set(options.cwd, trusted)
        trusted
    }
}

private fun defaultTrustDecision(defaultProjectTrust: DefaultProjectTrust): Boolean? =
    when (defaultProjectTrust) {
        DefaultProjectTrust.ALWAYS -> true
        DefaultProjectTrust.NEVER -> false
        DefaultProjectTrust.ASK -> null
    }

private suspend fun resolveFromInteraction(options: ProjectTrustResolutionOptions): Boolean? {
    val trustOptions =
        getProjectTrustOptions(
            cwd = options.cwd,
            includeSessionOnly = options.includeSessionOnlyOption,
            fileSystem = options.fileSystem,
        )
    val selectedIndex =
        options.interaction?.select(
            "Trust project folder?\n${normalizeCwd(options.cwd, options.fileSystem)}\n\n" +
                "This allows project settings, resources, packages, and extensions to load.",
            trustOptions.map(ProjectTrustOption::label),
        )
    return selectedIndex
        ?.let(trustOptions::getOrNull)
        ?.let { selected ->
            if (selected.updates.isNotEmpty()) options.trustStore.setMany(selected.updates)
            selected.trusted
        }
}

private const val PROJECT_CONFIG_DIRECTORY = ".xuexue/pi"
private const val AGENTS_DIRECTORY_NAME = ".agents"
private const val SKILLS_DIRECTORY_NAME = "skills"
private val TRUST_REQUIRING_PROJECT_CONFIG_RESOURCES =
    listOf("settings.json", "extensions", "skills", "prompts", "themes", "SYSTEM.md", "APPEND_SYSTEM.md")
