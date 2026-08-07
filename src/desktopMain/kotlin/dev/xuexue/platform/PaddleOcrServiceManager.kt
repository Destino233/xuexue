package dev.xuexue.platform

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.BufferedInputStream
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

enum class PaddleOcrArtifactFormat {
    EXECUTABLE,
    ZIP,
}

data class PaddleOcrSidecarArtifact(
    val url: URI,
    val sha256: String,
    val version: String,
    val executablePath: String,
    val format: PaddleOcrArtifactFormat = PaddleOcrArtifactFormat.EXECUTABLE,
    val arguments: List<String> = emptyList(),
)

data class PaddleOcrSidecarConfig(
    val executable: List<String> = emptyList(),
    val endpoint: URI = URI.create("http://127.0.0.1:8765"),
    val version: String = "3.x-sidecar",
    val requestedDevice: String = DEFAULT_PADDLEOCR_DEVICE,
    val startupTimeout: Duration = Duration.ofSeconds(30),
    val installDirectory: Path = defaultPaddleOcrInstallDirectory(),
    val artifact: PaddleOcrSidecarArtifact? = null,
) {
    init {
        require(endpoint.scheme == "http" && endpoint.host == "127.0.0.1") {
            "PaddleOCR sidecar must bind to loopback."
        }
        require(version.isNotBlank()) { "PaddleOCR sidecar version must not be blank." }
        require(requestedDevice.isNotBlank()) { "PaddleOCR requested device must not be blank." }
        require(!startupTimeout.isNegative && !startupTimeout.isZero) {
            "PaddleOCR sidecar startup timeout must be positive."
        }
        require(installDirectory.isAbsolute) { "PaddleOCR install directory must be absolute." }
        artifact?.let { item ->
            require(item.url.scheme in setOf("http", "https")) {
                "PaddleOCR artifact URL must use HTTP or HTTPS."
            }
            require(item.version == version) {
                "PaddleOCR artifact version must match the sidecar version."
            }
            require(item.sha256.matches(SHA256_PATTERN)) {
                "PaddleOCR artifact SHA-256 must be a lowercase 64-character hex string."
            }
            require(isSafeRelativePath(item.executablePath)) {
                "PaddleOCR artifact executable path must stay inside the install directory."
            }
        }
    }

    companion object {
        fun fromEnvironment(): PaddleOcrSidecarConfig {
            val executable =
                System
                    .getenv("XUEXUE_PADDLEOCR_EXECUTABLE")
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?.split(Regex("\\s+"))
                    .orEmpty()
            val endpoint =
                System
                    .getenv("XUEXUE_PADDLEOCR_ENDPOINT")
                    ?.takeIf(String::isNotBlank)
                    ?.let(URI::create)
                    ?: URI.create("http://127.0.0.1:8765")
            val version = System.getenv("XUEXUE_PADDLEOCR_VERSION")?.takeIf(String::isNotBlank) ?: "3.x-sidecar"
            val requestedDevice =
                System.getenv("XUEXUE_PADDLEOCR_DEVICE")?.takeIf(String::isNotBlank) ?: DEFAULT_PADDLEOCR_DEVICE
            val artifactUrl = System.getenv("XUEXUE_PADDLEOCR_ARTIFACT_URL")?.takeIf(String::isNotBlank)
            val artifactHash = System.getenv("XUEXUE_PADDLEOCR_ARTIFACT_SHA256")?.takeIf(String::isNotBlank)
            val artifact =
                if (artifactUrl != null && artifactHash != null) {
                    PaddleOcrSidecarArtifact(
                        url = URI.create(artifactUrl),
                        sha256 = artifactHash,
                        version = version,
                        executablePath =
                            System.getenv("XUEXUE_PADDLEOCR_ARTIFACT_ENTRY")?.takeIf(String::isNotBlank)
                                ?: defaultArtifactExecutablePath(),
                        format = artifactFormat(System.getenv("XUEXUE_PADDLEOCR_ARTIFACT_FORMAT")),
                    )
                } else {
                    null
                }
            val installDirectory =
                System
                    .getenv("XUEXUE_PADDLEOCR_INSTALL_DIR")
                    ?.takeIf(String::isNotBlank)
                    ?.let(Path::of)
                    ?: defaultPaddleOcrInstallDirectory()
            return PaddleOcrSidecarConfig(
                executable = executable,
                endpoint = endpoint,
                version = version,
                requestedDevice = requestedDevice,
                installDirectory = installDirectory,
                artifact = artifact,
            )
        }

        private fun artifactFormat(value: String?): PaddleOcrArtifactFormat =
            value
                ?.let { raw -> runCatching { PaddleOcrArtifactFormat.valueOf(raw.uppercase()) }.getOrNull() }
                ?: PaddleOcrArtifactFormat.EXECUTABLE

        private fun defaultArtifactExecutablePath(): String =
            if (System.getProperty("os.name", "").contains("win", ignoreCase = true)) {
                "paddleocr-sidecar.exe"
            } else {
                "paddleocr-sidecar"
            }
    }
}

