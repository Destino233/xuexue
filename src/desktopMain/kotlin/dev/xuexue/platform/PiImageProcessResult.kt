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
    val decoded =
        runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()
            ?: return PiImageProcessResult.Failure(
                "[Image omitted: could not be converted to a supported inline image format.]",
            )
    // Pi applies the EXIF orientation right after decode so "original" dimensions
    // reflect the rotated image before any size checks.
    val image = applyExifOrientation(decoded, bytes)

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
// ---------------------------------------------------------------------------
// EXIF orientation (port of Pi utils/exif-orientation.ts)
// ---------------------------------------------------------------------------

private const val EXIF_ORIENTATION_TAG = 0x0112

/** Read the EXIF orientation (1..8) from JPEG or WebP bytes; defaults to 1. */
internal fun readExifOrientation(bytes: ByteArray): Int {
    val tiffOffset =
        when {
            bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() -> {
                findJpegTiffOffset(bytes)
            }

            bytes.size >= 12 &&
                String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> {
                findWebpTiffOffset(bytes)
            }

            else -> -1
        }
    if (tiffOffset < 0) return 1
    return readOrientationFromTiff(bytes, tiffOffset)
}

private fun findJpegTiffOffset(bytes: ByteArray): Int {
    var offset = 2
    while (offset < bytes.size - 1) {
        if (bytes[offset] != 0xff.toByte()) return -1
        val marker = bytes[offset + 1].toInt() and 0xff
        if (marker == 0xff) {
            offset++
            continue
        }
        if (marker == 0xe1) {
            if (offset + 4 >= bytes.size) return -1
            val segmentStart = offset + 4
            if (segmentStart + 6 > bytes.size) return -1
            if (!hasExifHeader(bytes, segmentStart)) return -1
            return segmentStart + 6
        }
        if (offset + 4 > bytes.size) return -1
        val length = ((bytes[offset + 2].toInt() and 0xff) shl 8) or (bytes[offset + 3].toInt() and 0xff)
        offset += 2 + length
    }
    return -1
}

private fun findWebpTiffOffset(bytes: ByteArray): Int {
    var offset = 12
    while (offset + 8 <= bytes.size) {
        val chunkId = String(bytes, offset, 4, Charsets.US_ASCII)
        val chunkSize =
            (bytes[offset + 4].toInt() and 0xff) or
                ((bytes[offset + 5].toInt() and 0xff) shl 8) or
                ((bytes[offset + 6].toInt() and 0xff) shl 16) or
                ((bytes[offset + 7].toInt() and 0xff) shl 24)
        val dataStart = offset + 8
        if (chunkId == "EXIF") {
            if (dataStart + chunkSize > bytes.size) return -1
            // Some WebP files have "Exif\0\0" prefix before the TIFF header
            val tiffStart = if (chunkSize >= 6 && hasExifHeader(bytes, dataStart)) dataStart + 6 else dataStart
            return tiffStart
        }
        // RIFF chunks are padded to even size
        offset = dataStart + chunkSize + (chunkSize % 2)
    }
    return -1
}

private fun hasExifHeader(
    bytes: ByteArray,
    offset: Int,
): Boolean =
    offset + 6 <= bytes.size &&
        bytes[offset] == 0x45.toByte() &&
        bytes[offset + 1] == 0x78.toByte() &&
        bytes[offset + 2] == 0x69.toByte() &&
        bytes[offset + 3] == 0x66.toByte() &&
        bytes[offset + 4] == 0x00.toByte() &&
        bytes[offset + 5] == 0x00.toByte()

