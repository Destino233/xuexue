package dev.xuexue.platform

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions") // Lifecycle, device selection, and archive cases share one sidecar fixture boundary.
class PaddleOcrServiceManagerTest {
    @Test
    fun zipArtifactIsVerifiedInstalledAndReused() =
        runBlocking {
            val payload = zipPayload("bin/sidecar", "sidecar-binary")
            val requests = AtomicInteger()
            val server = artifactServer(payload, requests)
            val installDirectory = Files.createTempDirectory("xuexue-paddle-install")
            server.start()
            try {
                val manager =
                    PaddleOcrServiceManager(
                        config =
                            PaddleOcrSidecarConfig(
                                endpoint = URI("http://127.0.0.1:${server.address.port}"),
                                version = "test-version",
                                installDirectory = installDirectory,
                                artifact =
                                    PaddleOcrSidecarArtifact(
                                        url = URI("http://127.0.0.1:${server.address.port}/artifact"),
                                        sha256 = sha256(payload),
                                        version = "test-version",
                                        executablePath = "bin/sidecar",
                                        format = PaddleOcrArtifactFormat.ZIP,
                                    ),
                            ),
                        ioDispatcher = UnconfinedTestDispatcher(),
                    )
                try {
                    val first = checkNotNull(manager.installOrUpdate())
                    val second = checkNotNull(manager.installOrUpdate())
                    assertEquals(first, second)
                    assertContentEquals("sidecar-binary".encodeToByteArray(), Files.readAllBytes(first))
                    assertEquals(1, requests.get())
                } finally {
                    manager.close()
                }
            } finally {
                server.stop(0)
                installDirectory.toFile().deleteRecursively()
            }
        }

    @Test
    fun checksumMismatchLeavesNoVersionDirectory() =
        runBlocking {
            val payload = "invalid-artifact".encodeToByteArray()
            val server = artifactServer(payload, AtomicInteger())
            val installDirectory = Files.createTempDirectory("xuexue-paddle-invalid")
            server.start()
            try {
                val manager =
                    PaddleOcrServiceManager(
                        config =
                            PaddleOcrSidecarConfig(
                                endpoint = URI("http://127.0.0.1:${server.address.port}"),
                                version = "bad-version",
                                installDirectory = installDirectory,
                                artifact =
                                    PaddleOcrSidecarArtifact(
                                        url = URI("http://127.0.0.1:${server.address.port}/artifact"),
                                        sha256 = "0".repeat(64),
                                        version = "bad-version",
                                        executablePath = "sidecar",
                                    ),
                            ),
                        ioDispatcher = UnconfinedTestDispatcher(),
                    )
                try {
                    assertFailsWith<IllegalArgumentException> { manager.installOrUpdate() }
                    assertEquals(false, Files.exists(installDirectory.resolve("bad-version")))
                } finally {
                    manager.close()
                }
            } finally {
                server.stop(0)
                installDirectory.toFile().deleteRecursively()
            }
        }

    @Test
    fun partialArtifactDownloadResumesWithRangeRequest() =
        runBlocking {
            val payload = "0123456789abcdef".encodeToByteArray()
            val requests = AtomicInteger()
            val ranges = mutableListOf<String>()
            val server =
                HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { item ->
                    item.createContext("/artifact") { exchange ->
                        requests.incrementAndGet()
                        val range = exchange.requestHeaders.getFirst("Range").orEmpty()
                        ranges += range
                        val start = range.substringAfter("bytes=", "0").substringBefore('-').toInt()
                        val body = payload.copyOfRange(start, payload.size)
                        if (start > 0) {
                            exchange.responseHeaders.add(
                                "Content-Range",
                                "bytes $start-${payload.lastIndex}/${payload.size}",
                            )
                            exchange.sendResponseHeaders(206, body.size.toLong())
                        } else {
                            exchange.sendResponseHeaders(200, body.size.toLong())
                        }
                        exchange.responseBody.use { output -> output.write(body) }
                    }
                }
            val installDirectory = Files.createTempDirectory("xuexue-paddle-resume")
            val hash = sha256(payload)
            Files.createDirectories(installDirectory.resolve(".downloads"))
            Files.write(
                installDirectory.resolve(".downloads/$hash.part"),
                payload.copyOfRange(0, 5),
            )
            server.start()
            try {
                val manager =
                    PaddleOcrServiceManager(
                        config =
                            PaddleOcrSidecarConfig(
                                endpoint = URI("http://127.0.0.1:${server.address.port}"),
                                version = "resume-version",
                                installDirectory = installDirectory,
                                artifact =
                                    PaddleOcrSidecarArtifact(
                                        url = URI("http://127.0.0.1:${server.address.port}/artifact"),
                                        sha256 = hash,
                                        version = "resume-version",
                                        executablePath = "sidecar",
                                    ),
                            ),
                        ioDispatcher = UnconfinedTestDispatcher(),
                    )
                try {
                    val installed = checkNotNull(manager.installOrUpdate())
                    assertTrue(Files.isRegularFile(installed))
                    assertEquals(listOf("bytes=5-"), ranges)
                    assertEquals(1, requests.get())
                } finally {
                    manager.close()
                }
            } finally {
                server.stop(0)
                installDirectory.toFile().deleteRecursively()
            }
        }

