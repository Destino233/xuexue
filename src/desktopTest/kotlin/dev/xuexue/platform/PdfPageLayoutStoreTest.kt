package dev.xuexue.platform

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PdfPageLayoutStoreTest {
    @Test
    fun sourceIsRepeatableAndReadsPagesInPhysicalOrder() {
        val root = Files.createTempDirectory("xuexue-layout-store-test")
        try {
            val store = PdfPageLayoutStore(root)
            try {
                store.write(layout(2))
                store.write(layout(0))
                store.write(layout(1))

                val first = mutableListOf<Int>()
                val second = mutableListOf<Int>()
                store.source().forEachPage { page -> first += page.pageIndex }
                store.source().forEachPage { page -> second += page.pageIndex }

                assertEquals(listOf(0, 1, 2), first)
                assertEquals(first, second)
            } finally {
                store.close()
            }
            assertFalse(Files.exists(root.resolve("00000000.json")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun detectorCanConsumeARepeatablePageSourceWithoutChangingItsResult() {
        val layouts = listOf(layout(0), layout(1))
        val detector = ChapterStructureDetector()
        val expected = detector.detect(layouts, "book.pdf", "0".repeat(64), null)
        val store = PdfPageLayoutStore()
        try {
            layouts.forEach(store::write)
            val actual = detector.detect(store.source(), "book.pdf", "0".repeat(64), null)
            assertEquals(expected.outline, actual.outline)
            assertEquals(expected.candidates, actual.candidates)
        } finally {
            store.close()
        }
    }

    private fun layout(pageIndex: Int): PdfPageLayout =
        PdfPageLayout(
            pageIndex = pageIndex,
            width = 100,
            height = 100,
            blocks =
                listOf(
                    PdfLayoutBlock(
                        id = "block-$pageIndex",
                        order = 0,
                        type = PdfLayoutBlockType.SECTION_TITLE,
                        text = "第 ${pageIndex + 1} 章",
                        bounds = PdfNormalizedRect(0.2f, 0.2f, 0.8f, 0.3f),
                        recognitionConfidence = 0.95f,
                    ),
                ),
            engine = AnalysisProvenance("native-pdf-layout", "test"),
        )
}
