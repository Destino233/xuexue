package dev.xuexue.platform

import kotlinx.coroutines.future.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

class PpStructureV3Engine(
    private val endpoint: URI = URI.create("http://127.0.0.1:8765/analyze"),
    private val client: HttpClient = defaultClient(),
    private val requestTimeout: Duration = Duration.ofMinutes(5),
    private val engineVersion: String = DEFAULT_ENGINE_VERSION,
) : DocumentAnalysisEngine {
    init {
        requireLoopbackEndpoint(endpoint)
        require(engineVersion.isNotBlank()) { "PP-StructureV3 engine version must not be blank." }
    }

    override val descriptor =
        DocumentAnalysisEngineDescriptor(
            engineId = ENGINE_ID,
            displayName = "高精度中文 · PP-StructureV3",
            engineVersion = engineVersion,
            modelVersion = "PP-StructureV3",
            languages = setOf("chi_sim", "en"),
            supportsLayout = true,
            supportsVerticalText = true,
            supportsTables = true,
            supportsFormulas = true,
            backends = setOf(AnalysisComputeBackend.CPU, AnalysisComputeBackend.GPU),
            privacy = AnalysisPrivacy.LOOPBACK,
            license = "PaddleOCR license",
            downloadBytes = 2L * 1024 * 1024 * 1024,
            estimatedMemoryBytes = 1536L * 1024 * 1024,
        )

    override suspend fun analyzePage(request: DocumentAnalysisRequest): PdfPageLayout {
        require(request.imagePng?.isNotEmpty() == true) { "PP-StructureV3 requires a page image." }
        val payload =
            buildJsonObject {
                put("page_index", request.pageIndex)
                put("width", request.width)
                put("height", request.height)
                put("dpi", request.dpi)
                put("language", request.language)
                put("image_base64", Base64.getEncoder().encodeToString(request.imagePng))
            }
        val response = postJson(endpoint, payload)
        return PpStructureResponseMapper.toLayout(
            response = response,
            request = request,
            descriptor = descriptor,
        )
    }

    companion object {
        const val ENGINE_ID = "pp-structure-v3"
        const val DEFAULT_ENGINE_VERSION = "3.x-sidecar"

        fun defaultClient(): HttpClient =
            HttpClient
                .newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build()
    }

    private suspend fun postJson(
        uri: URI,
        payload: JsonObject,
    ): JsonObject {
        val request =
            HttpRequest
                .newBuilder(uri)
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.encodeToString(JsonObject.serializer(), payload)))
                .build()
        val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        if (response.statusCode() !in 200..299) {
            error("PP-StructureV3 sidecar returned HTTP ${response.statusCode()}.")
        }
        return Json.parseToJsonElement(response.body()).jsonObject
    }
}

class PaddleOcrVlEngine(
    private val endpoint: URI = URI.create("http://127.0.0.1:8765/analyze-vl"),
    private val client: HttpClient = PpStructureV3Engine.defaultClient(),
    private val requestTimeout: Duration = Duration.ofMinutes(5),
    private val engineVersion: String = PpStructureV3Engine.DEFAULT_ENGINE_VERSION,
) : DocumentAnalysisEngine {
    private val delegate =
        RemoteLayoutEngine(
            endpoint = endpoint,
            client = client,
            requestTimeout = requestTimeout,
            descriptor =
                DocumentAnalysisEngineDescriptor(
                    engineId = ENGINE_ID,
                    displayName = "复杂版面 · PaddleOCR-VL",
                    engineVersion = engineVersion,
                    modelVersion = "PaddleOCR-VL",
                    languages = setOf("chi_sim", "en"),
                    supportsLayout = true,
                    supportsVerticalText = true,
                    supportsTables = true,
                    supportsFormulas = true,
                    supportsHandwriting = true,
                    backends = setOf(AnalysisComputeBackend.CPU, AnalysisComputeBackend.GPU),
                    privacy = AnalysisPrivacy.LOOPBACK,
                    license = "PaddleOCR license",
                    downloadBytes = 4L * 1024 * 1024 * 1024,
                    estimatedMemoryBytes = 3L * 1024 * 1024 * 1024,
                ),
        )

    override val descriptor: DocumentAnalysisEngineDescriptor
        get() = delegate.descriptor

    override suspend fun analyzePage(request: DocumentAnalysisRequest): PdfPageLayout = delegate.analyzePage(request)

    companion object {
        const val ENGINE_ID = "paddleocr-vl"
    }
}

