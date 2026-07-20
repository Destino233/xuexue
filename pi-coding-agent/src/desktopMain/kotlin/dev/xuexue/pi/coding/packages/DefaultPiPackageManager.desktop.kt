@file:Suppress(
    "InjectDispatcher",
    "ImplicitDefaultLocale",
    "LongMethod",
    "MatchingDeclarationName",
    "MaxLineLength",
    "NestedBlockDepth",
    "ReturnCount",
    "TooGenericExceptionCaught",
    "TooManyFunctions",
)

package dev.xuexue.pi.coding.packages

import dev.xuexue.pi.coding.resources.ResourceExtensionPaths
import dev.xuexue.pi.coding.resources.ResourceOrigin
import dev.xuexue.pi.coding.resources.ResourcePathContribution
import dev.xuexue.pi.coding.resources.ResourcePathMetadata
import dev.xuexue.pi.coding.settings.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile

/** Desktop package manager for local, git and Maven-cache package sources. */
class DefaultPiPackageManager(
    cwd: String,
    agentDirectory: String,
    private val settingsManager: SettingsManager,
    offlineOverride: Boolean? = null,
) : PiPackageManager {
    private val cwd = normalize(cwd)
    private val agentDirectory = normalize(agentDirectory)
    private val offlineOverride = offlineOverride
    private val httpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()

    override fun isOffline(): Boolean = offlineOverride ?: offlineFromEnvironment()

    override fun listConfiguredPackages(): List<PiPackageSpec> = configuredEntries().map { it.spec }

    override suspend fun resolve(
        onMissing: suspend (PiPackageSource) -> PiPackageMissingAction,
    ): PiPackageResolution =
        withContext(Dispatchers.IO) {
            val records = mutableListOf<PiPackageRecord>()
            val extensionPaths = mutableListOf<PiPackagePathContribution>()
            val skillPaths = mutableListOf<ResourcePathContribution>()
            val promptPaths = mutableListOf<ResourcePathContribution>()
            val diagnostics = mutableListOf<PiPackageDiagnostic>()
            val seen = mutableSetOf<String>()
            configuredEntries().forEach { entry ->
                if (entry.spec.autoload == false) return@forEach
                val source =
                    runCatching { PiPackageSourceParser.parse(entry.spec.source) }.getOrElse { error ->
                        diagnostics += PiPackageDiagnostic(entry.spec.source, error.message.orEmpty())
                        return@forEach
                    }
                if (!seen.add(source.identity)) return@forEach
                val record =
                    runCatching {
                        resolveRecord(entry.spec, source, entry.scope, onMissing)
                    }.onFailure { error ->
                        diagnostics += PiPackageDiagnostic(source.raw, error.message.orEmpty())
                    }.getOrNull() ?: return@forEach
                records += record
                val metadata =
                    ResourcePathMetadata(
                        source = source.raw,
                        scope = entry.scope.toResourceScope(),
                        origin = ResourceOrigin.PACKAGE,
                        baseDirectory = record.installedPath,
                    )
                val extensionEntries = resourceEntries(record, entry.spec.extensions, record.manifest.extensions, "extensions")
                extensionEntries.forEach { path ->
                    extensionPaths += PiPackagePathContribution(path, source.raw, entry.scope)
                }
                resourceEntries(record, entry.spec.skills, record.manifest.skills, "skills").forEach { path ->
                    skillPaths += ResourcePathContribution(path, metadata)
                }
                resourceEntries(record, entry.spec.prompts, record.manifest.prompts, "prompts").forEach { path ->
                    promptPaths += ResourcePathContribution(path, metadata)
                }
            }
            PiPackageResolution(
                packages = records,
                extensionPaths = extensionPaths,
                resources = ResourceExtensionPaths(skillPaths = skillPaths, promptPaths = promptPaths),
                diagnostics = diagnostics,
            )
        }

    override suspend fun install(
        spec: PiPackageSpec,
        scope: PiPackageScope,
        persist: Boolean,
    ): PiPackageInstallResult =
        withContext(Dispatchers.IO) {
            assertTrusted(scope)
            val source = PiPackageSourceParser.parse(spec.source)
            val result =
                when (source.type) {
                    PiPackageSourceType.PATH -> {
                        val path = resolvePath(source.location, baseDirectory(scope))
                        require(exists(path)) { "Package path does not exist: $path" }
                        PiPackageInstallResult(createRecord(source, scope, path, spec))
                    }

                    PiPackageSourceType.GIT -> {
                        installGit(source, scope, spec)
                    }

                    PiPackageSourceType.MAVEN -> {
                        installMaven(source, scope, spec)
                    }
                }
            if (persist) persistSpec(spec, scope)
            result
        }

    override suspend fun remove(
        source: String,
        scope: PiPackageScope,
        persist: Boolean,
    ): Boolean =
        withContext(Dispatchers.IO) {
            assertTrusted(scope)
            val parsed = PiPackageSourceParser.parse(source)
            val path =
                when (parsed.type) {
                    PiPackageSourceType.PATH -> null
                    else -> cachePath(parsed, scope)
                }
            val existed = path?.let(::exists) == true
            if (path != null) deleteRecursively(path)
            val removedFromSettings = if (persist) removePersistedSource(parsed, scope) else false
            existed || removedFromSettings
        }

    override suspend fun update(source: String?): List<PiPackageInstallResult> =
        withContext(Dispatchers.IO) {
            if (isOffline()) return@withContext emptyList()
            configuredEntries()
                .filter { entry -> source == null || sameIdentity(entry.spec.source, source) }
                .mapNotNull { entry ->
                    val parsed = runCatching { PiPackageSourceParser.parse(entry.spec.source) }.getOrNull()
                    if (parsed == null || parsed.type == PiPackageSourceType.PATH) {
                        null
                    } else {
                        runCatching { install(entry.spec, entry.scope, persist = false) }.getOrNull()
                    }
                }
        }

    private suspend fun resolveRecord(
        spec: PiPackageSpec,
        source: PiPackageSource,
        scope: PiPackageScope,
        onMissing: suspend (PiPackageSource) -> PiPackageMissingAction,
    ): PiPackageRecord? {
        val existingPath =
            when (source.type) {
                PiPackageSourceType.PATH -> resolvePath(source.location, baseDirectory(scope))
                else -> cachePath(source, scope)
            }
        if (exists(existingPath)) return createRecord(source, scope, existingPath, spec)
        if (source.type == PiPackageSourceType.PATH) {
            error("Package path does not exist: $existingPath")
        }
        if (isOffline()) {
            error("Offline mode prevents installing missing package ${source.raw}")
        }
        return when (onMissing(source)) {
            PiPackageMissingAction.SKIP -> null
            PiPackageMissingAction.ERROR -> error("Missing package: ${source.raw}")
            PiPackageMissingAction.INSTALL -> install(spec, scope, persist = false).record
        }
    }

    private fun createRecord(
        source: PiPackageSource,
        scope: PiPackageScope,
        path: Path,
        spec: PiPackageSpec,
    ): PiPackageRecord {
        val manifest = readManifest(path)
        val selected =
            manifest.copy(
                extensions = spec.extensions ?: manifest.extensions,
                skills = spec.skills ?: manifest.skills,
                prompts = spec.prompts ?: manifest.prompts,
                themes = spec.themes ?: manifest.themes,
            )
        selected.validate()
        return PiPackageRecord(source, scope, path.toString(), selected, hashPath(path))
    }

    private fun installGit(
        source: PiPackageSource,
        scope: PiPackageScope,
        spec: PiPackageSpec,
    ): PiPackageInstallResult {
        check(!isOffline()) { "Offline mode prevents installing git package ${source.raw}" }
        val target = cachePath(source, scope)
        val staging = stagingPath(scope)
        Files.createDirectories(staging.parent)
        try {
            runCommand(listOf("git", "clone", "--no-tags", gitCloneUrl(source.location), staging.toString()), cwd)
            source.ref?.let { ref -> runCommand(listOf("git", "-C", staging.toString(), "checkout", "--detach", ref), cwd) }
            val record = createRecord(source, scope, staging, spec)
            val replaced = replaceAtomically(staging, target)
            return PiPackageInstallResult(record.copy(installedPath = target.toString()), replaced?.toString())
        } catch (error: Throwable) {
            deleteRecursively(staging)
            throw error
        }
    }

    private fun installMaven(
        source: PiPackageSource,
        scope: PiPackageScope,
        spec: PiPackageSpec,
    ): PiPackageInstallResult {
        check(!isOffline()) { "Offline mode prevents installing Maven package ${source.raw}" }
        val coordinates = source.location.split(':')
        require(coordinates.size == 2) { "Invalid Maven coordinates: ${source.location}" }
        val localJar =
            Path.of(
                System.getProperty("user.home"),
                ".m2",
                "repository",
                coordinates[0].replace('.', '/'),
                coordinates[1],
                source.version.orEmpty(),
                "${coordinates[1]}-${source.version}.jar",
            )
        val target = cachePath(source, scope)
        val staging = stagingPath(scope, suffix = ".jar")
        Files.createDirectories(staging.parent)
        withFileLock(target.resolveSibling(".${target.fileName}.lock")) {
            if (Files.isRegularFile(localJar)) {
                Files.copy(localJar, staging, StandardCopyOption.REPLACE_EXISTING)
            } else {
                downloadMavenJar(source, staging)
            }
        }
        return try {
            val record = createRecord(source, scope, staging, spec)
            val replaced = replaceAtomically(staging, target)
            PiPackageInstallResult(record.copy(installedPath = target.toString()), replaced?.toString())
        } catch (error: Throwable) {
            deleteRecursively(staging)
            throw error
        }
    }

    private fun resourceEntries(
        record: PiPackageRecord,
        selected: List<String>?,
        manifestEntries: List<String>,
        conventionalDirectory: String,
    ): List<String> {
        val root = Path.of(record.installedPath)
        if (root.toFile().isFile) return if (conventionalDirectory == "extensions") listOf(root.toString()) else emptyList()
        val entries = selected ?: manifestEntries
        if (entries.isNotEmpty()) return entries.flatMap { resolvePackageEntry(root, it) }
        val conventional = root.resolve(conventionalDirectory)
        return conventional.takeIf(::exists)?.let { listOf(it.toString()) }.orEmpty()
    }

    private fun resolvePackageEntry(
        root: Path,
        entry: String,
    ): List<String> {
        if (!containsGlob(entry)) {
            val path = resolvePath(entry, root)
            return path
                .takeIf(::exists)
                ?.let(::listOf)
                .orEmpty()
                .map(Path::toString)
        }
        val matcher = root.fileSystem.getPathMatcher("glob:${entry.replace('\\', '/')}")
        return Files.walk(root).use { paths ->
            paths
                .filter { path -> matcher.matches(root.relativize(path)) }
                .map(Path::toString)
                .toList()
        }
    }

    private fun downloadMavenJar(
        source: PiPackageSource,
        target: Path,
    ) {
        val coordinates = source.location.split(':')
        require(coordinates.size == 2) { "Invalid Maven coordinates: ${source.location}" }
        val repository =
            (System.getenv("PI_MAVEN_REPOSITORY") ?: "https://repo1.maven.org/maven2")
                .trimEnd('/')
        val url =
            "$repository/${coordinates[0].replace('.', '/')}/${coordinates[1]}/${source.version}/" +
                "${coordinates[1]}-${source.version}.jar"
        val response =
            httpClient.send(
                HttpRequest
                    .newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(2))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofByteArray(),
            )
        require(response.statusCode() in 200..299) {
            "Maven download failed (${response.statusCode()}): ${source.raw}"
        }
        Files.write(target, response.body())
    }

    private fun withFileLock(
        lockPath: Path,
        block: () -> Unit,
    ) {
        Files.createDirectories(requireNotNull(lockPath.parent))
        FileChannel
            .open(
                lockPath,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.WRITE,
            ).use { channel ->
                channel.lock().use { block() }
            }
    }

    private fun readManifest(path: Path): PiPackageManifest {
        val text =
            if (Files.isDirectory(path)) {
                val candidates =
                    listOf(
                        path.resolve("xuexue-pi-package.json"),
                        path.resolve(".xuexue/pi-package.json"),
                        path.resolve("package.json"),
                    )
                candidates.firstOrNull(Files::isRegularFile)?.let { Files.readString(it) }
            } else {
                JarFile(path.toFile()).use { jar ->
                    val entry =
                        jar.getJarEntry("META-INF/xuexue-pi-package.json")
                            ?: jar.getJarEntry("META-INF/xuexue-pi-plugin.json")
                            ?: return@use null
                    jar.getInputStream(entry).bufferedReader().use { it.readText() }
                }
            }
        return text?.let(PiPackageManifestCodec::decode)
            ?: PiPackageManifest(id = path.fileName.toString().substringBeforeLast('.'), version = "0.0.0")
    }

    private fun configuredEntries(): List<ConfiguredEntry> {
        val result = mutableListOf<ConfiguredEntry>()
        if (settingsManager.isProjectTrusted()) {
            readConfigured(settingsManager.getProjectSettings()["packages"], PiPackageScope.PROJECT, result)
        }
        readConfigured(settingsManager.getGlobalSettings()["packages"], PiPackageScope.USER, result)
        return result
    }

    private fun readConfigured(
        element: JsonElement?,
        scope: PiPackageScope,
        target: MutableList<ConfiguredEntry>,
    ) {
        val packages = (element as? JsonArray).orEmpty()
        packages.forEach { value ->
            PiPackageManifestCodec.decodeSettingsSpec(value)?.let { target += ConfiguredEntry(it, scope) }
        }
    }

    private fun persistSpec(
        spec: PiPackageSpec,
        scope: PiPackageScope,
    ) {
        if (scope == PiPackageScope.TEMPORARY) return
        val encoded = encodeSpec(spec)
        val current = configuredElements(scope).toMutableList()
        current.removeAll { element ->
            PiPackageManifestCodec.decodeSettingsSpec(element)?.source?.let { sameIdentity(it, spec.source) } == true
        }
        current += encoded
        if (scope == PiPackageScope.PROJECT) settingsManager.setProjectPackages(current) else settingsManager.setPackages(current)
    }

    private fun removePersistedSource(
        source: PiPackageSource,
        scope: PiPackageScope,
    ): Boolean {
        if (scope == PiPackageScope.TEMPORARY) return false
        val current = configuredElements(scope)
        val next =
            current.filterNot { element ->
                PiPackageManifestCodec.decodeSettingsSpec(element)?.source?.let { sameIdentity(it, source.raw) } == true
            }
        if (next.size == current.size) return false
        if (scope == PiPackageScope.PROJECT) settingsManager.setProjectPackages(next) else settingsManager.setPackages(next)
        return true
    }

    private fun configuredElements(scope: PiPackageScope): List<JsonElement> {
        val settings = if (scope == PiPackageScope.PROJECT) settingsManager.getProjectSettings() else settingsManager.getGlobalSettings()
        return (settings["packages"] as? JsonArray).orEmpty()
    }

    private fun encodeSpec(spec: PiPackageSpec): JsonElement {
        val hasFilter = spec.autoload != null || spec.extensions != null || spec.skills != null || spec.prompts != null || spec.themes != null
        if (!hasFilter) return JsonPrimitive(spec.source)
        return buildJsonObject {
            put("source", spec.source)
            spec.autoload?.let { put("autoload", it) }
            spec.extensions?.let { put("extensions", JsonArray(it.map(::JsonPrimitive))) }
            spec.skills?.let { put("skills", JsonArray(it.map(::JsonPrimitive))) }
            spec.prompts?.let { put("prompts", JsonArray(it.map(::JsonPrimitive))) }
            spec.themes?.let { put("themes", JsonArray(it.map(::JsonPrimitive))) }
        }
    }

    private fun assertTrusted(scope: PiPackageScope) {
        require(scope != PiPackageScope.PROJECT || settingsManager.isProjectTrusted()) {
            "Project package operations require workspace trust"
        }
    }

    private fun baseDirectory(scope: PiPackageScope): Path =
        when (scope) {
            PiPackageScope.PROJECT -> cwd
            PiPackageScope.USER, PiPackageScope.TEMPORARY -> agentDirectory
        }

    private fun cacheRoot(scope: PiPackageScope): Path =
        when (scope) {
            PiPackageScope.PROJECT -> cwd.resolve(".xuexue/pi/packages")
            PiPackageScope.USER -> agentDirectory.resolve("packages")
            PiPackageScope.TEMPORARY -> agentDirectory.resolve("tmp/packages")
        }

    private fun cachePath(
        source: PiPackageSource,
        scope: PiPackageScope,
    ): Path {
        val typeDirectory =
            when (source.type) {
                PiPackageSourceType.GIT -> "git"
                PiPackageSourceType.MAVEN -> "maven"
                PiPackageSourceType.PATH -> "path"
            }
        val suffix = sha256(source.identity.toByteArray(StandardCharsets.UTF_8)).take(24)
        return cacheRoot(scope).resolve(typeDirectory).resolve(suffix).let { path ->
            if (source.type == PiPackageSourceType.MAVEN) path.resolve("${source.version}.jar") else path
        }
    }

    private fun stagingPath(
        scope: PiPackageScope,
        suffix: String = "",
    ) = cacheRoot(scope).resolve(".staging").resolve("${UUID.randomUUID()}$suffix")

    private fun replaceAtomically(
        staging: Path,
        target: Path,
    ): Path? {
        Files.createDirectories(requireNotNull(target.parent))
        val backup = target.takeIf(::exists)?.resolveSibling(".${target.fileName}.backup-${UUID.randomUUID()}")
        try {
            if (backup != null) move(target, backup)
            move(staging, target)
            deleteRecursively(backup)
            // The backup is removed on success; only expose a path while it still exists.
            return null
        } catch (error: Throwable) {
            deleteRecursively(target)
            if (backup != null && exists(backup)) move(backup, target)
            throw error
        }
    }

    private fun move(
        source: Path,
        target: Path,
    ) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun runCommand(
        command: List<String>,
        directory: Path,
    ) {
        val process =
            ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("Package command timed out: ${command.firstOrNull()}")
        }
        if (process.exitValue() != 0) {
            throw IOException("Package command failed (${process.exitValue()}): ${output.take(1_000)}")
        }
    }

    private fun hashPath(path: Path): String {
        if (Files.isRegularFile(path)) return sha256(Files.readAllBytes(path))
        val digest = MessageDigest.getInstance("SHA-256")
        Files.walk(path).use { stream ->
            stream
                .filter { file -> Files.isRegularFile(file) }
                .filter { file -> !file.toString().contains("/.git/") }
                .sorted()
                .forEach { file ->
                    digest.update(path.relativize(file).toString().toByteArray(StandardCharsets.UTF_8))
                    digest.update(Files.readAllBytes(file))
                }
        }
        return digest.digest().toHex()
    }

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(value).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

    private fun deleteRecursively(path: Path?) {
        if (path == null || !Files.exists(path)) return
        Files.walk(path).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    private fun sameIdentity(
        left: String,
        right: String,
    ): Boolean = runCatching { PiPackageSourceParser.parse(left).identity == PiPackageSourceParser.parse(right).identity }.getOrDefault(false)

    private fun gitCloneUrl(location: String): String =
        when {
            location.contains("://") || location.startsWith("git@") -> location
            else -> "https://$location"
        }

    private fun exists(path: Path): Boolean = Files.exists(path)

    private fun containsGlob(value: String): Boolean = value.any { it == '*' || it == '?' || it == '[' }

    private fun resolvePath(
        path: String,
        base: Path,
    ): Path {
        val expanded =
            when {
                path == "~" -> System.getProperty("user.home")
                path.startsWith("~/") -> Path.of(System.getProperty("user.home"), path.drop(2)).toString()
                else -> path
            }
        val candidate = Path.of(expanded)
        return (if (candidate.isAbsolute) candidate else base.resolve(candidate)).toAbsolutePath().normalize()
    }

    private fun normalize(path: String): Path = resolvePath(path, Path.of("."))

    private fun offlineFromEnvironment(): Boolean = System.getenv("PI_OFFLINE")?.lowercase() in setOf("1", "true", "yes")

    private data class ConfiguredEntry(
        val spec: PiPackageSpec,
        val scope: PiPackageScope,
    )
}
