package dev.xuexue.platform

internal object PdfMemoryBudget {
    const val TOTAL_BYTES = 240L * 1024L * 1024L
    const val MUPDF_STORE_BYTES = 48L * 1024L * 1024L

    // Viewport tiles are retained only by the texture cache; the raster cache
    // serves thumbnails and previews, which need far less than the old budget.
    const val RASTER_CACHE_BYTES = 24L * 1024L * 1024L
    const val TEXTURE_CACHE_BYTES = 96L * 1024L * 1024L
    const val PIPELINE_HEADROOM_BYTES = 32L * 1024L * 1024L

    init {
        check(
            MUPDF_STORE_BYTES + RASTER_CACHE_BYTES + TEXTURE_CACHE_BYTES + PIPELINE_HEADROOM_BYTES <= TOTAL_BYTES,
        ) {
            "PDF cache partitions exceed the total memory budget."
        }
    }
}