    @Test
    fun startsAndStopsAManagedSidecarProcess() =
        runBlocking {
            val root = Files.createTempDirectory("xuexue-paddle-lifecycle")
            val started = root.resolve("started")
            val stopped = root.resolve("stopped")
            val port = ServerSocket(0).use(ServerSocket::getLocalPort)
            val version = "lifecycle-version"
            val command =
                listOf(
                    javaExecutable().toString(),
                    "-cp",
                    checkNotNull(System.getProperty("java.class.path")),
                    "dev.xuexue.platform.PaddleOcrTestSidecarMain",
                    port.toString(),
                    version,
                    started.toString(),
                    stopped.toString(),
                )
            val manager =
                PaddleOcrServiceManager(
                    config =
                        PaddleOcrSidecarConfig(
                            executable = command,
                            endpoint = URI("http://127.0.0.1:$port"),
                            version = version,
                            startupTimeout = java.time.Duration.ofSeconds(5),
                        ),
                    ioDispatcher = UnconfinedTestDispatcher(),
                )
            try {
                val health = manager.start()
                assertTrue(health.healthy)
                assertEquals(version, health.version)
                assertTrue(Files.exists(started))
            } finally {
                manager.close()
                assertTrue(Files.exists(stopped), "Managed sidecar did not run its shutdown hook")
                root.toFile().deleteRecursively()
            }
        }

    @Test
    fun engineHealthProbeReportsEachEngineIndependently() =
        runBlocking {
            val server =
                HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { item ->
                    item.createContext("/health") { exchange ->
                        val vl = exchange.requestURI.query == "probe=paddleocr-vl"
                        val response =
                            if (vl) {
                                """{"version":"probe-version","ready":false,"runtime":{"requested_device":"auto","device":"cpu","backend":"cpu","compiled_backends":["cpu"],"available_devices":[],"device_available":true},"engines":{"pp-structure-v3":true,"paddleocr-vl":false},"error":"VL missing"}"""
                            } else {
                                """{"version":"probe-version","ready":true,"runtime":{"requested_device":"auto","device":"cpu","backend":"cpu","compiled_backends":["cpu"],"available_devices":[],"device_available":true},"engines":{"pp-structure-v3":true,"paddleocr-vl":false}}"""
                            }
                        exchange.sendResponseHeaders(200, response.encodeToByteArray().size.toLong())
                        exchange.responseBody.use { output -> output.write(response.encodeToByteArray()) }
                    }
                }
            server.start()
            val manager =
                PaddleOcrServiceManager(
                    config =
                        PaddleOcrSidecarConfig(
                            endpoint = URI("http://127.0.0.1:${server.address.port}"),
                            version = "probe-version",
                        ),
                    ioDispatcher = UnconfinedTestDispatcher(),
                )
            try {
                val pp = manager.probeEngine("pp-structure-v3")
                val vl = manager.probeEngine("paddleocr-vl")

                assertTrue(pp.healthy)
                assertTrue(pp.isEngineReady("pp-structure-v3"))
                assertTrue(pp.isEngineReady("paddleocr-vl").not())
                assertTrue(vl.healthy.not())
                assertTrue(vl.isEngineReady("paddleocr-vl").not())
            } finally {
                manager.close()
                server.stop(0)
            }
        }

