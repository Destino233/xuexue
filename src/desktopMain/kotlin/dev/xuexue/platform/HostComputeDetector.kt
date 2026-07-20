package dev.xuexue.platform

import kotlinx.serialization.Serializable
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.TimeUnit

@Serializable
data class HostComputeProfile(
    val os: String,
    val architecture: String,
    val cpuThreads: Int,
    val availableMemoryBytes: Long,
    val gpu: GpuProfile? = null,
    val detectedAt: String = Instant.now().toString(),
)

@Serializable
data class GpuProfile(
    val vendor: String,
    val deviceName: String,
    val driverVersion: String? = null,
    val backend: String,
    val memoryBytes: Long? = null,
    val availableMemoryBytes: Long? = null,
    val supportedPrecisions: Set<String> = emptySet(),
)

enum class AvailabilityState {
    AVAILABLE,
    AVAILABLE_WITH_WARNING,
    DOWNLOAD_REQUIRED,
    UNSUPPORTED,
    INSUFFICIENT_MEMORY,
    BROKEN_INSTALLATION,
}

@Serializable
data class EngineAvailability(
    val engineId: String,
    val state: AvailabilityState,
    val reasons: List<String> = emptyList(),
    val estimatedMemoryBytes: Long? = null,
    val estimatedSpeed: String? = null,
    val requiresDownload: Boolean = false,
    val runtimeVersion: String? = null,
    val recommended: Boolean = false,
)

data class EngineRuntimeCheck(
    val runtimeInstalled: Boolean = true,
    val modelInstalled: Boolean = true,
    val modelHashMatches: Boolean? = null,
    val backendSupported: Boolean? = null,
    val runtimeBackend: String? = null,
    val measuredMemoryBytes: Long? = null,
    val runtimeVersion: String? = null,
)

fun interface HostCommandRunner {
    fun run(command: List<String>): String?
}

