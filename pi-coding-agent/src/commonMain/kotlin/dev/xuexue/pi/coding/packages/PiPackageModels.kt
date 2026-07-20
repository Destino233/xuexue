package dev.xuexue.pi.coding.packages

import dev.xuexue.pi.coding.resources.ResourceExtensionPaths
import dev.xuexue.pi.coding.resources.ResourcePathContribution
import kotlinx.serialization.json.JsonElement

/** Scope used when a package is configured or installed. */
enum class PiPackageScope {
    USER,
    PROJECT,
    TEMPORARY,
}

enum class PiPackageSourceType {
    PATH,
    GIT,
    MAVEN,
}

/** A normalized package source. The raw value is retained for settings and diagnostics. */
data class PiPackageSource(
    val raw: String,
    val type: PiPackageSourceType,
    val location: String,
    val ref: String? = null,
    val version: String? = null,
) {
    val identity: String
        get() =
            when (type) {
                PiPackageSourceType.PATH -> "path:$location"
                PiPackageSourceType.GIT -> "git:$location"
                PiPackageSourceType.MAVEN -> "maven:$location"
            }
}

object PiPackageSourceParser {
    fun parse(rawSource: String): PiPackageSource {
        val raw = rawSource.trim()
        require(raw.isNotEmpty()) { "Package source must not be blank" }
        return when {
            raw.startsWith("maven:", ignoreCase = true) -> {
                parseMaven(raw)
            }

            raw.startsWith("git:", ignoreCase = true) -> {
                parseGit(raw)
            }

            raw.startsWith("path:", ignoreCase = true) -> {
                PiPackageSource(raw, PiPackageSourceType.PATH, raw.substringAfter(':').trim())
            }

            raw.startsWith("file:", ignoreCase = true) -> {
                PiPackageSource(raw, PiPackageSourceType.PATH, raw.substringAfter(':').trim())
            }

            isGitUrl(raw) -> {
                parseGit(raw)
            }

            else -> {
                PiPackageSource(raw, PiPackageSourceType.PATH, raw)
            }
        }.also { source ->
            require(source.location.isNotBlank()) { "Package source location must not be blank: $raw" }
        }
    }

    private fun parseMaven(raw: String): PiPackageSource {
        val coordinate = raw.substringAfter(':').trim()
        val parts = coordinate.split(':')
        require(parts.size == 3 && parts.all(String::isNotBlank)) {
            "Maven package source must be maven:group:artifact:version: $raw"
        }
        return PiPackageSource(raw, PiPackageSourceType.MAVEN, "${parts[0]}:${parts[1]}", version = parts[2])
    }

    private fun parseGit(raw: String): PiPackageSource {
        val value = raw.substringAfter(':', raw).trim()
        val refSeparator =
            value.lastIndexOf('@').takeIf { index ->
                index > value.lastIndexOf('/') && index > value.lastIndexOf(':')
            }
        val location = refSeparator?.let { value.substring(0, it) } ?: value
        val ref = refSeparator?.let { value.substring(it + 1).takeIf(String::isNotBlank) }
        require(location.isNotBlank()) { "Git package source location must not be blank: $raw" }
        return PiPackageSource(raw, PiPackageSourceType.GIT, normalizeGitLocation(location), ref = ref)
    }

    private fun normalizeGitLocation(value: String): String = value.removePrefix("git:").removeSuffix("/")

    private fun isGitUrl(value: String): Boolean =
        value.startsWith("https://", ignoreCase = true) ||
            value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("ssh://", ignoreCase = true) ||
            value.startsWith("git://", ignoreCase = true) ||
            value.startsWith("git@", ignoreCase = true)
}

/** Capability manifest used by directory, JAR and process packages. */
data class PiPackageManifest(
    val schemaVersion: Int = 1,
    val id: String,
    val version: String,
    val apiVersion: String = "1",
    val extensions: List<String> = emptyList(),
    val skills: List<String> = emptyList(),
    val prompts: List<String> = emptyList(),
    val themes: List<String> = emptyList(),
    val permissions: List<String> = emptyList(),
    val metadata: JsonElement? = null,
) {
    fun validate() {
        require(schemaVersion == 1) { "Unsupported package manifest schemaVersion: $schemaVersion" }
        require(id.isNotBlank()) { "Package manifest id must not be blank" }
        require(version.isNotBlank()) { "Package $id manifest version must not be blank" }
        require(apiVersion == "1") { "Package $id requires unsupported API version: $apiVersion" }
    }
}

data class PiPackageSpec(
    val source: String,
    val autoload: Boolean? = null,
    val extensions: List<String>? = null,
    val skills: List<String>? = null,
    val prompts: List<String>? = null,
    val themes: List<String>? = null,
)

data class PiPackageRecord(
    val source: PiPackageSource,
    val scope: PiPackageScope,
    val installedPath: String,
    val manifest: PiPackageManifest,
    val contentHash: String,
)

data class PiPackagePathContribution(
    val path: String,
    val source: String,
    val scope: PiPackageScope,
)

data class PiPackageResolution(
    val packages: List<PiPackageRecord> = emptyList(),
    val extensionPaths: List<PiPackagePathContribution> = emptyList(),
    val resources: ResourceExtensionPaths = ResourceExtensionPaths(),
    val diagnostics: List<PiPackageDiagnostic> = emptyList(),
)

enum class PiPackageDiagnosticSeverity {
    WARNING,
    ERROR,
}

data class PiPackageDiagnostic(
    val source: String,
    val message: String,
    val severity: PiPackageDiagnosticSeverity = PiPackageDiagnosticSeverity.ERROR,
)

enum class PiPackageMissingAction {
    INSTALL,
    SKIP,
    ERROR,
}

data class PiPackageInstallResult(
    val record: PiPackageRecord,
    val replacedPath: String? = null,
)

interface PiPackageManager {
    suspend fun resolve(
        onMissing: suspend (PiPackageSource) -> PiPackageMissingAction = { PiPackageMissingAction.INSTALL },
    ): PiPackageResolution

    suspend fun install(
        spec: PiPackageSpec,
        scope: PiPackageScope = PiPackageScope.USER,
        persist: Boolean = false,
    ): PiPackageInstallResult

    suspend fun remove(
        source: String,
        scope: PiPackageScope = PiPackageScope.USER,
        persist: Boolean = false,
    ): Boolean

    suspend fun update(source: String? = null): List<PiPackageInstallResult>

    fun listConfiguredPackages(): List<PiPackageSpec>

    fun isOffline(): Boolean
}

internal fun PiPackageScope.toResourceScope() =
    when (this) {
        PiPackageScope.USER -> dev.xuexue.pi.coding.resources.ResourceScope.USER
        PiPackageScope.PROJECT -> dev.xuexue.pi.coding.resources.ResourceScope.PROJECT
        PiPackageScope.TEMPORARY -> dev.xuexue.pi.coding.resources.ResourceScope.TEMPORARY
    }

internal fun PiPackagePathContribution.toResourceContribution() =
    ResourcePathContribution(
        path = path,
        metadata =
            dev.xuexue.pi.coding.resources.ResourcePathMetadata(
                source = source,
                scope = scope.toResourceScope(),
                origin = dev.xuexue.pi.coding.resources.ResourceOrigin.PACKAGE,
            ),
    )
