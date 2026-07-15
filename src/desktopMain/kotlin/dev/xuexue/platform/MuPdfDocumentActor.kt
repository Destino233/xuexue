package dev.xuexue.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

internal data class MuPdfPageSnapshot(
    val pageIndex: Int,
    val bounds: MuPdfRect,
    val displayList: MuPdfDisplayList,
    val overlayDisplayList: MuPdfDisplayList?,
    val dataGeneration: Long,
) {
    private var structuredText: MuPdfTextPage? = null

    val dimensions: PdfPageDimensions
        get() = bounds.dimensions()

    fun textPage(): MuPdfTextPage =
        synchronized(this) {
            structuredText
                ?: MuPdfTextPage(MuPdfNative.createTextPage(displayList.handle)).also { structuredText = it }
        }

    fun close() {
        synchronized(this) {
            structuredText?.close()
            structuredText = null
            overlayDisplayList?.close()
            displayList.close()
        }
    }
}

internal class MuPdfPageLease(
    val snapshot: MuPdfPageSnapshot,
    private val session: MuPdfDocumentSession,
    private val entry: MuPdfPageEntry,
) {
    suspend fun release() = session.releasePage(entry)
}

internal class MuPdfSessionLease(
    val session: MuPdfDocumentSession,
    private val manager: MuPdfSessionManager,
    private val record: MuPdfSessionRecord,
) {
    suspend fun release() = manager.release(record)
}

internal class MuPdfSessionManager(
    private val documentDispatcher: CoroutineDispatcher,
    private val progressiveSources: ProgressivePdfRegistry,
    private val progressiveScope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val current = mutableMapOf<Path, MuPdfSessionRecord>()
    private val records = mutableSetOf<MuPdfSessionRecord>()

    suspend fun acquire(path: Path): MuPdfSessionLease {
        val normalizedPath = path.toAbsolutePath().normalize()
        val progressiveSource = progressiveSources.sourceFor(normalizedPath)
        val revision = progressiveSource?.revision() ?: normalizedPath.revision()
        var sessionToClose: MuPdfDocumentSession? = null
        val record =
            mutex.withLock {
                val existing = current[normalizedPath]
                if (existing != null && existing.revision == revision) {
                    existing.references++
                    existing
                } else {
                    if (existing != null) {
                        existing.retired = true
                        if (existing.references == 0) {
                            records.remove(existing)
                            sessionToClose = existing.session
                        }
                    }
                    MuPdfSessionRecord(
                        revision = revision,
                        session =
                            MuPdfDocumentSession(
                                path = normalizedPath,
                                documentDispatcher = documentDispatcher,
                                progressiveSource = progressiveSource,
                                progressiveScope = progressiveScope,
                            ),
                        references = 1,
                    ).also { created ->
                        current[normalizedPath] = created
                        records += created
                    }
                }
            }
        sessionToClose?.close()
        return MuPdfSessionLease(record.session, this, record)
    }

    suspend fun release(record: MuPdfSessionRecord) {
        var sessionToClose: MuPdfDocumentSession? = null
        mutex.withLock {
            check(record.references > 0) { "MuPDF session reference count underflow." }
            record.references--
            if (record.retired && record.references == 0) {
                records.remove(record)
                sessionToClose = record.session
            }
        }
        sessionToClose?.close()
    }

    suspend fun close() {
        val sessions =
            mutex.withLock {
                current.clear()
                records.map(MuPdfSessionRecord::session).also { records.clear() }
            }
        sessions.forEach { session -> session.close() }
    }

    private fun Path.revision(): MuPdfFileRevision =
        MuPdfFileRevision(
            size = Files.size(this),
            modifiedAtMillis = Files.getLastModifiedTime(this).toMillis(),
        )

    private fun ProgressivePdfSource.revision(): MuPdfFileRevision =
        MuPdfFileRevision(
            size = length,
            modifiedAtMillis = revision,
        )
}