class HostComputeDetector(
    private val commandRunner: HostCommandRunner = HostCommandRunner(::runCommand),
) {
    fun detect(): HostComputeProfile {
        val gpu = detectNvidiaGpu() ?: detectAmdGpu() ?: detectIntelGpu() ?: detectLinuxGraphics()
        return HostComputeProfile(
            os = System.getProperty("os.name", "unknown"),
            architecture = System.getProperty("os.arch", "unknown"),
            cpuThreads = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
            availableMemoryBytes = availableMemoryBytes(),
            gpu = gpu,
        )
    }

    // Availability is a deliberate ordered decision matrix: runtime, model, backend, and memory failures
    // must retain distinct user-facing states instead of being collapsed into one probe result.
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun availability(
        descriptor: DocumentAnalysisEngineDescriptor,
        profile: HostComputeProfile = detect(),
        runtime: EngineRuntimeCheck = EngineRuntimeCheck(),
        recommended: Boolean = false,
    ): EngineAvailability {
        val estimatedMemory =
            descriptor.estimatedMemoryBytes?.coerceAtLeast(DEFAULT_ENGINE_MEMORY_BYTES)
        val paddleGpuBackendAvailable =
            runtime.runtimeBackend
                ?.let(::isGpuRuntimeBackend)
                ?: true
        val gpuComputeAvailable = profile.gpu?.backend in COMPUTE_BACKENDS && paddleGpuBackendAvailable
        val noGpu = descriptor.backends.contains(AnalysisComputeBackend.GPU) && !gpuComputeAvailable
        val memory = profile.gpu?.availableMemoryBytes ?: profile.availableMemoryBytes
        return when {
            runtime.backendSupported == false -> {
                EngineAvailability(
                    descriptor.engineId,
                    AvailabilityState.UNSUPPORTED,
                    reasons = listOf("当前平台或驱动不支持该识别后端"),
                    estimatedMemoryBytes = estimatedMemory,
                    runtimeVersion = runtime.runtimeVersion,
                    recommended = recommended,
                )
            }

            !runtime.runtimeInstalled -> {
                EngineAvailability(
                    descriptor.engineId,
                    AvailabilityState.BROKEN_INSTALLATION,
                    reasons = listOf("识别运行时未安装或健康检查失败"),
                    estimatedMemoryBytes = estimatedMemory,
                    runtimeVersion = runtime.runtimeVersion,
                    recommended = recommended,
                )
            }

            !runtime.modelInstalled -> {
                EngineAvailability(
                    descriptor.engineId,
                    AvailabilityState.DOWNLOAD_REQUIRED,
                    reasons = listOf("模型尚未下载"),
                    estimatedMemoryBytes = estimatedMemory,
                    requiresDownload = true,
                    runtimeVersion = runtime.runtimeVersion,
                    recommended = recommended,
                )
            }

            runtime.modelHashMatches == false -> {
                EngineAvailability(
                    descriptor.engineId,
                    AvailabilityState.BROKEN_INSTALLATION,
                    reasons = listOf("模型文件校验和不匹配"),
                    estimatedMemoryBytes = estimatedMemory,
                    runtimeVersion = runtime.runtimeVersion,
                    recommended = recommended,
                )
            }

            runtime.measuredMemoryBytes != null && runtime.measuredMemoryBytes > profile.availableMemoryBytes -> {
                EngineAvailability(
                    descriptor.engineId,
                    AvailabilityState.INSUFFICIENT_MEMORY,
                    reasons = listOf("模型启动实测系统内存超过当前可用额度"),
                    estimatedMemoryBytes = runtime.measuredMemoryBytes,
                    runtimeVersion = runtime.runtimeVersion,
                    recommended = recommended,
                )
            }

            noGpu && !descriptor.backends.contains(AnalysisComputeBackend.CPU) -> {
                EngineAvailability(
                    descriptor.engineId,
                    AvailabilityState.UNSUPPORTED,
                    reasons = listOf("未检测到可用 GPU 后端"),
                    estimatedMemoryBytes = estimatedMemory,
                    runtimeVersion = runtime.runtimeVersion,
                    recommended = recommended,
                )
            }

            estimatedMemory != null && memory in 1 until estimatedMemory -> {
                EngineAvailability(
                    descriptor.engineId,
                    AvailabilityState.INSUFFICIENT_MEMORY,
                    reasons = listOf("预计内存不足"),
                    estimatedMemoryBytes = estimatedMemory,
                    runtimeVersion = runtime.runtimeVersion,
                    recommended = recommended,
                )
            }

            noGpu -> {
                EngineAvailability(
                    descriptor.engineId,
                    AvailabilityState.AVAILABLE_WITH_WARNING,
                    reasons =
                        listOf(
                            if (runtime.runtimeBackend.equals("cpu", ignoreCase = true)) {
                                "Paddle 运行时当前使用 CPU，将使用 CPU，预计速度较慢"
                            } else {
                                "未检测到可用 GPU，将使用 CPU，预计速度较慢"
                            },
                        ),
                    estimatedMemoryBytes = estimatedMemory,
                    estimatedSpeed = "CPU 回退",
                    runtimeVersion = runtime.runtimeVersion,
                    recommended = recommended,
                )
            }

            else -> {
                EngineAvailability(
                    descriptor.engineId,
                    AvailabilityState.AVAILABLE,
                    estimatedMemoryBytes = estimatedMemory,
                    estimatedSpeed =
                        if (gpuComputeAvailable && descriptor.backends.contains(AnalysisComputeBackend.GPU)) {
                            "GPU"
                        } else {
                            "CPU"
                        },
                    runtimeVersion = runtime.runtimeVersion,
                    recommended = recommended,
                )
            }
        }
    }

    fun matrix(
        descriptors: Iterable<DocumentAnalysisEngineDescriptor>,
        profile: HostComputeProfile = detect(),
        installedModels: Set<String> = emptySet(),
        runtimeInstalledByEngine: Map<String, Boolean> = emptyMap(),
        runtimeVersions: Map<String, String?> = emptyMap(),
        recommendedEngineId: String? = null,
        runtimeChecks: Map<String, EngineRuntimeCheck> = emptyMap(),
    ): List<EngineAvailability> =
        descriptors.map { descriptor ->
            val check = runtimeChecks[descriptor.engineId]
            availability(
                descriptor = descriptor,
                profile = profile,
                runtime =
                    check ?: EngineRuntimeCheck(
                        modelInstalled =
                            descriptor.engineId in installedModels ||
                                descriptor.engineId == NativePdfLayoutEngine().descriptor.engineId,
                        runtimeInstalled = runtimeInstalledByEngine[descriptor.engineId] ?: true,
                        runtimeVersion = runtimeVersions[descriptor.engineId],
                    ),
                recommended = descriptor.engineId == recommendedEngineId,
            )
        }

    fun recommendedEngineId(availability: List<EngineAvailability>): String? {
        val preferredOrder = listOf("pp-structure-v3", "native-pdf-layout", "paddleocr-vl")
        return preferredOrder.firstOrNull { engineId ->
            availability.any { item ->
                item.engineId == engineId &&
                    item.state in setOf(AvailabilityState.AVAILABLE, AvailabilityState.AVAILABLE_WITH_WARNING)
            }
        } ?: availability
            .firstOrNull { item ->
                item.state in setOf(AvailabilityState.AVAILABLE, AvailabilityState.AVAILABLE_WITH_WARNING)
            }?.engineId
    }

    private fun detectNvidiaGpu(): GpuProfile? =
        commandRunner
            .run(
                listOf(
                    "nvidia-smi",
                    "--query-gpu=name,driver_version,memory.total,memory.free",
                    "--format=csv,noheader,nounits",
                ),
            )?.lineSequence()
            ?.firstOrNull()
            ?.split(',')
            ?.map(String::trim)
            ?.takeIf(List<String>::isNotEmpty)
            ?.let { fields ->
                GpuProfile(
                    vendor = "NVIDIA",
                    deviceName = fields.getOrElse(0) { "NVIDIA GPU" },
                    driverVersion = fields.getOrNull(1),
                    backend = "CUDA",
                    memoryBytes = fields.getOrNull(2)?.toLongOrNull()?.times(MIB),
                    availableMemoryBytes = fields.getOrNull(3)?.toLongOrNull()?.times(MIB),
                    supportedPrecisions = setOf("fp16", "fp32", "int8"),
                )
            }

    private fun detectAmdGpu(): GpuProfile? =
        commandRunner
            .run(listOf("rocminfo"))
            ?.lineSequence()
            ?.map(String::trim)
            ?.firstOrNull { line -> line.startsWith("Name:") && !line.contains("CPU", ignoreCase = true) }
            ?.let { line ->
                GpuProfile(
                    vendor = "AMD",
                    deviceName = line.substringAfter(':').trim().ifBlank { "AMD GPU" },
                    backend = "ROCm",
                    supportedPrecisions = setOf("fp16", "fp32", "int8"),
                )
            }

    private fun detectIntelGpu(): GpuProfile? =
        commandRunner
            .run(listOf("sycl-ls"))
            ?.lineSequence()
            ?.map(String::trim)
            ?.firstOrNull { line -> line.contains("gpu", ignoreCase = true) }
            ?.let { line ->
                GpuProfile(
                    vendor = "Intel",
                    deviceName = line.substringAfterLast(']').trim().ifBlank { "Intel GPU" },
                    backend = "SYCL",
                    supportedPrecisions = setOf("fp16", "fp32", "int8"),
                )
            }

    private fun detectLinuxGraphics(): GpuProfile? =
        System
            .getProperty("os.name", "")
            .takeIf { os -> os.contains("linux", ignoreCase = true) }
            ?.let { commandRunner.run(listOf("lspci", "-nn")) }
            ?.lineSequence()
            ?.firstOrNull { value ->
                value.contains("VGA", ignoreCase = true) ||
                    value.contains("3D controller", ignoreCase = true)
            }?.let { line ->
                val vendor =
                    when {
                        line.contains("NVIDIA", ignoreCase = true) -> "NVIDIA"
                        line.contains("AMD", ignoreCase = true) || line.contains("ATI", ignoreCase = true) -> "AMD"
                        line.contains("Intel", ignoreCase = true) -> "Intel"
                        else -> "Unknown"
                    }
                GpuProfile(
                    vendor = vendor,
                    deviceName = line.substringAfter(": ", line).trim(),
                    backend = "system",
                )
            }

    private fun availableMemoryBytes(): Long {
        val memory =
            runCatching {
                val bean =
                    java.lang.management.ManagementFactory
                        .getOperatingSystemMXBean()
                bean.javaClass.methods
                    .firstOrNull { method -> method.name == "getFreeMemorySize" }
                    ?.invoke(bean) as? Long
            }.getOrNull()
        val linuxAvailable =
            runCatching {
                if (!System.getProperty("os.name", "").contains("linux", ignoreCase = true)) {
                    null
                } else {
                    Files
                        .readString(Path.of("/proc/meminfo"))
                        .lineSequence()
                        .firstOrNull { line -> line.startsWith("MemAvailable:") }
                        ?.filter(Char::isDigit)
                        ?.toLongOrNull()
                        ?.times(1024L)
                }
            }.getOrNull()
        return (linuxAvailable ?: memory ?: Runtime.getRuntime().maxMemory()).coerceAtLeast(0L)
    }

    fun modelHashMatches(
        path: Path,
        expectedSha256: String,
    ): Boolean {
        require(expectedSha256.matches(SHA256_PATTERN)) {
            "Model SHA-256 must be a lowercase 64-character hex string."
        }
        if (!Files.isRegularFile(path)) return false
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        val actual =
            digest.digest().joinToString("") { byte ->
                byte
                    .toInt()
                    .and(0xff)
                    .toString(16)
                    .padStart(2, '0')
            }
        return actual == expectedSha256
    }

    private companion object {
        const val MIB = 1024L * 1024L
        const val DEFAULT_ENGINE_MEMORY_BYTES = 512L * MIB
        val SHA256_PATTERN = Regex("[0-9a-f]{64}")
        val COMPUTE_BACKENDS = setOf("CUDA", "ROCm", "SYCL")
        val PADDLE_GPU_BACKENDS = setOf("cuda", "xpu", "rocm", "sycl")

        fun isGpuRuntimeBackend(backend: String): Boolean {
            val normalized = backend.trim().lowercase()
            return normalized in PADDLE_GPU_BACKENDS || normalized.startsWith("custom:")
        }

        fun runCommand(command: List<String>): String? =
            runCatching {
                val process = ProcessBuilder(command).redirectErrorStream(true).start()
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    return null
                }
                if (process.exitValue() != 0) return null
                BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            }.getOrNull()
    }
}
