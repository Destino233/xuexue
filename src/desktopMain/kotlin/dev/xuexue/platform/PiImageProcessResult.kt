package dev.xuexue.platform

import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

private const val PI_IMAGE_MAX_WIDTH = 2_000
private const val PI_IMAGE_MAX_HEIGHT = 2_000
private const val PI_IMAGE_MAX_BASE64_BYTES = (4.5 * 1024 * 1024).toInt()
private val PI_IMAGE_JPEG_QUALITIES = listOf(80, 85, 70, 55, 40)

internal sealed interface PiImageProcessResult {
    data class Success(
        val data: String,
        val mimeType: String,
        val hints: List<String>,
    ) : PiImageProcessResult

    data class Failure(
        val message: String,
    ) : PiImageProcessResult
}

/** Implements Pi's image normalization, size guard and dimension hint semantics on the JVM. */
@Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements", "ReturnCount")
internal fun processPiImage(
    bytes: ByteArray,
    mimeType: String,
    autoResize: Boolean = true,
): PiImageProcessResult {
    val normalizedMime =
        normalizeImageMime(mimeType)
            ?: return PiImageProcessResult.Failure(
                "[Image omitted: could not be converted to a supported inline image format.]",
            )
    val sourceMime = mimeType.substringBefore(';').trim().lowercase()
    val convertedFrom = sourceMime.takeIf { normalizedMime != it && it != "image/jpg" }
    val rawData = Base64.getEncoder().encodeToString(bytes)
    val image =
        runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()
            ?: return PiImageProcessResult.Failure(
                "[Image omitted: could not be converted to a supported inline image format.]",
            )

    if (!autoResize) {
        if (convertedFrom == null) return PiImageProcessResult.Success(rawData, normalizedMime, emptyList())
        val png = encodePng(image) ?: return conversionFailure()
        return PiImageProcessResult.Success(
            data = Base64.getEncoder().encodeToString(png),
            mimeType = "image/png",
            hints = listOf("[Image converted from $convertedFrom to image/png.]"),
        )
    }

    val withinImageLimits =
        convertedFrom == null &&
            image.width <= PI_IMAGE_MAX_WIDTH &&
            image.height <= PI_IMAGE_MAX_HEIGHT &&
            rawData.length < PI_IMAGE_MAX_BASE64_BYTES
    if (withinImageLimits) {
        return PiImageProcessResult.Success(rawData, normalizedMime, emptyList())
    }

    var dimensions = fitDimensions(image.width, image.height)
    while (true) {
        val resized = resizeImage(image, dimensions.first, dimensions.second)
        val candidates =
            buildList {
                encodePng(resized)?.let { add(EncodedImage(it, "image/png")) }
                PI_IMAGE_JPEG_QUALITIES.forEach { quality ->
                    encodeJpeg(resized, quality)?.let { add(EncodedImage(it, "image/jpeg")) }
                }
            }
        val candidate =
            candidates.firstOrNull {
                Base64.getEncoder().encodeToString(it.bytes).length < PI_IMAGE_MAX_BASE64_BYTES
            }
        if (candidate != null) {
            val hints =
                buildList {
                    if (convertedFrom != null && convertedFrom != candidate.mimeType) {
                        add("[Image converted from $convertedFrom to ${candidate.mimeType}.]")
                    }
                    if (dimensions.first != image.width || dimensions.second != image.height) {
                        val scale = image.width.toDouble() / dimensions.first.toDouble()
                        add(
                            "[Image: original ${image.width}x${image.height}, displayed at " +
                                "${dimensions.first}x${dimensions.second}. Multiply coordinates by " +
                                "${"%.2f".format(java.util.Locale.ROOT, scale)} to map to original image.]",
                        )
                    }
                }
            return PiImageProcessResult.Success(
                data = Base64.getEncoder().encodeToString(candidate.bytes),
                mimeType = candidate.mimeType,
                hints = hints,
            )
        }
        if (dimensions.first == 1 && dimensions.second == 1) break
        val next =
            dimensions.first.coerceAtLeast(1).let { width ->
                val height = dimensions.second.coerceAtLeast(1)
                max(1, floor(width * 0.75).toInt()) to max(1, floor(height * 0.75).toInt())
            }
        if (next == dimensions) break
        dimensions = next
    }
    return PiImageProcessResult.Failure(
        "[Image omitted: could not be resized below the inline image size limit.]",
    )
}