private fun readOrientationFromTiff(
    bytes: ByteArray,
    tiffStart: Int,
): Int {
    if (tiffStart + 8 > bytes.size) return 1
    val byteOrder = ((bytes[tiffStart].toInt() and 0xff) shl 8) or (bytes[tiffStart + 1].toInt() and 0xff)
    val littleEndian = byteOrder == 0x4949
    val read16: (Int) -> Int =
        { position ->
            if (littleEndian) {
                (bytes[position].toInt() and 0xff) or ((bytes[position + 1].toInt() and 0xff) shl 8)
            } else {
                ((bytes[position].toInt() and 0xff) shl 8) or (bytes[position + 1].toInt() and 0xff)
            }
        }
    val read32: (Int) -> Int =
        { position ->
            if (littleEndian) {
                (bytes[position].toInt() and 0xff) or
                    ((bytes[position + 1].toInt() and 0xff) shl 8) or
                    ((bytes[position + 2].toInt() and 0xff) shl 16) or
                    ((bytes[position + 3].toInt() and 0xff) shl 24)
            } else {
                ((bytes[position].toInt() and 0xff) shl 24) or
                    ((bytes[position + 1].toInt() and 0xff) shl 16) or
                    ((bytes[position + 2].toInt() and 0xff) shl 8) or
                    (bytes[position + 3].toInt() and 0xff)
            }
        }
    val ifdOffset = read32(tiffStart + 4)
    val ifdStart = tiffStart + ifdOffset
    if (ifdStart + 2 > bytes.size) return 1
    val entryCount = read16(ifdStart)
    for (index in 0 until entryCount) {
        val entryPos = ifdStart + 2 + index * 12
        if (entryPos + 12 > bytes.size) return 1
        if (read16(entryPos) == EXIF_ORIENTATION_TAG) {
            val value = read16(entryPos + 8)
            return if (value in 1..8) value else 1
        }
    }
    return 1
}

/** Apply the EXIF orientation to a decoded image, returning the corrected image. */
internal fun applyExifOrientation(
    image: BufferedImage,
    originalBytes: ByteArray,
): BufferedImage {
    val orientation = readExifOrientation(originalBytes)
    if (orientation == 1) return image
    val width = image.width
    val height = image.height
    return when (orientation) {
        2 -> flipHorizontal(image)
        3 -> rotate180(image)
        4 -> flipVertical(image)
        5 -> flipHorizontal(rotate90Cw(image))
        6 -> rotate90Cw(image)
        7 -> flipHorizontal(rotate90Ccw(image))
        8 -> rotate90Ccw(image)
        else -> image
    }
}

private fun flipHorizontal(image: BufferedImage): BufferedImage {
    val flipped = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
    val graphics = flipped.createGraphics()
    graphics.drawImage(image, image.width, 0, -image.width, image.height, null)
    graphics.dispose()
    return flipped
}

private fun flipVertical(image: BufferedImage): BufferedImage {
    val flipped = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
    val graphics = flipped.createGraphics()
    graphics.drawImage(image, 0, image.height, image.width, -image.height, null)
    graphics.dispose()
    return flipped
}

private fun rotate180(image: BufferedImage): BufferedImage {
    val rotated = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
    val graphics = rotated.createGraphics()
    graphics.rotate(Math.toRadians(180.0), image.width / 2.0, image.height / 2.0)
    graphics.drawImage(image, 0, 0, null)
    graphics.dispose()
    return rotated
}

private fun rotate90Cw(image: BufferedImage): BufferedImage {
    val rotated = BufferedImage(image.height, image.width, BufferedImage.TYPE_INT_ARGB)
    val graphics = rotated.createGraphics()
    graphics.translate(0.0, image.width.toDouble())
    graphics.rotate(Math.toRadians(-90.0))
    graphics.drawImage(image, 0, 0, null)
    graphics.dispose()
    return rotated
}

private fun rotate90Ccw(image: BufferedImage): BufferedImage {
    val rotated = BufferedImage(image.height, image.width, BufferedImage.TYPE_INT_ARGB)
    val graphics = rotated.createGraphics()
    graphics.translate(image.height.toDouble(), 0.0)
    graphics.rotate(Math.toRadians(90.0))
    graphics.drawImage(image, 0, 0, null)
    graphics.dispose()
    return rotated
}