private class RemoteLayoutEngine(
    private val endpoint: URI,
    private val client: HttpClient,
    private val requestTimeout: Duration,
    override val descriptor: DocumentAnalysisEngineDescriptor,
) : DocumentAnalysisEngine {
    init {
        requireLoopbackEndpoint(endpoint)
    }

    override suspend fun analyzePage(request: DocumentAnalysisRequest): PdfPageLayout {
        require(request.imagePng?.isNotEmpty() == true) { "The remote layout engine requires a page image." }
        val payload =
            buildJsonObject {
                put("page_index", request.pageIndex)
                put("width", request.width)
                put("height", request.height)
                put("dpi", request.dpi)
                put("language", request.language)
                put("image_base64", Base64.getEncoder().encodeToString(request.imagePng))
            }
        val httpRequest =
            HttpRequest
                .newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.encodeToString(JsonObject.serializer(), payload)))
                .build()
        val response = client.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString()).await()
        if (response.statusCode() !in 200..299) {
            error("${descriptor.displayName} sidecar returned HTTP ${response.statusCode()}.")
        }
        return PpStructureResponseMapper.toLayout(
            Json.parseToJsonElement(response.body()).jsonObject,
            request,
            descriptor,
        )
    }
}

private fun requireLoopbackEndpoint(endpoint: URI) {
    require(endpoint.scheme == "http" && endpoint.host == "127.0.0.1") {
        "PaddleOCR engines must use an HTTP loopback endpoint."
    }
}

private object PpStructureResponseMapper {
    fun toLayout(
        response: JsonObject,
        request: DocumentAnalysisRequest,
        descriptor: DocumentAnalysisEngineDescriptor,
    ): PdfPageLayout {
        val blocks = responseBlocks(response, request, descriptor)
        return PdfPageLayout(
            pageIndex = request.pageIndex,
            width = request.width,
            height = request.height,
            blocks = blocks,
            engine =
                AnalysisProvenance(
                    engineId = descriptor.engineId,
                    engineVersion = descriptor.engineVersion,
                    modelVersion = descriptor.modelVersion,
                    language = request.language,
                    dpi = request.dpi,
                ),
            classification = PdfPageClassification.SCANNED,
            classificationEvidence =
                PdfPageClassificationEvidence(
                    nativeCharacterCount = 0,
                    nativeBlockCount = blocks.size,
                    signals = listOf("版面识别引擎返回 OCR 块"),
                ),
        )
    }

    private fun responseBlocks(
        response: JsonObject,
        request: DocumentAnalysisRequest,
        descriptor: DocumentAnalysisEngineDescriptor,
    ): List<PdfLayoutBlock> {
        val list =
            (response["parsing_res_list"] as? JsonArray)
                ?: (response["blocks"] as? JsonArray)
                ?: (response["result"] as? JsonObject)?.get("parsing_res_list") as? JsonArray
                ?: JsonArray(emptyList())
        return list
            .mapIndexedNotNull { index, element -> parseBlock(element, index, request, descriptor) }
            .sortedWith(compareBy<PdfLayoutBlock>({ it.order }, { it.bounds.top }, { it.bounds.left }))
    }

    private fun parseBlock(
        element: JsonElement,
        index: Int,
        request: DocumentAnalysisRequest,
        descriptor: DocumentAnalysisEngineDescriptor,
    ): PdfLayoutBlock? {
        val item = element as? JsonObject ?: return null
        val label = item.blockLabel()
        return parseBounds(item["block_bbox"] ?: item["bbox"], request.width, request.height)?.let { bounds ->
            PdfLayoutBlock(
                id = "${descriptor.engineId}-${request.pageIndex}-$index",
                order = item.blockOrder(index).coerceAtLeast(0),
                type = mapType(label),
                text = item.blockText().trim(),
                bounds = bounds,
                detectionConfidence = item.number("block_detection_score")?.toFloat()?.coerceIn(0f, 1f),
                recognitionConfidence = item.blockRecognition()?.coerceIn(0f, 1f),
                style = PdfTextStyle(vertical = label.contains("vertical", ignoreCase = true)),
            )
        }
    }

