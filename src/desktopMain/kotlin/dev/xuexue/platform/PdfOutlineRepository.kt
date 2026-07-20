package dev.xuexue.platform

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.pathString

data class PdfOutlineLoadResult(
    val current: PdfOutlineDocument?,
    val stale: PdfOutlineDocument?,
)

@Suppress("TooManyFunctions") // Persistence, migration, merge, and fingerprinting share one atomic store boundary.
class PdfOutlineRepository(
    private val workspaceService: WorkspaceService,
) {
    private val mutex = Mutex()
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    suspend fun load(
        root: Path,
        relativePath: String,
        documentFingerprint: String,
    ): PdfOutlineLoadResult =
        mutex.withLock {
            withWorkspace(root) { safeRoot ->
                val path = outlinesPath(safeRoot)
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    return@withWorkspace PdfOutlineLoadResult(null, null)
                }
                val records = readRecords(path)
                val entry = records.documents[relativePath]
                if (entry == null) {
                    PdfOutlineLoadResult(null, null)
                } else if (entry.documentFingerprint == documentFingerprint) {
                    PdfOutlineLoadResult(entry, null)
                } else {
                    PdfOutlineLoadResult(null, entry)
                }
            }
        }

    suspend fun save(
        root: Path,
        document: PdfOutlineDocument,
    ) {
        mutex.withLock {
            withWorkspace(root) { safeRoot ->
                val path = outlinesPath(safeRoot)
                val existing =
                    if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        readRecords(path)
                    } else {
                        PersistedPdfOutlines()
                    }
                val documents = existing.documents.toMutableMap()
                documents[document.relativePath] = document
                workspaceService.atomicWrite(path, json.encodeToString(PersistedPdfOutlines(documents = documents)))
            }
        }
    }

    suspend fun remove(
        root: Path,
        relativePath: String,
    ) {
        mutex.withLock {
            withWorkspace(root) { safeRoot ->
                val path = outlinesPath(safeRoot)
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return@withWorkspace
                val records = readRecords(path)
                val documents = records.documents.toMutableMap()
                if (documents.remove(relativePath) != null) {
                    workspaceService.atomicWrite(path, json.encodeToString(records.copy(documents = documents)))
                }
            }
        }
    }

    fun fingerprint(path: Path): String {
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "PDF fingerprint source is not a file." }
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> byte.toHex() }
    }

    fun mergePreservingReview(
        existing: PdfOutlineDocument?,
        generated: PdfOutlineDocument,
    ): PdfOutlineDocument =
        if (existing == null) {
            generated
        } else {
            val protectedBranches = existing.nodes.mapNotNull(::protectedBranch)
            if (protectedBranches.isEmpty()) {
                generated
            } else {
                fun findGenerated(
                    protectedNode: PdfOutlineNode,
                    generatedNodes: List<PdfOutlineNode>,
                ): Pair<Int, PdfOutlineNode>? =
                    generatedNodes
                        .withIndex()
                        .firstOrNull { (_, candidate) -> candidate.id == protectedNode.id }
                        ?.let { item -> item.index to item.value }
                        ?: generatedNodes
                            .withIndex()
                            .firstOrNull { (_, candidate) ->
                                outlineMatchKey(candidate) == outlineMatchKey(protectedNode)
                            }?.let { item -> item.index to item.value }

                fun merge(
                    generatedNodes: List<PdfOutlineNode>,
                    candidates: List<PdfOutlineNode>,
                ): List<PdfOutlineNode> {
                    val consumedGenerated = mutableSetOf<Int>()
                    val merged = mutableListOf<PdfOutlineNode>()
                    candidates.forEach { protected ->
                        val match = findGenerated(protected, generatedNodes)
                        if (match == null) {
                            merged += protected
                        } else {
                            val (index, generatedNode) = match
                            consumedGenerated += index
                            merged += protected.copy(children = merge(generatedNode.children, protected.children))
                        }
                    }
                    generatedNodes.forEachIndexed { index, node ->
                        if (index !in consumedGenerated) {
                            merged += node.copy(children = merge(node.children, emptyList()))
                        }
                    }
                    return merged
                }

                generated.copy(nodes = merge(generated.nodes, protectedBranches))
            }
        }

    private fun readRecords(path: Path): PersistedPdfOutlines =
        runCatching {
            val root = json.parseToJsonElement(Files.readString(path)).jsonObject
            val schemaVersion = root["schemaVersion"]?.jsonPrimitive?.intOrNull ?: 1
            require(schemaVersion in 0..CURRENT_PDF_OUTLINE_SCHEMA) {
                "Unsupported PDF outline schema version: $schemaVersion"
            }
            json.decodeFromJsonElement<PersistedPdfOutlines>(migrateRecords(root, schemaVersion))
        }.getOrElse { error ->
            throw IllegalStateException("Could not read PDF outline data: ${error.message}", error)
        }

    private fun migrateRecords(
        root: JsonObject,
        schemaVersion: Int,
    ): JsonObject {
        if (schemaVersion == CURRENT_PDF_OUTLINE_SCHEMA) return root
        val documents = root["documents"]?.jsonObject.orEmpty()
        return buildJsonObject {
            root.forEach { (key, value) ->
                if (key != "schemaVersion" && key != "documents") put(key, value)
            }
            put("schemaVersion", CURRENT_PDF_OUTLINE_SCHEMA)
            put(
                "documents",
                buildJsonObject {
                    documents.forEach { (path, document) ->
                        put(
                            path,
                            buildJsonObject {
                                document.jsonObject.forEach { (key, value) ->
                                    if (key != "schemaVersion") put(key, value)
                                }
                                put("schemaVersion", CURRENT_PDF_OUTLINE_SCHEMA)
                            },
                        )
                    }
                },
            )
        }
    }

    private fun <T> withWorkspace(
        root: Path,
        block: (Path) -> T,
    ): T = block(workspaceService.canonicalRoot(root))

    private fun outlinesPath(
        root: Path,
    ): Path =
        workspaceService.resolveForWriteInside(
            root,
            PDF_OUTLINES_RELATIVE_PATH,
        )

    private fun protectedBranch(node: PdfOutlineNode): PdfOutlineNode? {
        val children = node.children.mapNotNull(::protectedBranch)
        return if (node.isReviewProtected() || children.isNotEmpty()) {
            node.copy(children = children)
        } else {
            null
        }
    }

    private fun PdfOutlineNode.isReviewProtected(): Boolean =
        reviewState == PdfOutlineReviewState.MANUAL ||
            reviewState == PdfOutlineReviewState.CONFIRMED

    private fun outlineMatchKey(
        node: PdfOutlineNode,
    ): String = "${node.destination.pageIndex}|${normalizeOutlineTitle(node.title)}"

    private companion object {
        const val PDF_OUTLINES_RELATIVE_PATH = ".xuexue/pdf-outlines.json"

        fun normalizeOutlineTitle(title: String): String = title.replace(Regex("\\s+"), " ").trim().lowercase()
    }
}