    @Test
    fun ensureReadyWaitsForTheSelectedEngineAfterBaseSidecarIsHealthy() =
        runBlocking {
            val vlReady = AtomicBoolean(false)
            val server =
                HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { item ->
                    item.createContext("/health") { exchange ->
                        val isVlProbe =
                            exchange.requestURI.query
                                .orEmpty()
                                .contains("probe=paddleocr-vl")
                        val ready = !isVlProbe || vlReady.getAndSet(true)
                        val response =
                            """{"version":"selected-engine-version","ready":$ready,"runtime":{"requested_device":"auto","device":"cpu","backend":"cpu","compiled_backends":["cpu"],"available_devices":[],"device_available":true},"engines":{"pp-structure-v3":true,"paddleocr-vl":$ready}}"""
                        exchange.sendResponseHeaders(200, response.encodeToByteArray().size.toLong())
                        exchange.responseBody.use { output -> output.write(response.encodeToByteArray()) }
                    }
                }
            server.start()
            val manager =
                PaddleOcrServiceManager(
                    config =
                        PaddleOcrSidecarConfig(
                            endpoint = URI("http://127.0.0.1:${server.address.port}"),
                            version = "selected-engine-version",
                            startupTimeout = java.time.Duration.ofSeconds(2),
                        ),
                    ioDispatcher = UnconfinedTestDispatcher(),
                )
            try {
                val health = manager.ensureReady(PaddleOcrVlEngine.ENGINE_ID)
                assertTrue(health.healthy)
                assertTrue(vlReady.get())
            } finally {
                manager.close()
                server.stop(0)
            }
        }

    @Test
    fun autoRejectsCpuSidecarWhenAnAcceleratorIsReported() =
        runBlocking {
            val server =
                HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { item ->
                    item.createContext("/health") { exchange ->
                        val response =
                            """
                            {
                              "version": "gpu-first-version",
                              "ready": true,
                              "runtime": {
                                "requested_device": "auto",
                                "device": "cpu",
                                "backend": "cpu",
                                "compiled_backends": ["cpu", "custom:intel_gpu"],
                                "available_devices": ["intel_gpu:0"],
                                "device_available": true
                              }
                            }
                            """.trimIndent()
                        exchange.sendResponseHeaders(200, response.encodeToByteArray().size.toLong())
                        exchange.responseBody.use { output -> output.write(response.encodeToByteArray()) }
                    }
                }
            server.start()
            val manager =
                PaddleOcrServiceManager(
                    config =
                        PaddleOcrSidecarConfig(
                            endpoint = URI("http://127.0.0.1:${server.address.port}"),
                            version = "gpu-first-version",
                        ),
                    ioDispatcher = UnconfinedTestDispatcher(),
                )
            try {
                val health = manager.ensureReady()
                assertFalse(health.healthy)
                assertTrue(health.detail.orEmpty().contains("GPU-first"))
            } finally {
                manager.close()
                server.stop(0)
            }
        }

    @Test
    fun explicitGpuRequestRejectsCpuSidecar() =
        runBlocking {
            val server =
                HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { item ->
                    item.createContext("/health") { exchange ->
                        val response =
                            """
                            {
                              "version": "explicit-gpu-version",
                              "ready": true,
                              "runtime": {
                                "requested_device": "gpu:0",
                                "device": "cpu",
                                "backend": "cpu",
                                "compiled_backends": ["cpu"],
                                "available_devices": [],
                                "device_available": true
                              }
                            }
                            """.trimIndent()
                        exchange.sendResponseHeaders(200, response.encodeToByteArray().size.toLong())
                        exchange.responseBody.use { output -> output.write(response.encodeToByteArray()) }
                    }
                }
            server.start()
            val manager =
                PaddleOcrServiceManager(
                    config =
                        PaddleOcrSidecarConfig(
                            endpoint = URI("http://127.0.0.1:${server.address.port}"),
                            version = "explicit-gpu-version",
                            requestedDevice = "gpu:0",
                        ),
                    ioDispatcher = UnconfinedTestDispatcher(),
                )
            try {
                val health = manager.ensureReady()
                assertFalse(health.healthy)
                assertTrue(health.detail.orEmpty().contains("requested gpu:0"))
            } finally {
                manager.close()
                server.stop(0)
            }
        }

    private fun artifactServer(
        payload: ByteArray,
        requests: AtomicInteger,
    ): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { server ->
            server.createContext("/artifact") { exchange ->
                requests.incrementAndGet()
                exchange.sendResponseHeaders(200, payload.size.toLong())
                exchange.responseBody.use { output -> output.write(payload) }
            }
        }

    private fun zipPayload(
        entryName: String,
        content: String,
    ): ByteArray =
        ByteArrayOutputStream()
            .also { bytes ->
                ZipOutputStream(bytes).use { zip ->
                    zip.putNextEntry(ZipEntry(entryName))
                    zip.write(content.encodeToByteArray())
                    zip.closeEntry()
                }
            }.toByteArray()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte ->
                byte
                    .toInt()
                    .and(0xff)
                    .toString(16)
                    .padStart(2, '0')
            }

    private fun javaExecutable(): Path {
        val executable =
            if (System.getProperty("os.name", "").contains("win", ignoreCase = true)) {
                "java.exe"
            } else {
                "java"
            }
        return Path.of(System.getProperty("java.home"), "bin", executable)
    }
}