private data class EncodedImage(
    val bytes: ByteArray,
    val mimeType: String,
)

private fun normalizeImageMime(mimeType: String): String? =
    when (mimeType.substringBefore(';').trim().lowercase()) {
        "image/png" -> "image/png"
        "image/jpeg", "image/jpg" -> "image/jpeg"
        "image/gif" -> "image/gif"
        "image/webp" -> "image/webp"
        "image/bmp" -> "image/png"
        else -> null
    }

private fun conversionFailure(): PiImageProcessResult.Failure =
    PiImageProcessResult.Failure(
        "[Image omitted: could not be converted to a supported inline image format.]",
    )

private fun fitDimensions(
    width: Int,
    height: Int,
): Pair<Int, Int> {
    var targetWidth = width
    var targetHeight = height
    if (targetWidth > PI_IMAGE_MAX_WIDTH) {
        targetHeight = (targetHeight.toDouble() * PI_IMAGE_MAX_WIDTH / targetWidth).roundToInt().coerceAtLeast(1)
        targetWidth = PI_IMAGE_MAX_WIDTH
    }
    if (targetHeight > PI_IMAGE_MAX_HEIGHT) {
        targetWidth = (targetWidth.toDouble() * PI_IMAGE_MAX_HEIGHT / targetHeight).roundToInt().coerceAtLeast(1)
        targetHeight = PI_IMAGE_MAX_HEIGHT
    }
    return targetWidth to targetHeight
}

private fun resizeImage(
    source: BufferedImage,
    width: Int,
    height: Int,
): BufferedImage {
    val target = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val graphics = target.createGraphics()
    try {
        configureGraphics(graphics)
        graphics.drawImage(source, 0, 0, width, height, null)
    } finally {
        graphics.dispose()
    }
    return target
}

private fun configureGraphics(graphics: Graphics2D) {
    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
    graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
}

private fun encodePng(image: BufferedImage): ByteArray? = encode(image, "png")

private fun encodeJpeg(
    source: BufferedImage,
    quality: Int,
): ByteArray? {
    val image = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB)
    val graphics = image.createGraphics()
    try {
        graphics.composite = AlphaComposite.Src
        graphics.color = Color.WHITE
        graphics.fillRect(0, 0, image.width, image.height)
        graphics.drawImage(source, 0, 0, null)
    } finally {
        graphics.dispose()
    }
    val writer = ImageIO.getImageWritersByFormatName("jpeg").asSequence().firstOrNull() ?: return null
    val output = ByteArrayOutputStream()
    return try {
        MemoryCacheImageOutputStream(output).use { imageOutput ->
            writer.output = imageOutput
            val params =
                writer.defaultWriteParam.apply {
                    if (canWriteCompressed()) {
                        compressionMode = ImageWriteParam.MODE_EXPLICIT
                        compressionQuality = quality / 100f
                    }
                }
            writer.write(null, IIOImage(image, null, null), params)
            imageOutput.flush()
        }
        output.toByteArray()
    } catch (_: Exception) {
        null
    } finally {
        writer.dispose()
    }
}

private fun encode(
    image: BufferedImage,
    format: String,
): ByteArray? {
    val output = ByteArrayOutputStream()
    return runCatching {
        check(ImageIO.write(image, format, output)) { "No ImageIO writer for $format" }
        output.toByteArray()
    }.getOrNull()
}
