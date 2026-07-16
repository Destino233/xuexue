package dev.xuexue.ui

import dev.xuexue.platform.PdfNormalizedRect
import kotlin.test.Test
import kotlin.test.assertEquals

class PdfPageAnnotationOverlayTest {
    private val region = PdfNormalizedRect(left = 0.1f, top = 0.2f, right = 0.4f, bottom = 0.5f)

    @Test
    fun annotationAnchorsFollowClockwisePageRotation() {
        assertEquals(PdfNormalizedRect(0.5f, 0.1f, 0.8f, 0.4f), pdfDisplayRegion(region, 90f))
        assertEquals(PdfNormalizedRect(0.6f, 0.5f, 0.9f, 0.8f), pdfDisplayRegion(region, 180f))
        assertEquals(PdfNormalizedRect(0.2f, 0.6f, 0.5f, 0.9f), pdfDisplayRegion(region, 270f))
    }

    @Test
    fun annotationAnchorsNormalizeEquivalentRotations() {
        assertEquals(pdfDisplayRegion(region, 90f), pdfDisplayRegion(region, 450f))
        assertEquals(pdfDisplayRegion(region, 270f), pdfDisplayRegion(region, -90f))
    }

    @Test
    fun selectedTextRegionsRemainTheAnnotationGeometry() {
        val converted = listOf(region).toPdfHighlightRegions().single()

        assertEquals(region.left, converted.left)
        assertEquals(region.top, converted.top)
        assertEquals(region.right, converted.right)
        assertEquals(region.bottom, converted.bottom)
    }
}
