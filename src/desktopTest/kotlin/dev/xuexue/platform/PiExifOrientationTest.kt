package dev.xuexue.platform

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class PiExifOrientationTest {
    @Test
    fun readsOrientationFromLittleEndianTiff() {
        val jpeg = jpegWithExifOrientation(6)
        assertEquals(6, readExifOrientation(jpeg))
    }

    @Test
    fun readsOrientationFromBigEndianTiff() {
        val jpeg = jpegWithExifOrientation(3, bigEndian = true)
        assertEquals(3, readExifOrientation(jpeg))
    }

    @Test
    fun defaultsToOrientationOneWithoutExif() {
        val plain = plainJpeg()
        assertEquals(1, readExifOrientation(plain))
    }

    @Test
    fun ignoresOrientationOutsideValidRange() {
        val jpeg = jpegWithExifOrientation(9)
        assertEquals(1, readExifOrientation(jpeg))
    }

    @Test
    fun rotatesNinetyDegreesForOrientationSix() {
        val source = BufferedImage(4, 2, BufferedImage.TYPE_INT_ARGB)
        // Mark each column with a distinct color so rotation is observable.
        val colors = intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt(), 0xffffff00.toInt())
        for (x in 0 until 4) {
            for (y in 0 until 2) source.setRGB(x, y, colors[x])
        }
        val rotated = applyExifOrientation(source, jpegWithExifOrientation(6))
        assertEquals(2, rotated.width)
        assertEquals(4, rotated.height)
        // After 90° CW: the left column of the result is the bottom row of the source.
        assertEquals(colors[3], rotated.getRGB(0, 0))
        assertEquals(colors[2], rotated.getRGB(0, 1))
        assertEquals(colors[1], rotated.getRGB(0, 2))
        assertEquals(colors[0], rotated.getRGB(0, 3))
    }

    @Test
    fun flipsHorizontallyForOrientationTwo() {
        val source = BufferedImage(3, 1, BufferedImage.TYPE_INT_ARGB)
        source.setRGB(0, 0, 0xffff0000.toInt())
        source.setRGB(1, 0, 0xff00ff00.toInt())
        source.setRGB(2, 0, 0xff0000ff.toInt())
        val flipped = applyExifOrientation(source, jpegWithExifOrientation(2))
        assertEquals(0xff0000ff.toInt(), flipped.getRGB(0, 0))
        assertEquals(0xff00ff00.toInt(), flipped.getRGB(1, 0))
        assertEquals(0xffff0000.toInt(), flipped.getRGB(2, 0))
    }

    @Test
    fun returnsSameImageForOrientationOne() {
        val source = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)
        val result = applyExifOrientation(source, jpegWithExifOrientation(1))
        assertSame(source, result)
    }

    private fun jpegWithExifOrientation(
        orientation: Int,
        bigEndian: Boolean = false,
    ): ByteArray {
        val tiff = buildTiff(orientation, bigEndian)
        val exif = ByteArray(6) { 0 }
        exif[0] = 'E'.code.toByte()
        exif[1] = 'x'.code.toByte()
        exif[2] = 'i'.code.toByte()
        exif[3] = 'f'.code.toByte()
        // exif[4..5] = 0x00 0x00
        val app1Payload = exif + tiff
        val segmentLength = app1Payload.size + 2
        val app1 = ByteArray(segmentLength + 2)
        app1[0] = 0xff.toByte()
        app1[1] = 0xe1.toByte()
        app1[2] = ((segmentLength shr 8) and 0xff).toByte()
        app1[3] = (segmentLength and 0xff).toByte()
        app1Payload.copyInto(app1, 4)
        val base = plainJpeg()
        // Insert APP1 right after SOI (FFD8).
        return byteArrayOf(0xff.toByte(), 0xd8.toByte()) + app1 + base.copyOfRange(2, base.size)
    }

    private fun buildTiff(
        orientation: Int,
        bigEndian: Boolean,
    ): ByteArray {
        val order = if (bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        val buffer = ByteBuffer.allocate(2 + 2 + 4 + 2 + 12 + 4).order(order)
        buffer.put(if (bigEndian) 0x4d.toByte() else 0x49.toByte())
        buffer.put(if (bigEndian) 0x4d.toByte() else 0x49.toByte())
        buffer.putShort(42)
        buffer.putInt(8) // IFD offset
        buffer.putShort(1) // one entry
        buffer.putShort(0x0112) // orientation tag
        buffer.putShort(3) // SHORT type
        buffer.putInt(1) // count
        buffer.putShort(orientation.toShort())
        buffer.putShort(0) // padding
        buffer.putInt(0) // next IFD
        return buffer.array()
    }

    private fun plainJpeg(): ByteArray {
        val output = ByteArrayOutputStream()
        check(ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "jpg", output))
        return output.toByteArray()
    }
}