@Serializable
private data class PersistedPdfOutlines(
    val schemaVersion: Int = CURRENT_PDF_OUTLINE_SCHEMA,
    val documents: Map<String, PdfOutlineDocument> = emptyMap(),
)

class PdfAnalysisCache(
    private val cacheRoot: Path = defaultAnalysisCacheRoot(),
) {
    private val json = Json { encodeDefaults = true }

    fun loadPage(
        key: PdfAnalysisCacheKey,
        pageIndex: Int,
    ): PdfPageLayout? {
        val manifest = manifestPath(key)
        return if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
            null
        } else {
            val stored =
                runCatching {
                    json.decodeFromString<PdfAnalysisCacheManifest>(Files.readString(manifest))
                }.getOrNull()
            if (stored?.identity != key.identity()) {
                null
            } else {
                val page = pagePath(key, pageIndex)
                if (!Files.isRegularFile(page, LinkOption.NOFOLLOW_LINKS)) {
                    null
                } else {
                    runCatching { json.decodeFromString<PdfPageLayout>(Files.readString(page)) }
                        .getOrNull()
                        ?.takeIf { layout -> layout.pageIndex == pageIndex }
                }
            }
        }
    }

    fun savePage(
        key: PdfAnalysisCacheKey,
        layout: PdfPageLayout,
    ) {
        require(layout.pageIndex in 0 until key.pageCount) { "Analysis cache page is outside the document." }
        val directory = directory(key)
        Files.createDirectories(directory.resolve("pages"))
        val manifest = PdfAnalysisCacheManifest(key.identity())
        atomicWrite(manifestPath(key), json.encodeToString(manifest))
        atomicWrite(pagePath(key, layout.pageIndex), json.encodeToString(layout))
    }

    fun invalidate(key: PdfAnalysisCacheKey) {
        directory(key).toFile().deleteRecursively()
    }

    private fun directory(key: PdfAnalysisCacheKey): Path = cacheRoot.resolve(key.cacheDirectoryName())

    private fun manifestPath(key: PdfAnalysisCacheKey): Path = directory(key).resolve("manifest.json")

    private fun pagePath(
        key: PdfAnalysisCacheKey,
        pageIndex: Int,
    ): Path = directory(key).resolve("pages").resolve(pageIndex.toString().padStart(6, '0') + ".json")

    private fun atomicWrite(
        path: Path,
        content: String,
    ) {
        Files.createDirectories(path.parent)
        val temp = Files.createTempFile(path.parent, ".${path.fileName}.", ".tmp")
        try {
            Files.writeString(temp, content)
            Files.move(
                temp,
                path,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}

data class PdfAnalysisCacheKey(
    val documentFingerprint: String,
    val fileSize: Long,
    val pageCount: Int,
    val engineId: String,
    val engineVersion: String,
    val modelVersion: String?,
    val dpi: Int,
    val language: String,
    val optionsFingerprint: String = "",
    val runtimeFingerprint: String = "",
    val cacheSchemaVersion: Int = CURRENT_PDF_ANALYSIS_CACHE_SCHEMA,
) {
    init {
        require(documentFingerprint.matches(Regex("[0-9a-f]{64}"))) { "Analysis cache fingerprint must be SHA-256." }
        require(fileSize >= 0 && pageCount >= 0 && dpi > 0 && cacheSchemaVersion > 0) {
            "Analysis cache dimensions are invalid."
        }
    }

    fun identity(): String =
        listOf(
            documentFingerprint,
            fileSize,
            pageCount,
            engineId,
            engineVersion,
            modelVersion.orEmpty(),
            dpi,
            language,
            optionsFingerprint,
            runtimeFingerprint,
            cacheSchemaVersion,
        ).joinToString("|")

    fun cacheDirectoryName(): String = sha256(identity())
}

const val CURRENT_PDF_ANALYSIS_CACHE_SCHEMA = 2

@Serializable
private data class PdfAnalysisCacheManifest(
    val identity: String,
)

private fun defaultAnalysisCacheRoot(): Path {
    val configured = System.getenv("XDG_CACHE_HOME")?.takeIf(String::isNotBlank)?.let(Path::of)
    return (configured ?: Path.of(System.getProperty("user.home"), ".cache")).resolve("xuexue/document-analysis")
}

private fun sha256(value: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { byte -> byte.toHex() }

private fun Byte.toHex(): String = toInt().and(0xff).toString(16).padStart(2, '0')