internal class MuPdfDocumentSession(
    private val path: Path,
    private val documentDispatcher: CoroutineDispatcher,
    private val maxDisplayLists: Int = DEFAULT_DISPLAY_LISTS,
    private val progressiveSource: ProgressivePdfSource? = null,
    private val progressiveScope: CoroutineScope? = null,
) {
    private var documentHandle = 0L
    private var progressiveStream: MuPdfProgressiveStream? = null
    private var progressiveGeneration = 0L
    private val pageCache = LinkedHashMap<Int, MuPdfPageEntry>(16, 0.75f, true)
    private val linkCache = mutableMapOf<Int, ByteArray>()

    suspend fun pageCount(): Int = onActorWithProgressiveData { MuPdfNative.countPages(document()) }

    suspend fun loadLinks(pageIndex: Int): ByteArray =
        onActorWithProgressiveData {
            val count = MuPdfNative.countPages(document())
            require(pageIndex in 0 until count) { "PDF page index $pageIndex is outside 0 until $count." }
            linkCache[pageIndex] ?: MuPdfNative.loadLinks(document(), pageIndex).also { linkCache[pageIndex] = it }
        }

    suspend fun acquirePage(pageIndex: Int): MuPdfPageLease =
        onActorWithProgressiveData {
            val count = MuPdfNative.countPages(document())
            require(pageIndex in 0 until count) { "PDF page index $pageIndex is outside 0 until $count." }
            var entry = pageCache[pageIndex]
            if (
                entry?.snapshot?.displayList?.incomplete == true &&
                entry.activeLeases == 0 &&
                progressiveGeneration > entry.snapshot.dataGeneration
            ) {
                pageCache.remove(pageIndex)
                entry.snapshot.close()
                entry = null
            }
            val acquiredEntry =
                entry
                    ?: loadPageSnapshot(pageIndex)
                        .let(::MuPdfPageEntry)
                        .also { loaded -> pageCache[pageIndex] = loaded }
            acquiredEntry.activeLeases++
            evictDisplayLists()
            MuPdfPageLease(acquiredEntry.snapshot, this, acquiredEntry)
        }

    suspend fun releasePage(entry: MuPdfPageEntry) {
        onActor {
            check(entry.activeLeases > 0) { "MuPDF page lease count underflow." }
            entry.activeLeases--
            evictDisplayLists()
        }
    }

    suspend fun close() {
        onActor {
            pageCache.values.forEach { entry -> entry.snapshot.close() }
            pageCache.clear()
            linkCache.clear()
            if (documentHandle != 0L) {
                MuPdfNative.closeDocument(documentHandle)
                documentHandle = 0L
            }
            progressiveStream?.close()
            progressiveStream = null
        }
    }

    private fun document(): Long {
        if (documentHandle != 0L) return documentHandle
        val opened =
            progressiveSource?.let { source ->
                val stream =
                    progressiveStream
                        ?: MuPdfProgressiveStream(source.length, PROGRESSIVE_PDF_BLOCK_BYTES)
                            .also { progressiveStream = it }
                stream.openDocument()
            } ?: MuPdfNative.openDocument(path.toString().encodeToByteArray())
        return opened
            .also { opened ->
                check(opened != 0L) { "MuPDF returned an empty document handle for $path." }
                documentHandle = opened
            }
    }

    private fun loadPageSnapshot(pageIndex: Int): MuPdfPageSnapshot {
        var displayList: MuPdfDisplayList? = null
        var overlayDisplayList: MuPdfDisplayList? = null
        var overlayIncomplete = false
        var retained = false
        return try {
            displayList =
                if (progressiveSource == null) {
                    MuPdfDisplayList(MuPdfNative.loadDisplayList(document(), pageIndex))
                } else {
                    val result = checkNotNull(progressiveStream).loadDisplayList(document(), pageIndex)
                    check(result.size == 2) { "MuPDF returned an invalid progressive display-list result." }
                    MuPdfDisplayList(result[0], incomplete = result[1] != 0L)
                }
            overlayDisplayList =
                if (progressiveSource == null) {
                    MuPdfNative
                        .loadOverlayDisplayList(document(), pageIndex)
                        .takeIf { it != 0L }
                        ?.let(::MuPdfDisplayList)
                } else {
                    val result = checkNotNull(progressiveStream).loadOverlayDisplayList(document(), pageIndex)
                    check(result.size == 2) { "MuPDF returned an invalid progressive overlay result." }
                    overlayIncomplete = result[1] != 0L
                    result[0].takeIf { it != 0L }?.let { handle ->
                        MuPdfDisplayList(handle, incomplete = overlayIncomplete)
                    }
                }
            val content = checkNotNull(displayList)
            val overlay = overlayDisplayList
            val snapshot =
                MuPdfPageSnapshot(
                    pageIndex = pageIndex,
                    bounds = MuPdfNative.displayListBounds(content.handle).toMuPdfRect(),
                    displayList = content,
                    overlayDisplayList = overlay,
                    dataGeneration = progressiveGeneration,
                )
            if (content.incomplete || overlayIncomplete || overlay?.incomplete == true) {
                val source = checkNotNull(progressiveSource)
                val stream = checkNotNull(progressiveStream)
                val range = stream.requestedRange()
                checkNotNull(progressiveScope).launch {
                    runCatching {
                        val bytes = source.read(range)
                        onActor {
                            if (progressiveStream === stream) {
                                stream.provide(range, bytes)
                                progressiveGeneration++
                            }
                        }
                    }
                }
            }
            retained = true
            snapshot
        } finally {
            if (!retained) {
                overlayDisplayList?.close()
                displayList?.close()
            }
        }
    }

    private fun evictDisplayLists() {
        while (pageCache.size > maxDisplayLists) {
            val candidate = pageCache.entries.firstOrNull { (_, entry) -> entry.activeLeases == 0 } ?: return
            pageCache.remove(candidate.key)
            candidate.value.snapshot.close()
        }
    }

    private suspend fun <T> onActor(block: () -> T): T = withContext(documentDispatcher) { block() }

    private suspend fun <T> onActorWithProgressiveData(block: () -> T): T {
        while (true) {
            try {
                return onActor(block)
            } catch (_: PdfTryLaterException) {
                val source = checkNotNull(progressiveSource) { "Local PDF unexpectedly requested progressive data." }
                val stream = onActor { checkNotNull(progressiveStream) }
                val range = onActor(stream::requestedRange)
                val bytes = source.read(range)
                onActor {
                    stream.provide(range, bytes)
                    progressiveGeneration++
                }
            }
        }
    }

    private companion object {
        const val DEFAULT_DISPLAY_LISTS = 12
    }
}

internal class MuPdfPageEntry(
    val snapshot: MuPdfPageSnapshot,
    var activeLeases: Int = 0,
)

internal class MuPdfSessionRecord(
    val revision: MuPdfFileRevision,
    val session: MuPdfDocumentSession,
    var references: Int,
    var retired: Boolean = false,
)

internal data class MuPdfFileRevision(
    val size: Long,
    val modifiedAtMillis: Long,
)
