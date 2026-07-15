package dev.xuexue.platform

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

internal const val PROGRESSIVE_PDF_BLOCK_BYTES = 64 * 1024

internal interface ProgressivePdfSource : AutoCloseable {
    val length: Long
    val revision: Long
    val availableBytes: Long

    suspend fun read(range: PdfByteRange): ByteArray

    override fun close() = Unit
}

internal class ProgressivePdfRegistry : AutoCloseable {
    private val sources = ConcurrentHashMap<Path, ProgressivePdfSource>()

    fun register(
        path: Path,
        source: ProgressivePdfSource,
    ) {
        sources.put(path.cacheKey(), source)?.takeIf { it !== source }?.close()
    }

    fun sourceFor(path: Path): ProgressivePdfSource? = sources[path.cacheKey()]

    fun unregister(
        path: Path,
        close: Boolean = true,
    ): ProgressivePdfSource? = sources.remove(path.cacheKey())?.also { if (close) it.close() }

    override fun close() {
        sources.values.forEach(ProgressivePdfSource::close)
        sources.clear()
    }

    private fun Path.cacheKey(): Path = toAbsolutePath().normalize()
}
