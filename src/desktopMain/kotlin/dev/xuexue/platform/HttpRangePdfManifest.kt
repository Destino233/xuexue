package dev.xuexue.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.BitSet
import java.util.Properties

internal data class HttpRangePdfManifest(
    val uri: String,
    val length: Long,
    val validator: String?,
    val blockSize: Int,
    val revision: Long,
    val cachedBlocks: BooleanArray,
)

internal data class RecoveredRangeCache(
    val cachePath: Path,
    val manifest: HttpRangePdfManifest,
)

internal class HttpRangePdfManifestStore(
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val writeMutex = Mutex()

    suspend fun write(
        cachePath: Path,
        manifest: HttpRangePdfManifest,
    ): Unit =
        writeMutex.withLock {
            withContext(ioDispatcher) {
                val properties = manifest.toProperties()
                val target = manifestPath(cachePath)
                val temporary = target.resolveSibling("${target.fileName}.tmp")
                Files.newOutputStream(temporary).use { output -> properties.store(output, null) }
                try {
                    Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }

    suspend fun read(cachePath: Path): HttpRangePdfManifest? =
        withContext(ioDispatcher) {
            val path = manifestPath(cachePath)
            if (!Files.isRegularFile(path) || !Files.isRegularFile(cachePath)) return@withContext null
            runCatching {
                val properties = Properties().apply { Files.newInputStream(path).use(::load) }
                properties.toManifest()
            }.getOrNull()
        }

    suspend fun discover(downloads: Path): List<RecoveredRangeCache> {
        val manifestPaths =
            withContext(ioDispatcher) {
                if (!Files.isDirectory(downloads)) return@withContext emptyList()
                Files.list(downloads).use { paths ->
                    paths
                        .filter { path -> path.fileName.toString().endsWith(MANIFEST_SUFFIX) }
                        .toList()
                }
            }
        return manifestPaths.mapNotNull { path ->
            val cachePath = path.resolveSibling(path.fileName.toString().removeSuffix(MANIFEST_FILE_SUFFIX))
            read(cachePath)?.let { manifest -> RecoveredRangeCache(cachePath, manifest) }
        }
    }

    fun delete(cachePath: Path) {
        runCatching { Files.deleteIfExists(manifestPath(cachePath)) }
    }

    private fun manifestPath(cachePath: Path): Path {
        val fileName = "${cachePath.fileName}$MANIFEST_FILE_SUFFIX"
        return cachePath.resolveSibling(fileName)
    }

    private companion object {
        const val MANIFEST_FILE_SUFFIX = ".meta"
        const val MANIFEST_SUFFIX = ".pdf.part$MANIFEST_FILE_SUFFIX"
    }
}

private fun HttpRangePdfManifest.toProperties(): Properties =
    Properties().apply {
        setProperty("version", MANIFEST_VERSION)
        setProperty("uri", uri)
        setProperty("length", length.toString())
        setProperty("validator", validator.orEmpty())
        setProperty("blockSize", blockSize.toString())
        setProperty("revision", revision.toString())
        val bits = BitSet(cachedBlocks.size)
        cachedBlocks.forEachIndexed { index, available -> if (available) bits.set(index) }
        setProperty("cachedBlocks", Base64.getEncoder().encodeToString(bits.toByteArray()))
    }

private fun Properties.toManifest(): HttpRangePdfManifest {
    require(getProperty("version") == MANIFEST_VERSION) { "Unsupported remote PDF cache manifest." }
    val length = getProperty("length").toLong()
    val blockSize = getProperty("blockSize").toInt()
    require(length > 0L && blockSize > 0) { "Invalid remote PDF cache dimensions." }
    val blockCount = ((length + blockSize - 1L) / blockSize).toInt()
    val cached = BooleanArray(blockCount)
    val bits = BitSet.valueOf(Base64.getDecoder().decode(getProperty("cachedBlocks")))
    for (index in 0 until blockCount) cached[index] = bits[index]
    return HttpRangePdfManifest(
        uri = getProperty("uri"),
        length = length,
        validator = getProperty("validator").takeUnless(String::isEmpty),
        blockSize = blockSize,
        revision = getProperty("revision").toLong(),
        cachedBlocks = cached,
    )
}

private const val MANIFEST_VERSION = "1"
