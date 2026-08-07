package dev.xuexue.ui

import dev.xuexue.platform.PdfPageDimensions
import kotlin.test.Test
import kotlin.test.assertEquals

class PdfRenderDpiTest {
    @Test
    fun pageDisplayedAtScreenPixelsUsesTheMatchingDpi() {
        assertEquals(
            96f,
            pdfTargetRenderDpi(
                pageDimensions = A4,
                displayedWidthPixels = A4.widthPoints * 96f / POINTS_PER_INCH,
                displayedHeightPixels = A4.heightPoints * 96f / POINTS_PER_INCH,
            ),
            absoluteTolerance = 0.01f,
        )
    }

    @Test
    fun shrinkingAndEnlargingTheCanvasChangesTheTargetDpi() {
        val halfSizeDpi =
            pdfTargetRenderDpi(
                pageDimensions = A4,
                displayedWidthPixels = A4.widthPoints * 48f / POINTS_PER_INCH,
                displayedHeightPixels = A4.heightPoints * 48f / POINTS_PER_INCH,
            )
        val doubleSizeDpi =
            pdfTargetRenderDpi(
                pageDimensions = A4,
                displayedWidthPixels = A4.widthPoints * 192f / POINTS_PER_INCH,
                displayedHeightPixels = A4.heightPoints * 192f / POINTS_PER_INCH,
            )

        assertEquals(48f, halfSizeDpi, absoluteTolerance = 0.01f)
        assertEquals(192f, doubleSizeDpi, absoluteTolerance = 0.01f)
    }

    @Test
    fun rotatedPageUsesTheUnrotatedPageAxes() {
        assertEquals(
            96f,
            pdfTargetRenderDpi(
                pageDimensions = A4,
                displayedWidthPixels = A4.heightPoints * 96f / POINTS_PER_INCH,
                displayedHeightPixels = A4.widthPoints * 96f / POINTS_PER_INCH,
                rotationDegrees = 90f,
            ),
            absoluteTolerance = 0.01f,
        )
    }

    @Test
    fun largerAxisRequirementWinsWhenTheLayoutIsNotProportional() {
        assertEquals(
            144f,
            pdfTargetRenderDpi(
                pageDimensions = PdfPageDimensions(600f, 600f),
                displayedWidthPixels = 1_200f,
                displayedHeightPixels = 800f,
            ),
            absoluteTolerance = 0.01f,
        )
    }

    @Test
    fun invalidLayoutFallsBackWithoutThrowing() {
        assertEquals(
            null,
            pdfTargetRenderDpiOrNull(
                pageDimensions = PdfPageDimensions(0f, 842f),
                displayedWidthPixels = 800f,
                displayedHeightPixels = 1_100f,
            ),
        )
    }

    @Test
    fun previewUsesItsBitmapAspectRatioUntilPageDimensionsAreReady() {
        assertEquals(
            1.5f,
            pdfDisplayedPageAspectRatio(
                pageDimensions = A4,
                previewWidthPixels = 1_200,
                previewHeightPixels = 800,
                renderViewport = false,
                rotationDegrees = 0f,
            ),
            absoluteTolerance = 0.01f,
        )
    }

    @Test
    fun finalPageAspectRatioUsesMuPdfDimensionsInsteadOfThePreviewRatio() {
        assertEquals(
            A4.widthPoints / A4.heightPoints,
            pdfDisplayedPageAspectRatio(
                pageDimensions = A4,
                previewWidthPixels = 1_200,
                previewHeightPixels = 800,
                renderViewport = true,
                rotationDegrees = 0f,
            ),
            absoluteTolerance = 0.01f,
        )
    }

    private companion object {
        val A4 = PdfPageDimensions(widthPoints = 595f, heightPoints = 842f)
        const val POINTS_PER_INCH = 72f
    }
}
