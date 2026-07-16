package dev.xuexue.pi.coding.resources

data class SystemPromptDocumentation(
    val readmePath: String,
    val docsPath: String,
    val examplesPath: String,
)

data class SystemPromptOptions(
    val customPrompt: String? = null,
    val selectedTools: List<String>? = null,
    val toolSnippets: Map<String, String> = emptyMap(),
    val promptGuidelines: List<String> = emptyList(),
    val appendSystemPrompt: String? = null,
    val cwd: String,
    val currentDate: String,
    val documentation: SystemPromptDocumentation,
    val contextFiles: List<ContextFile> = emptyList(),
    val skills: List<Skill> = emptyList(),
)

data class ContextFile(
    val path: String,
    val content: String,
)

/**
 * Builds Pi's agent system prompt from already-loaded resources. File discovery intentionally stays outside this
 * function so Compose, SDK, and JSONL RPC can share one deterministic prompt construction path.
 */
fun buildSystemPrompt(options: SystemPromptOptions): String {
    val promptCwd = options.cwd.replace('\\', '/')
    val appendSection =
        options.appendSystemPrompt
            ?.takeIf(String::isNotEmpty)
            ?.let { "\n\n$it" }
            .orEmpty()
    val selectedTools = options.selectedTools ?: DEFAULT_SYSTEM_PROMPT_TOOLS
    val prompt =
        options.customPrompt?.let { customPrompt ->
            buildCustomSystemPrompt(customPrompt, appendSection, selectedTools, options.contextFiles, options.skills)
        } ?: buildDefaultSystemPrompt(options, appendSection, selectedTools)
    return prompt + "\nCurrent date: ${options.currentDate}\nCurrent working directory: $promptCwd"
}

private fun buildCustomSystemPrompt(
    customPrompt: String,
    appendSection: String,
    selectedTools: List<String>,
    contextFiles: List<ContextFile>,
    skills: List<Skill>,
): String {
    var prompt = customPrompt + appendSection
    prompt += formatContextFiles(contextFiles)
    if ("read" in selectedTools) prompt += formatSkillsForPrompt(skills)
    return prompt
}

private fun buildDefaultSystemPrompt(
    options: SystemPromptOptions,
    appendSection: String,
    selectedTools: List<String>,
): String {
    val visibleTools = selectedTools.filter(options.toolSnippets::containsKey)
    val tools =
        visibleTools
            .joinToString("\n") { tool -> "- $tool: ${options.toolSnippets.getValue(tool)}" }
            .ifEmpty { "(none)" }
    val guidelines = buildPromptGuidelines(selectedTools, options.promptGuidelines)
    val docs = options.documentation
    var prompt =
        """You are an expert coding assistant operating inside pi, a coding agent harness. You help users by reading files, executing commands, editing code, and writing new files.

Available tools:
$tools

In addition to the tools above, you may have access to other custom tools depending on the project.

Guidelines:
$guidelines

Pi documentation (read only when the user asks about pi itself, its SDK, extensions, themes, skills, or TUI):
- Main documentation: ${docs.readmePath}
- Additional docs: ${docs.docsPath}
- Examples: ${docs.examplesPath} (extensions, custom tools, SDK)
- When reading pi docs or examples, resolve docs/... under Additional docs and examples/... under Examples, not the current working directory
- When asked about: extensions (docs/extensions.md, examples/extensions/), themes (docs/themes.md), skills (docs/skills.md), prompt templates (docs/prompt-templates.md), TUI components (docs/tui.md), keybindings (docs/keybindings.md), SDK integrations (docs/sdk.md), custom providers (docs/custom-provider.md), adding models (docs/models.md), pi packages (docs/packages.md)
- When working on pi topics, read the docs and examples, and follow .md cross-references before implementing
- Always read pi .md files completely and follow links to related docs (e.g., tui.md for TUI API details)"""
    prompt += appendSection
    prompt += formatContextFiles(options.contextFiles)
    if ("read" in selectedTools) prompt += formatSkillsForPrompt(options.skills)
    return prompt
}

private fun buildPromptGuidelines(
    selectedTools: List<String>,
    additionalGuidelines: List<String>,
): String {
    val guidelines = linkedSetOf<String>()
    if ("bash" in selectedTools && selectedTools.none { it in FILE_EXPLORATION_TOOLS }) {
        guidelines += "Use bash for file operations like ls, rg, find"
    }
    additionalGuidelines.map(String::trim).filter(String::isNotEmpty).forEach(guidelines::add)
    guidelines += "Be concise in your responses"
    guidelines += "Show file paths clearly when working with files"
    return guidelines.joinToString("\n") { guideline -> "- $guideline" }
}

private fun formatContextFiles(contextFiles: List<ContextFile>): String {
    if (contextFiles.isEmpty()) return ""
    return buildString {
        append("\n\n<project_context>\n\n")
        append("Project-specific instructions and guidelines:\n\n")
        contextFiles.forEach { file ->
            append("<project_instructions path=\"")
            append(file.path)
            append("\">\n")
            append(file.content)
            append("\n</project_instructions>\n\n")
        }
        append("</project_context>\n")
    }
}

private val DEFAULT_SYSTEM_PROMPT_TOOLS = listOf("read", "bash", "edit", "write")
private val FILE_EXPLORATION_TOOLS = setOf("grep", "find", "ls")
