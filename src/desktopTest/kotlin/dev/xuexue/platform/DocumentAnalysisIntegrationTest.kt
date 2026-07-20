package dev.xuexue.platform

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DocumentAnalysisIntegrationTest {
    @Test
    fun ppStructureAdapterNormalizesCoordinatesAndPreservesEvidence() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/analyze") { exchange ->
            val response =
                """{"parsing_res_list":[{"block_label":"paragraph_title","block_content":"第一章","block_bbox":[10,20,90,20,90,100,10,100],"block_order":2,"block_prob":0.91}]}"""
                    .toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { output -> output.write(response) }
        }
        server.start()
        try {
            val engine =
                PpStructureV3Engine(
                    endpoint = URI("http://127.0.0.1:${server.address.port}/analyze"),
                    client = HttpClient.newHttpClient(),
                )
            val layout =
                runBlocking {
                    engine.analyzePage(
                        DocumentAnalysisRequest(
                            path = Path.of("scan.pdf"),
                            pageIndex = 3,
                            width = 100,
                            height = 200,
                            imagePng = byteArrayOf(1),
                        ),
                    )
                }

            assertEquals(PdfLayoutBlockType.SECTION_TITLE, layout.blocks.single().type)
            assertEquals("第一章", layout.blocks.single().text)
            assertEquals(
                0.1f,
                layout.blocks
                    .single()
                    .bounds.left,
            )
            assertEquals(
                0.1f,
                layout.blocks
                    .single()
                    .bounds.top,
            )
            assertEquals(
                0.9f,
                layout.blocks
                    .single()
                    .bounds.right,
            )
            assertEquals(
                0.5f,
                layout.blocks
                    .single()
                    .bounds.bottom,
            )
            assertEquals(0.91f, layout.blocks.single().recognitionConfidence)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun hostAvailabilitySeparatesRuntimeModelAndRecommendation() {
        val detector = HostComputeDetector { null }
        val profile =
            HostComputeProfile(
                os = "Linux",
                architecture = "x86_64",
                cpuThreads = 8,
                availableMemoryBytes = 8L * 1024 * 1024 * 1024,
                gpu = null,
            )
        val descriptors =
            listOf(
                NativePdfLayoutEngine().descriptor,
                PpStructureV3Engine().descriptor,
            )
        val matrix =
            detector.matrix(
                descriptors = descriptors,
                profile = profile,
                installedModels = setOf("native-pdf-layout"),
                runtimeInstalledByEngine = mapOf("pp-structure-v3" to false),
            )
        val recommended = detector.recommendedEngineId(matrix)
        val native = matrix.first { item -> item.engineId == "native-pdf-layout" }
        val pp = matrix.first { item -> item.engineId == "pp-structure-v3" }

        assertEquals("native-pdf-layout", recommended)
        assertEquals(AvailabilityState.AVAILABLE, native.state)
        assertEquals(AvailabilityState.BROKEN_INSTALLATION, pp.state)
        assertFalse(pp.recommended)
        assertTrue(native.estimatedSpeed == "CPU")
    }

    @Test
    fun nativeLayoutClassifiesMixedPagesAndRecordsHiddenTextEvidence() =
        runBlocking {
            val characters =
                "native text layer with enough characters".mapIndexed { index, character ->
                    PdfStructuredTextChar(
                        codePoint = character.code,
                        bounds = PdfAbsoluteRect(index.toFloat(), 20f, index + 8f, 32f),
                        size = 10f,
                        flags = 0,
                        argb = if (index == 0) 0 else -1,
                    )
                }
            val layout =
                NativePdfLayoutEngine().analyzePage(
                    DocumentAnalysisRequest(
                        path = Path.of("mixed.pdf"),
                        pageIndex = 0,
                        width = 612,
                        height = 792,
                        nativeText =
                            PdfStructuredTextPage(
                                widthPoints = 612f,
                                heightPoints = 792f,
                                blocks =
                                    listOf(
                                        PdfStructuredTextBlock(
                                            type = 0,
                                            bounds = PdfAbsoluteRect(20f, 20f, 400f, 60f),
                                            lines =
                                                listOf(
                                                    PdfStructuredTextLine(
                                                        bounds = PdfAbsoluteRect(20f, 20f, 400f, 60f),
                                                        vertical = false,
                                                        chars = characters,
                                                    ),
                                                ),
                                        ),
                                        PdfStructuredTextBlock(
                                            type = 1,
                                            bounds = PdfAbsoluteRect(0f, 0f, 612f, 792f),
                                        ),
                                    ),
                            ),
                    ),
                )

            assertEquals(PdfPageClassification.MIXED, layout.classification)
            assertEquals(1, layout.classificationEvidence?.imageBlockCount)
            assertTrue((layout.classificationEvidence?.nativeCoverage ?: 1f) < 0.1f)
            assertTrue((layout.classificationEvidence?.hiddenCharacterRatio ?: 0f) > 0f)
        }

    @Test
    fun nativeLayoutNormalizesStructuredTextAgainstThePageOrigin() =
        runBlocking {
            val layout =
                NativePdfLayoutEngine().analyzePage(
                    DocumentAnalysisRequest(
                        path = Path.of("offset.pdf"),
                        pageIndex = 0,
                        width = 100,
                        height = 100,
                        nativeText =
                            PdfStructuredTextPage(
                                widthPoints = 100f,
                                heightPoints = 100f,
                                originX = 50f,
                                originY = 80f,
                                blocks =
                                    listOf(
                                        PdfStructuredTextBlock(
                                            type = 0,
                                            bounds = PdfAbsoluteRect(60f, 90f, 80f, 100f),
                                            lines =
                                                listOf(
                                                    PdfStructuredTextLine(
                                                        bounds = PdfAbsoluteRect(60f, 90f, 80f, 100f),
                                                        vertical = false,
                                                        chars =
                                                            listOf(
                                                                PdfStructuredTextChar(
                                                                    codePoint = 'A'.code,
                                                                    bounds = PdfAbsoluteRect(60f, 90f, 80f, 100f),
                                                                    size = 10f,
                                                                    flags = 0,
                                                                    argb = -1,
                                                                ),
                                                            ),
                                                    ),
                                                ),
                                        ),
                                    ),
                            ),
                    ),
                )

            assertEquals(
                0.1f,
                layout.blocks
                    .single()
                    .bounds
                    .left,
            )
            assertEquals(
                0.1f,
                layout.blocks
                    .single()
                    .bounds
                    .top,
            )
        }

    @Test
    fun runtimeChecksCanRejectAValidInstallAfterHashOrBackendProbe() {
        val detector = HostComputeDetector { null }
        val descriptor = NativePdfLayoutEngine().descriptor
        val profile =
            HostComputeProfile(
                os = "Linux",
                architecture = "x86_64",
                cpuThreads = 8,
                availableMemoryBytes = 8L * 1024 * 1024 * 1024,
            )
        val hashFailure =
            detector
                .matrix(
                    descriptors = listOf(descriptor),
                    profile = profile,
                    runtimeChecks = mapOf(descriptor.engineId to EngineRuntimeCheck(modelHashMatches = false)),
                ).single()
        val backendFailure =
            detector
                .matrix(
                    descriptors = listOf(descriptor),
                    profile = profile,
                    runtimeChecks = mapOf(descriptor.engineId to EngineRuntimeCheck(backendSupported = false)),
                ).single()

        assertEquals(AvailabilityState.BROKEN_INSTALLATION, hashFailure.state)
        assertEquals(AvailabilityState.UNSUPPORTED, backendFailure.state)
    }

    @Test
    fun lowCpuMemoryIsReportedBeforeTheMissingGpuWarning() {
        val detector = HostComputeDetector { null }
        val availability =
            detector.availability(
                descriptor = PpStructureV3Engine().descriptor,
                profile =
                    HostComputeProfile(
                        os = "Linux",
                        architecture = "x86_64",
                        cpuThreads = 4,
                        availableMemoryBytes = 256L * 1024 * 1024,
                    ),
            )

        assertEquals(AvailabilityState.INSUFFICIENT_MEMORY, availability.state)
    }

    @Test
    fun measuredSidecarMemoryIsReportedBeforeCpuFallbackWarning() {
        val detector = HostComputeDetector { null }
        val availability =
            detector.availability(
                descriptor = PpStructureV3Engine().descriptor,
                profile =
                    HostComputeProfile(
                        os = "Linux",
                        architecture = "x86_64",
                        cpuThreads = 4,
                        availableMemoryBytes = 2L * 1024 * 1024 * 1024,
                    ),
                runtime =
                    EngineRuntimeCheck(
                        measuredMemoryBytes = 3L * 1024 * 1024 * 1024,
                    ),
            )

        assertEquals(AvailabilityState.INSUFFICIENT_MEMORY, availability.state)
    }

    @Test
    fun cpuPaddleRuntimeDoesNotClaimAHostGpuIsBeingUsed() {
        val detector = HostComputeDetector { null }
        val availability =
            detector.availability(
                descriptor = PpStructureV3Engine().descriptor,
                profile =
                    HostComputeProfile(
                        os = "Linux",
                        architecture = "x86_64",
                        cpuThreads = 8,
                        availableMemoryBytes = 8L * 1024 * 1024 * 1024,
                        gpu =
                            GpuProfile(
                                vendor = "Intel",
                                deviceName = "Arc B570",
                                backend = "SYCL",
                            ),
                    ),
                runtime =
                    EngineRuntimeCheck(
                        backendSupported = true,
                        runtimeBackend = "cpu",
                    ),
            )

        assertEquals(AvailabilityState.AVAILABLE_WITH_WARNING, availability.state)
        assertEquals("CPU 回退", availability.estimatedSpeed)
        assertTrue(availability.reasons.single().contains("Paddle"))
    }

    @Test
    fun reportedIntelCustomRuntimeCountsAsGpuEvenWithoutSyclLsProbe() {
        val detector = HostComputeDetector { null }
        val availability =
            detector.availability(
                descriptor = PpStructureV3Engine().descriptor,
                profile =
                    HostComputeProfile(
                        os = "Linux",
                        architecture = "x86_64",
                        cpuThreads = 8,
                        availableMemoryBytes = 8L * 1024 * 1024 * 1024,
                        gpu =
                            GpuProfile(
                                vendor = "Intel",
                                deviceName = "Arc B570",
                                backend = "system",
                            ),
                    ),
                runtime =
                    EngineRuntimeCheck(
                        backendSupported = true,
                        runtimeBackend = "custom:intel_gpu",
                    ),
            )

        assertEquals(AvailabilityState.AVAILABLE, availability.state)
        assertEquals("GPU", availability.estimatedSpeed)
    }

    @Test
    fun sidecarHealthChecksExpectedVersion() =
        runBlocking {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/health") { exchange ->
                val response =
                    """{"version":"test-version","rss_bytes":123456,"runtime":{"requested_device":"auto","device":"cpu","backend":"cpu","compiled_backends":["cpu"],"available_devices":[],"device_available":true}}"""
                        .toByteArray()
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { output -> output.write(response) }
            }
            server.start()
            val manager =
                PaddleOcrServiceManager(
                    config =
                        PaddleOcrSidecarConfig(
                            endpoint = URI("http://127.0.0.1:${server.address.port}"),
                            version = "test-version",
                        ),
                    ioDispatcher = UnconfinedTestDispatcher(),
                )
            try {
                val health = manager.ensureReady()
                assertTrue(health.healthy)
                assertEquals("test-version", health.version)
                assertEquals(123456L, health.measuredMemoryBytes)
                assertEquals("auto", health.runtime.requestedDevice)
                assertEquals("cpu", health.runtime.device)
                assertEquals("cpu", health.runtime.backend)
                assertEquals(setOf("cpu"), health.runtime.compiledBackends)
                assertTrue(health.runtime.deviceAvailable == true)
            } finally {
                manager.close()
                server.stop(0)
            }
        }
}
