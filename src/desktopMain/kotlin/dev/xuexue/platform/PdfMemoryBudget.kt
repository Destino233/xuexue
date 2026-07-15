package dev.xuexue.platform

internal object PdfMemoryBudget {
    const val TOTAL_BYTES = 224L * 1024L * 1024L
    const val MUPDF_STORE_BYTES = 96L * 1024L * 1024L
    const val RASTER_CACHE_BYTES = 64L * 1024L * 1024L
    const val TEXTURE_CACHE_BYTES = 48L * 1024L * 1024L
    const val PIPELINE_HEADROOM_BYTES = 16L * 1024L * 1024L

    init {
        check(
            MUPDF_STORE_BYTES + RASTER_CACHE_BYTES + TEXTURE_CACHE_BYTES + PIPELINE_HEADROOM_BYTES <= TOTAL_BYTES,
        ) {
            "PDF cache partitions exceed the total memory budget."
        }
    }
}
