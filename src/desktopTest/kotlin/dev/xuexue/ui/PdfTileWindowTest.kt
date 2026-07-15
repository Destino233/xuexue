package dev.xuexue.ui

import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PdfTileWindowTest {
    @Test
    fun visibleWindowSelectsOnlyTilesIntersectingTheRootViewport() {
        assertEquals(
            PdfTileWindow(firstColumn = 0, lastColumn = 3, firstRow = 0, lastRow = 3),
            pdfVisibleTileWindow(
                pageBounds = Rect(left = -100f, top = 50f, right = 900f, bottom = 2_050f),
                viewportBounds = Rect(left = 0f, top = 0f, right = 800f, bottom = 1_000f),
                columns = 4,
                rows = 8,
                marginTiles = 0,
            ),
        )
    }

    @Test
    fun completelyOffscreenPageSchedulesNoTiles() {
        assertNull(
            pdfVisibleTileWindow(
                pageBounds = Rect(left = 0f, top = 1_100f, right = 800f, bottom = 1_900f),
                viewportBounds = Rect(left = 0f, top = 0f, right = 800f, bottom = 1_000f),
                columns = 4,
                rows = 4,
            ),
        )
    }

    @Test
    fun nightModeAddsAContentFilterWithoutChangingNormalPages() {
        assertNull(pdfPageColorFilter(PdfColorMode.NORMAL.name))
        assertNotNull(pdfPageColorFilter(PdfColorMode.NIGHT.name))
    }
}
