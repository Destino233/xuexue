package dev.xuexue.platform

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** A repeatable page source that keeps only file names and one decoded page in memory. */
fun interface PdfPageLayoutSource {
    fun forEachPage(action: (PdfPageLayout) -> Unit)
}

class PdfPageLayoutStore(
    private val root: Path = Files.createTempDirectory("xuexue-pdf-layouts-"),
) : AutoCloseable {
    private val json = Json { encodeDefaults = true }

    init {
        Files.createDirectories(root)
    }

    fun write(layout: PdfPageLayout) {
        val target = pagePath(layout.pageIndex)
        val temporary = Files.createTempFile(root, ".page-", ".tmp")
        try {
            Files.writeString(temporary, json.encodeToString(layout))
            try {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    fun source(): PdfPageLayoutSource =
        PdfPageLayoutSource { action ->
            val pages =
                Files
                    .list(root)
                    .use { entries ->
                        entries
                            .filter { path ->
                                Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                                    path.fileName.toString().endsWith(PAGE_SUFFIX)
                            }.iterator()
                            .asSequence()
                            .toList()
                    }.sortedBy { path -> path.fileName.toString() }
            pages.forEach { path ->
                action(json.decodeFromString<PdfPageLayout>(Files.readString(path)))
            }
        }

    override fun close() {
        root.toFile().deleteRecursively()
    }

    private fun pagePath(pageIndex: Int): Path = root.resolve(pageIndex.toString().padStart(8, '0') + PAGE_SUFFIX)

    private companion object {
        const val PAGE_SUFFIX = ".json"
    }
}