data class PaddleOcrHealth(
    val healthy: Boolean,
    val version: String? = null,
    val detail: String? = null,
    val engineReadiness: Map<String, Boolean> = emptyMap(),
    val measuredMemoryBytes: Long? = null,
    val runtime: PaddleOcrRuntime = PaddleOcrRuntime(),
) {
    fun isEngineReady(engineId: String): Boolean = engineReadiness[engineId] ?: healthy
}

data class PaddleOcrRuntime(
    val requestedDevice: String? = null,
    val device: String? = null,
    val backend: String? = null,
    val compiledBackends: Set<String> = emptySet(),
    val availableDevices: List<String> = emptyList(),
    val deviceAvailable: Boolean? = null,
) {
    fun cacheFingerprint(): String = "device=${device.orEmpty()};backend=${backend.orEmpty()}"
}

private const val DEFAULT_PADDLEOCR_DEVICE = "auto"

private fun canonicalPaddleDevice(value: String): String {
    val normalized = value.trim()
    return when {
        normalized.equals("gpu", ignoreCase = true) || normalized.equals("cuda", ignoreCase = true) -> "gpu:0"
        normalized.startsWith("cuda:", ignoreCase = true) -> "gpu:${normalized.substringAfter(':')}"
        normalized.equals("xpu", ignoreCase = true) -> "xpu:0"
        else -> normalized
    }
}

private fun paddleDeviceBackend(device: String): String {
    val normalized = device.trim().lowercase()
    return when {
        normalized == "cpu" -> "cpu"

        normalized == "gpu" || normalized.startsWith("gpu:") || normalized == "cuda" ||
            normalized.startsWith("cuda:") -> "cuda"

        normalized == "xpu" || normalized.startsWith("xpu:") -> "xpu"

        else -> "custom:${normalized.removePrefix("custom_device:").removePrefix("custom:").substringBefore(':')}"
    }
}

private fun paddleDevicesMatch(
    expected: String,
    actual: String,
): Boolean {
    fun identity(value: String): String =
        value
            .lowercase()
            .removePrefix("custom_device:")
            .removePrefix("custom:")
            .removeSuffix(":0")

    return identity(expected) == identity(actual)
}

private fun isPaddleAcceleratedDevice(device: String): Boolean {
    if (device.isBlank()) return false
    return paddleDeviceBackend(device) != "cpu"
}

private fun runtimePreferenceFailure(
    runtime: PaddleOcrRuntime,
    requestedDevice: String,
): String? {
    val actualDevice = runtime.device
    return when {
        actualDevice == null -> {
            "PaddleOCR sidecar health did not report the selected device."
        }

        runtime.deviceAvailable == null -> {
            "PaddleOCR sidecar health did not confirm device availability."
        }

        else -> {
            val requested = canonicalPaddleDevice(requestedDevice)
            when {
                requested.equals("auto", ignoreCase = true) -> {
                    val acceleratorReported =
                        isPaddleAcceleratedDevice(actualDevice) ||
                            runtime.availableDevices.any(::isPaddleAcceleratedDevice)
                    if (acceleratorReported && !isPaddleAcceleratedDevice(actualDevice)) {
                        "GPU-first policy rejected CPU sidecar: an accelerated Paddle device is available."
                    } else {
                        null
                    }
                }

                requested.equals("cpu", ignoreCase = true) && !actualDevice.equals("cpu", ignoreCase = true) -> {
                    "PaddleOCR requested CPU but sidecar selected $actualDevice."
                }

                !requested.equals("cpu", ignoreCase = true) && !paddleDevicesMatch(requested, actualDevice) -> {
                    "PaddleOCR requested $requested but sidecar selected $actualDevice."
                }

                else -> {
                    null
                }
            }
        }
    }
}