    private fun JsonObject.blockLabel(): String =
        string("block_label")
            ?: string("label")
            ?: string("type")
            ?: "unknown"

    private fun JsonObject.blockText(): String =
        string("block_content")
            ?: string("text")
            ?: this["block_rec_res"]
                ?.jsonObject
                ?.string("text")
                .orEmpty()

    private fun JsonObject.blockOrder(default: Int): Int =
        number("block_order")?.toInt()
            ?: number("order")?.toInt()
            ?: default

    private fun JsonObject.blockRecognition(): Float? =
        number("block_prob")?.toFloat()
            ?: this["block_rec_res"]?.jsonObject?.number("score")?.toFloat()
            ?: number("block_recognition_score")?.toFloat()
            ?: number("block_rec_score")?.toFloat()
            ?: this["confidence"]?.jsonPrimitive?.floatOrNull

    private fun parseBounds(
        element: JsonElement?,
        width: Int,
        height: Int,
    ): PdfNormalizedRect? {
        val values =
            (element as? JsonArray)
                ?.flatMap { value ->
                    if (value is JsonArray) {
                        value.mapNotNull { item -> item.floatOrNull() }
                    } else {
                        listOfNotNull(value.floatOrNull())
                    }
                }.orEmpty()
        return values
            .takeIf {
                width > 0 && height > 0 && it.size >= 4 && it.all(Float::isFinite)
            }?.let {
                val bounds =
                    if (it.size >= 8) {
                        val xValues = it.filterIndexed { index, _ -> index % 2 == 0 }
                        val yValues = it.filterIndexed { index, _ -> index % 2 == 1 }
                        listOf(xValues.min(), yValues.min(), xValues.max(), yValues.max())
                    } else {
                        it.take(4)
                    }
                var left = bounds[0]
                var top = bounds[1]
                var right = bounds[2]
                var bottom = bounds[3]
                if (right <= left) right = left + right
                if (bottom <= top) bottom = top + bottom
                if (right <= left || bottom <= top) {
                    null
                } else {
                    left = (left / width).coerceIn(0f, 1f)
                    top = (top / height).coerceIn(0f, 1f)
                    right = (right / width).coerceIn(0f, 1f)
                    bottom = (bottom / height).coerceIn(0f, 1f)
                    if (right <= left || bottom <= top) {
                        null
                    } else {
                        runCatching { PdfNormalizedRect(left, top, right, bottom) }.getOrNull()
                    }
                }
            }
    }

    private fun mapType(label: String): PdfLayoutBlockType =
        when (label.lowercase().replace('_', ' ')) {
            "doc title", "document title", "title" -> PdfLayoutBlockType.DOCUMENT_TITLE
            "paragraph title", "section title", "text title", "heading" -> PdfLayoutBlockType.SECTION_TITLE
            "table" -> PdfLayoutBlockType.TABLE
            "figure", "image" -> PdfLayoutBlockType.FIGURE
            "header" -> PdfLayoutBlockType.HEADER
            "footer" -> PdfLayoutBlockType.FOOTER
            "toc", "table of contents", "contents" -> PdfLayoutBlockType.TOC
            "page number", "page_number" -> PdfLayoutBlockType.PAGE_NUMBER
            "list" -> PdfLayoutBlockType.LIST
            "text", "paragraph" -> PdfLayoutBlockType.TEXT
            else -> PdfLayoutBlockType.UNKNOWN
        }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.number(key: String): Double? = this[key]?.let { value -> value.doubleOrNull() }
}

private val JsonPrimitive.contentOrNull: String?
    get() = if (isString) content else content.takeIf { it.isNotBlank() }

private val JsonPrimitive.floatOrNull: Float?
    get() = content.toFloatOrNull()

private val JsonPrimitive.doubleOrNull: Double?
    get() = content.toDoubleOrNull()

private fun JsonElement.floatOrNull(): Float? = runCatching { jsonPrimitive.floatOrNull }.getOrNull()

private fun JsonElement.doubleOrNull(): Double? = runCatching { jsonPrimitive.doubleOrNull }.getOrNull()
