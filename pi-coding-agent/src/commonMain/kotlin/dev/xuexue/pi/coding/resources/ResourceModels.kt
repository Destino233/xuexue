package dev.xuexue.pi.coding.resources

enum class ResourceScope {
    USER,
    PROJECT,
    TEMPORARY,
}

data class ResourceSourceInfo(
    val path: String,
    val source: String,
    val scope: ResourceScope,
    val baseDirectory: String? = null,
)

data class ResourceDiagnostic(
    val type: ResourceDiagnosticType,
    val message: String,
    val path: String,
    val collision: ResourceCollision? = null,
)

enum class ResourceDiagnosticType {
    WARNING,
    ERROR,
    COLLISION,
}

data class ResourceCollision(
    val resourceType: String,
    val name: String,
    val winnerPath: String,
    val loserPath: String,
)

data class Skill(
    val name: String,
    val description: String,
    val filePath: String,
    val baseDirectory: String,
    val sourceInfo: ResourceSourceInfo,
    val disableModelInvocation: Boolean,
)

data class PromptTemplate(
    val name: String,
    val description: String,
    val argumentHint: String? = null,
    val content: String,
    val sourceInfo: ResourceSourceInfo,
    val filePath: String,
)

data class SkillsLoadResult(
    val skills: List<Skill>,
    val diagnostics: List<ResourceDiagnostic>,
)

data class PromptTemplatesLoadResult(
    val prompts: List<PromptTemplate>,
    val diagnostics: List<ResourceDiagnostic>,
)

data class ResourceLoaderState(
    val skills: SkillsLoadResult,
    val prompts: PromptTemplatesLoadResult,
    val contextFiles: List<ContextFile>,
    val systemPrompt: String?,
    val appendSystemPrompt: List<String>,
    val diagnostics: List<ResourceDiagnostic>,
)