// Keep installation, health, lifecycle, and archive validation in one atomic service boundary.
@Suppress("TooManyFunctions")
class PaddleOcrServiceManager(
    private val config: PaddleOcrSidecarConfig = PaddleOcrSidecarConfig(),
    private val client: HttpClient = PpStructureV3Engine.defaultClient(),
    private val ioDispatcher: CoroutineDispatcher,
) : AutoCloseable {
    private val lifecycleMutex = Mutex()
    private var process: Process? = null

    suspend fun start(): PaddleOcrHealth = ensureReady()

    suspend fun ensureReady(engineId: String? = null): PaddleOcrHealth =
        lifecycleMutex.withLock {
            val current = health()
            if (!current.healthy) {
                val command = commandForStart()
                if (command.isEmpty()) {
                    return@withLock current
                }
                val started = startProcessAndWait(command)
                if (!started.healthy || engineId == null) {
                    return@withLock started
                }
            } else if (engineId == null) {
                return@withLock current
            }
            waitForEngine(checkNotNull(engineId))
        }

    suspend fun probeEngine(
        engineId: String,
        load: Boolean = false,
    ): PaddleOcrHealth = health(engineId, load)

    suspend fun installOrUpdate(): Path? = lifecycleMutex.withLock { installArtifact() }

    private suspend fun commandForStart(): List<String> {
        val artifact = config.artifact
        return when {
            config.executable.isNotEmpty() -> {
                wrapWindowsLauncher(config.executable)
            }

            artifact == null -> {
                emptyList()
            }

            else -> {
                installArtifact()
                    ?.let { executable -> wrapWindowsLauncher(listOf(executable.toString()) + artifact.arguments) }
                    .orEmpty()
            }
        }
    }

    private fun wrapWindowsLauncher(command: List<String>): List<String> {
        val executable = command.firstOrNull()?.lowercase().orEmpty()
        val shellRequired =
            isWindows() &&
                command.isNotEmpty() &&
                (executable.endsWith(".cmd") || executable.endsWith(".bat"))
        return if (!shellRequired) {
            command
        } else {
            val shell = System.getenv("ComSpec")?.takeIf(String::isNotBlank) ?: "cmd.exe"
            val commandLine = command.joinToString(" ") { argument -> quoteWindowsArgument(argument) }
            listOf(shell, "/d", "/s", "/c", "\"$commandLine\"")
        }
    }

    private fun quoteWindowsArgument(argument: String): String =
        if (argument.isEmpty() || argument.any { character -> character.isWhitespace() || character == '"' }) {
            "\"${argument.replace("\"", "\\\"")}\""
        } else {
            argument
        }

    private fun isWindows(): Boolean = System.getProperty("os.name", "").contains("win", ignoreCase = true)

    private suspend fun startProcessAndWait(command: List<String>): PaddleOcrHealth {
        process =
            withContext(ioDispatcher) {
                ProcessBuilder(command)
                    .apply {
                        environment()["XUEXUE_PADDLEOCR_DEVICE"] = config.requestedDevice
                        redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        redirectError(ProcessBuilder.Redirect.DISCARD)
                    }.start()
            }
        return try {
            val deadline = System.nanoTime() + config.startupTimeout.toNanos()
            while (System.nanoTime() < deadline) {
                val health = health()
                if (health.healthy) return health
                delay(100)
            }
            PaddleOcrHealth(false, detail = "sidecar 启动超时")
        } catch (cancelled: CancellationException) {
            stop()
            throw cancelled
        } finally {
            if (process?.isAlive != true) process = null
        }.also { health -> if (!health.healthy) stop() }
    }

    private suspend fun waitForEngine(engineId: String): PaddleOcrHealth {
        require(engineId.isNotBlank()) { "PaddleOCR engine ID must not be blank." }
        val deadline = System.nanoTime() + config.startupTimeout.toNanos()
        var current = health(engineId, load = true)
        while (!current.healthy && System.nanoTime() < deadline) {
            delay(100)
            current = health(engineId, load = true)
        }
        return current
    }

    suspend fun health(
        probeEngine: String? = null,
        load: Boolean = false,
    ): PaddleOcrHealth =
        withContext(ioDispatcher) {
            try {
                healthFromResponse(requestHealth(probeEngine, load))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IOException) {
                unavailableHealth(failure)
            } catch (failure: CompletionException) {
                unavailableHealth(failure)
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                unavailableHealth(failure)
            }
        }

    private suspend fun requestHealth(
        probeEngine: String?,
        load: Boolean,
    ): HttpResponse<String> {
        val healthUri =
            config.endpoint
                .resolve("/health")
                .let { uri ->
                    listOfNotNull(
                        probeEngine?.let { engine ->
                            "probe=${java.net.URLEncoder.encode(engine, Charsets.UTF_8)}"
                        },
                        "load=true".takeIf { load },
                    ).takeIf(List<String>::isNotEmpty)?.let { query -> URI.create("$uri?${query.joinToString("&")}") }
                        ?: uri
                }
        val request =
            HttpRequest
                .newBuilder(healthUri)
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build()
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
    }

    private fun healthFromResponse(response: HttpResponse<String>): PaddleOcrHealth {
        if (response.statusCode() !in 200..299) {
            return PaddleOcrHealth(false, detail = "HTTP ${response.statusCode()}")
        }
        val body = response.body()
        val json = Json.parseToJsonElement(body).jsonObject
        val reportedVersion = json["version"]?.jsonPrimitive?.contentOrNull
        val engineReadiness = parseEngineReadiness(json)
        val measuredMemoryBytes = json["rss_bytes"]?.jsonPrimitive?.longOrNull
        val runtime = parseRuntime(json)
        val runtimeFailure = runtimePreferenceFailure(runtime, config.requestedDevice)
        return when {
            reportedVersion == null -> {
                PaddleOcrHealth(
                    false,
                    detail = "sidecar 健康检查未报告版本",
                    engineReadiness = engineReadiness,
                    measuredMemoryBytes = measuredMemoryBytes,
                    runtime = runtime,
                )
            }

            reportedVersion != config.version -> {
                PaddleOcrHealth(
                    false,
                    version = reportedVersion,
                    detail = "sidecar 版本不匹配，期望 ${config.version}，实际 $reportedVersion",
                    engineReadiness = engineReadiness,
                    measuredMemoryBytes = measuredMemoryBytes,
                    runtime = runtime,
                )
            }

            runtimeFailure != null -> {
                PaddleOcrHealth(
                    false,
                    version = reportedVersion,
                    detail = runtimeFailure,
                    engineReadiness = engineReadiness,
                    measuredMemoryBytes = measuredMemoryBytes,
                    runtime = runtime,
                )
            }

            runtime.deviceAvailable == false -> {
                PaddleOcrHealth(
                    false,
                    version = reportedVersion,
                    detail = json["error"]?.jsonPrimitive?.contentOrNull ?: "Paddle 运行时设备不可用",
                    engineReadiness = engineReadiness,
                    measuredMemoryBytes = measuredMemoryBytes,
                    runtime = runtime,
                )
            }

            json["ready"]?.jsonPrimitive?.booleanOrNull == false -> {
                PaddleOcrHealth(
                    false,
                    version = reportedVersion,
                    detail = json["error"]?.jsonPrimitive?.contentOrNull ?: "sidecar 模型尚未就绪",
                    engineReadiness = engineReadiness,
                    measuredMemoryBytes = measuredMemoryBytes,
                    runtime = runtime,
                )
            }

            else -> {
                PaddleOcrHealth(
                    true,
                    version = reportedVersion,
                    detail = body.take(200),
                    engineReadiness = engineReadiness,
                    measuredMemoryBytes = measuredMemoryBytes,
                    runtime = runtime,
                )
            }
        }
    }

    private fun parseEngineReadiness(json: JsonObject): Map<String, Boolean> =
        json["engines"]
            ?.jsonObject
            ?.mapValues { (_, value) -> value.jsonPrimitive.booleanOrNull ?: false }
            .orEmpty()

    private fun parseRuntime(json: JsonObject): PaddleOcrRuntime {
        val runtime = json["runtime"]?.jsonObject ?: return PaddleOcrRuntime()
        return PaddleOcrRuntime(
            requestedDevice = runtime["requested_device"]?.jsonPrimitive?.contentOrNull,
            device = runtime["device"]?.jsonPrimitive?.contentOrNull,
            backend = runtime["backend"]?.jsonPrimitive?.contentOrNull,
            compiledBackends =
                runtime["compiled_backends"]
                    ?.jsonArray
                    ?.mapNotNull { value -> value.jsonPrimitive.contentOrNull }
                    ?.toSet()
                    .orEmpty(),
            availableDevices =
                runtime["available_devices"]
                    ?.jsonArray
                    ?.mapNotNull { value -> value.jsonPrimitive.contentOrNull }
                    .orEmpty(),
            deviceAvailable = runtime["device_available"]?.jsonPrimitive?.booleanOrNull,
        )
    }

    private fun unavailableHealth(failure: Exception): PaddleOcrHealth =
        PaddleOcrHealth(
            false,
            detail = failure.message ?: "sidecar 不可用",
        )

    @Synchronized
    fun stop() {
        process?.let { current ->
            current.destroy()
            if (!current.waitFor(2, TimeUnit.SECONDS)) current.destroyForcibly()
        }
        process = null
    }

    override fun close() = stop()

    private suspend fun installArtifact(): Path? {
        val artifact = config.artifact ?: return null
        return withContext(ioDispatcher) {
            val versionRoot = config.installDirectory.resolve(artifact.version).normalize()
            val executable = safeResolve(versionRoot, artifact.executablePath)
            val marker = versionRoot.resolve(INSTALL_MARKER)
            if (
                Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS) &&
                Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) &&
                Files.readString(marker).trim() == artifact.sha256
            ) {
                return@withContext executable
            }

            Files.createDirectories(config.installDirectory)
            val downloadDirectory = config.installDirectory.resolve(".downloads")
            Files.createDirectories(downloadDirectory)
            val download = downloadDirectory.resolve("${artifact.sha256}.part")
            val staging =
                config.installDirectory.resolve(".staging-${artifact.version}-${UUID.randomUUID()}").normalize()
            try {
                cleanupStagingDirectories(artifact.version)
                downloadArtifact(artifact, download)
                require(sha256(download) == artifact.sha256) {
                    Files.deleteIfExists(download)
                    "PaddleOCR artifact SHA-256 does not match the configured checksum."
                }
                Files.createDirectories(staging)
                when (artifact.format) {
                    PaddleOcrArtifactFormat.EXECUTABLE -> {
                        val target = safeResolve(staging, artifact.executablePath)
                        Files.createDirectories(checkNotNull(target.parent))
                        Files.move(download, target, StandardCopyOption.REPLACE_EXISTING)
                    }

                    PaddleOcrArtifactFormat.ZIP -> {
                        extractZip(download, staging)
                    }
                }
                val stagedExecutable = safeResolve(staging, artifact.executablePath)
                require(Files.isRegularFile(stagedExecutable, LinkOption.NOFOLLOW_LINKS)) {
                    "PaddleOCR artifact does not contain ${artifact.executablePath}."
                }
                makeExecutable(stagedExecutable)
                Files.writeString(staging.resolve(INSTALL_MARKER), artifact.sha256)
                if (Files.exists(versionRoot, LinkOption.NOFOLLOW_LINKS)) versionRoot.toFile().deleteRecursively()
                moveDirectory(staging, versionRoot)
                safeResolve(versionRoot, artifact.executablePath)
            } finally {
                if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) staging.toFile().deleteRecursively()
            }
        }
    }

    private suspend fun downloadArtifact(
        artifact: PaddleOcrSidecarArtifact,
        partial: Path,
    ) {
        val existingBytes =
            if (Files.isRegularFile(partial, LinkOption.NOFOLLOW_LINKS)) Files.size(partial) else 0L
        if (existingBytes > 0L && sha256(partial) == artifact.sha256) return
        val responsePath = partial.resolveSibling(".${partial.fileName}.response")
        Files.deleteIfExists(responsePath)
        try {
            val response = downloadAttempt(artifact.url, responsePath, existingBytes)
            when {
                response.statusCode() == 206 &&
                    existingBytes > 0L &&
                    response
                        .headers()
                        .firstValue("Content-Range")
                        .orElse("")
                        .startsWith("bytes $existingBytes-") -> {
                    Files.newInputStream(responsePath).use { input ->
                        Files
                            .newOutputStream(
                                partial,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE,
                                StandardOpenOption.APPEND,
                            ).use { output -> input.copyTo(output) }
                    }
                }

                response.statusCode() == 206 && existingBytes > 0L -> {
                    Files.deleteIfExists(partial)
                    val retry = downloadAttempt(artifact.url, responsePath, 0L)
                    require(retry.statusCode() in 200..299) {
                        "PaddleOCR artifact download returned HTTP ${retry.statusCode()}."
                    }
                    Files.move(responsePath, partial, StandardCopyOption.REPLACE_EXISTING)
                }

                response.statusCode() in 200..299 -> {
                    Files.move(responsePath, partial, StandardCopyOption.REPLACE_EXISTING)
                }

                else -> {
                    require(false) {
                        "PaddleOCR artifact download returned HTTP ${response.statusCode()}."
                    }
                }
            }
        } finally {
            Files.deleteIfExists(responsePath)
        }
    }

    private suspend fun downloadAttempt(
        url: URI,
        responsePath: Path,
        existingBytes: Long,
    ): HttpResponse<Path> {
        val requestBuilder =
            HttpRequest
                .newBuilder(url)
                .timeout(config.startupTimeout)
                .GET()
        if (existingBytes > 0L) requestBuilder.header("Range", "bytes=$existingBytes-")
        return client
            .sendAsync(
                requestBuilder.build(),
                HttpResponse.BodyHandlers.ofFile(
                    responsePath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                ),
            ).await()
    }

    private fun cleanupStagingDirectories(version: String) {
        Files.list(config.installDirectory).use { entries ->
            entries
                .filter { path ->
                    path.fileName.toString().startsWith(".staging-$version-")
                }.forEach { path -> path.toFile().deleteRecursively() }
        }
    }

    private fun extractZip(
        archive: Path,
        destination: Path,
    ) {
        var entryCount = 0
        var totalBytes = 0L
        ZipInputStream(BufferedInputStream(Files.newInputStream(archive))).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                require(++entryCount <= MAX_ZIP_ENTRIES) { "PaddleOCR archive contains too many entries." }
                require(entry.name.isNotBlank()) { "PaddleOCR archive contains an empty entry name." }
                require(entry.size < 0L || entry.size <= MAX_ZIP_ENTRY_BYTES) {
                    "PaddleOCR archive entry is too large."
                }
                val target = safeResolve(destination, entry.name)
                if (entry.isDirectory) {
                    Files.createDirectories(target)
                } else {
                    totalBytes = extractZipFile(input, target, totalBytes)
                }
                input.closeEntry()
            }
        }
    }

    private fun extractZipFile(
        input: ZipInputStream,
        target: Path,
        initialTotalBytes: Long,
    ): Long {
        Files.createDirectories(checkNotNull(target.parent))
        var totalBytes = initialTotalBytes
        var entryBytes = 0L
        Files.newOutputStream(target).use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                entryBytes += count
                require(entryBytes <= MAX_ZIP_ENTRY_BYTES) {
                    "PaddleOCR archive entry expands beyond the entry limit."
                }
                totalBytes += count
                require(totalBytes <= MAX_ZIP_TOTAL_BYTES) {
                    "PaddleOCR archive expands beyond the installation limit."
                }
                output.write(buffer, 0, count)
            }
        }
        return totalBytes
    }

    private fun moveDirectory(
        source: Path,
        target: Path,
    ) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(source, target)
        }
    }

    private fun makeExecutable(path: Path) {
        runCatching {
            val permissions = Files.getPosixFilePermissions(path)
            Files.setPosixFilePermissions(
                path,
                permissions +
                    setOf(
                        java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
                        java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE,
                    ),
            )
        }
    }

    private fun safeResolve(
        root: Path,
        relative: String,
    ): Path {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val resolved = normalizedRoot.resolve(relative).normalize()
        require(resolved.startsWith(normalizedRoot)) { "PaddleOCR artifact path escapes its install directory." }
        return resolved
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte ->
            byte
                .toInt()
                .and(0xff)
                .toString(16)
                .padStart(2, '0')
        }
    }

    private companion object {
        const val INSTALL_MARKER = ".xuexue-sha256"
        const val MAX_ZIP_ENTRIES = 10_000
        const val MAX_ZIP_ENTRY_BYTES = 512L * 1024 * 1024
        const val MAX_ZIP_TOTAL_BYTES = 2L * 1024 * 1024 * 1024
    }
}

private val SHA256_PATTERN = Regex("[0-9a-f]{64}")

private fun isSafeRelativePath(value: String): Boolean {
    val path = Path.of(value)
    return value.isNotBlank() && !path.isAbsolute && path.normalize() == path && path != Path.of(".")
}

private fun defaultPaddleOcrInstallDirectory(): Path {
    val os = System.getProperty("os.name", "")
    val root =
        when {
            os.contains("win", ignoreCase = true) -> {
                System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)?.let(Path::of)
                    ?: Path.of(System.getProperty("user.home"))
            }

            os.contains("mac", ignoreCase = true) -> {
                Path.of(System.getProperty("user.home"), "Library", "Caches")
            }

            else -> {
                System.getenv("XDG_CACHE_HOME")?.takeIf(String::isNotBlank)?.let(Path::of)
                    ?: Path.of(System.getProperty("user.home"), ".cache")
            }
        }
    return root.toAbsolutePath().normalize().resolve("xuexue/paddleocr")
}
