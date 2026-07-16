package dev.xuexue.pi.coding.session

import dev.xuexue.pi.ai.AgentMessage
import dev.xuexue.pi.ai.AssistantMessage
import dev.xuexue.pi.ai.UserContent
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// Pi exposes session mutation, traversal, persistence, and factories through one facade.
@Suppress("TooManyFunctions")
class SessionManager private constructor(
    cwd: String,
    sessionDir: String,
    sessionFile: String?,
    private val persist: Boolean,
    private val fileSystem: SessionFileSystem,
    newSessionOptions: NewSessionOptions? = null,
) : SessionCwdSource {
    private var sessionFile: String? = sessionFile
    private val sessionDir: String = fileSystem.normalizePath(sessionDir)
    private val cwd: String = fileSystem.resolvePath(cwd)
    private var flushed = false
    private var header = newHeader(createSessionId(), null)
    private val entries = mutableListOf<SessionEntry>()
    private val byId = linkedMapOf<String, SessionEntry>()
    private val labelsById = linkedMapOf<String, String>()
    private val labelTimestampsById = linkedMapOf<String, String>()
    private var leafId: String? = null

    init {
        if (persist && this.sessionDir.isNotEmpty() && !fileSystem.exists(this.sessionDir)) {
            fileSystem.createDirectories(this.sessionDir)
        }
        if (sessionFile != null) setSessionFile(sessionFile) else newSession(newSessionOptions)
    }

    fun setSessionFile(path: String) {
        sessionFile = fileSystem.resolvePath(path)
        val resolvedPath = requireNotNull(sessionFile)
        if (!fileSystem.exists(resolvedPath)) {
            newSession()
            sessionFile = resolvedPath
            return
        }

        val rawEntries = loadEntriesFromFile(resolvedPath, fileSystem).toMutableList()
        if (rawEntries.isEmpty()) {
            if (fileSystem.fileSize(resolvedPath) > 0) {
                error("Session file is not a valid pi session: $resolvedPath")
            }
            newSession()
            sessionFile = resolvedPath
            rewriteFile()
            flushed = true
            return
        }

        val migrated = migrateToCurrentVersion(rawEntries)
        header =
            decodeSessionHeader(rawEntries.first())
                ?: error("Session file is not a valid pi session: $resolvedPath")
        entries.clear()
        entries += rawEntries.drop(1).map(::decodeSessionEntry)
        if (migrated) {
            fileSystem.writeLines(resolvedPath, rawEntries.map(JsonObject::toString))
        }
        buildIndex()
        flushed = true
    }

    fun newSession(options: NewSessionOptions? = null): String? {
        options?.id?.let(::assertValidSessionId)
        val id = options?.id ?: createSessionId()
        header = newHeader(id, options?.parentSession)
        entries.clear()
        byId.clear()
        labelsById.clear()
        labelTimestampsById.clear()
        leafId = null
        flushed = false
        sessionFile =
            if (persist) {
                fileSystem.join(sessionDir, "${fileTimestamp(header.timestamp)}_${header.id}.jsonl")
            } else {
                null
            }
        return sessionFile
    }

    fun isPersisted(): Boolean = persist

    override fun getCwd(): String = cwd

    fun getSessionDir(): String = sessionDir

    fun usesDefaultSessionDir(): Boolean =
        sessionDir ==
            getDefaultSessionDirPath(
                cwd,
                fileSystem.defaultAgentDirectory,
                fileSystem,
            )

    fun getSessionId(): String = header.id

    override fun getSessionFile(): String? = sessionFile

    fun appendMessage(message: AgentMessage): String {
        require(message !is CompactionSummaryMessage && message !is BranchSummaryMessage) {
            "Compaction and branch summaries must be appended as session entries"
        }
        return appendEntry(
            SessionMessageEntry(
                id = nextEntryId(),
                parentId = leafId,
                timestamp = now(),
                message = message,
            ),
        )
    }

    fun appendThinkingLevelChange(thinkingLevel: String): String =
        appendEntry(
            ThinkingLevelChangeEntry(
                id = nextEntryId(),
                parentId = leafId,
                timestamp = now(),
                thinkingLevel = thinkingLevel,
            ),
        )

    fun appendModelChange(
        provider: String,
        modelId: String,
    ): String =
        appendEntry(
            ModelChangeEntry(
                id = nextEntryId(),
                parentId = leafId,
                timestamp = now(),
                provider = provider,
                modelId = modelId,
            ),
        )

    fun appendCompaction(
        summary: String,
        firstKeptEntryId: String,
        tokensBefore: Long,
        details: JsonElement? = null,
        fromHook: Boolean? = null,
    ): String =
        appendEntry(
            CompactionEntry(
                id = nextEntryId(),
                parentId = leafId,
                timestamp = now(),
                summary = summary,
                firstKeptEntryId = firstKeptEntryId,
                tokensBefore = tokensBefore,
                details = details,
                fromHook = fromHook,
            ),
        )

    fun appendCustomEntry(
        customType: String,
        data: JsonElement? = null,
    ): String =
        appendEntry(
            CustomEntry(
                id = nextEntryId(),
                parentId = leafId,
                timestamp = now(),
                customType = customType,
                data = data,
            ),
        )

    fun appendSessionInfo(name: String): String =
        appendEntry(
            SessionInfoEntry(
                id = nextEntryId(),
                parentId = leafId,
                timestamp = now(),
                name = name.replace(SESSION_NAME_NEWLINES, " ").trim(),
            ),
        )

    fun getSessionName(): String? {
        entries.asReversed().forEach { entry ->
            if (entry is SessionInfoEntry) return entry.name?.trim()?.ifEmpty { null }
        }
        return null
    }

    fun appendCustomMessageEntry(
        customType: String,
        content: UserContent,
        display: Boolean,
        details: JsonElement? = null,
    ): String =
        appendEntry(
            CustomMessageEntry(
                id = nextEntryId(),
                parentId = leafId,
                timestamp = now(),
                customType = customType,
                content = content,
                details = details,
                display = display,
            ),
        )

    fun getLeafId(): String? = leafId

    fun getLeafEntry(): SessionEntry? = leafId?.let(byId::get)

    fun getEntry(id: String): SessionEntry? = byId[id]

    fun getChildren(parentId: String): List<SessionEntry> = byId.values.filter { it.parentId == parentId }

    fun getLabel(id: String): String? = labelsById[id]

    fun appendLabelChange(
        targetId: String,
        label: String?,
    ): String {
        require(targetId in byId) { "Entry $targetId not found" }
        val entry =
            LabelEntry(
                id = nextEntryId(),
                parentId = leafId,
                timestamp = now(),
                targetId = targetId,
                label = label,
            )
        appendEntry(entry)
        applyLabel(entry)
        return entry.id
    }

    fun getBranch(fromId: String? = leafId): List<SessionEntry> {
        val path = mutableListOf<SessionEntry>()
        var current = fromId?.let(byId::get)
        while (current != null) {
            path += current
            current = current.parentId?.let(byId::get)
        }
        path.reverse()
        return path
    }

    fun buildContextEntries(): List<SessionEntry> =
        if (leafId == null) {
            emptyList()
        } else {
            buildContextEntries(
                entries,
                leafId,
                byId,
            )
        }

    fun buildSessionContext(): SessionContext =
        if (leafId == null) {
            SessionContext(emptyList(), "off", null)
        } else {
            buildSessionContext(entries, leafId, byId)
        }

    fun getHeader(): SessionHeader = header

    fun getEntries(): List<SessionEntry> = entries.toList()

    fun getTree(): List<SessionTreeNode> {
        val nodes = linkedMapOf<String, SessionTreeNode>()
        val roots = mutableListOf<SessionTreeNode>()
        entries.forEach { entry ->
            nodes[entry.id] =
                SessionTreeNode(
                    entry = entry,
                    label = labelsById[entry.id],
                    labelTimestamp = labelTimestampsById[entry.id],
                )
        }
        entries.forEach { entry ->
            val node = requireNotNull(nodes[entry.id])
            val parent = entry.parentId?.takeUnless { it == entry.id }?.let(nodes::get)
            if (parent == null) roots += node else parent.children += node
        }

        val stack = roots.toMutableList()
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            node.children.sortBy {
                it.entry.timestamp
                    .toInstantOrNull()
                    ?.toEpochMilliseconds() ?: 0L
            }
            stack += node.children
        }
        return roots
    }

    fun branch(branchFromId: String) {
        require(branchFromId in byId) { "Entry $branchFromId not found" }
        leafId = branchFromId
    }

    fun resetLeaf() {
        leafId = null
    }

    fun branchWithSummary(
        branchFromId: String?,
        summary: String,
        details: JsonElement? = null,
        fromHook: Boolean? = null,
    ): String {
        require(branchFromId == null || branchFromId in byId) { "Entry $branchFromId not found" }
        leafId = branchFromId
        return appendEntry(
            BranchSummaryEntry(
                id = nextEntryId(),
                parentId = branchFromId,
                timestamp = now(),
                fromId = branchFromId ?: "root",
                summary = summary,
                details = details,
                fromHook = fromHook,
            ),
        )
    }

    fun createBranchedSession(branchLeafId: String): String? {
        val previousSessionFile = sessionFile
        val path = getBranch(branchLeafId)
        require(path.isNotEmpty()) { "Entry $branchLeafId not found" }

        val pathWithoutLabels = mutableListOf<SessionEntry>()
        var parentId: String? = null
        path.forEach { entry ->
            if (entry !is LabelEntry) {
                pathWithoutLabels += entry.withParentId(parentId)
                parentId = entry.id
            }
        }

        val timestamp = now()
        val newHeader =
            SessionHeader(
                type = "session",
                version = CURRENT_SESSION_VERSION,
                id = createSessionId(),
                timestamp = timestamp,
                cwd = cwd,
                parentSession = previousSessionFile.takeIf { persist },
            )
        val newSessionFile = fileSystem.join(sessionDir, "${fileTimestamp(timestamp)}_${newHeader.id}.jsonl")
        val retainedIds = pathWithoutLabels.mapTo(linkedSetOf(), SessionEntry::id)
        val retainedLabels =
            labelsById.mapNotNull { (targetId, label) ->
                if (targetId in retainedIds) {
                    LabelSnapshot(targetId, label, requireNotNull(labelTimestampsById[targetId]))
                } else {
                    null
                }
            }
        val labelEntries = buildRetainedLabelEntries(pathWithoutLabels.lastOrNull()?.id, retainedIds, retainedLabels)

        header = newHeader
        entries.clear()
        entries += pathWithoutLabels
        entries += labelEntries
        sessionFile = newSessionFile.takeIf { persist }
        buildIndex()
        if (persist) {
            if (hasAssistantMessage()) {
                rewriteFile()
                flushed = true
            } else {
                flushed = false
            }
            return newSessionFile
        }
        return null
    }

    private fun buildRetainedLabelEntries(
        initialParentId: String?,
        retainedIds: MutableSet<String>,
        labels: List<LabelSnapshot>,
    ): List<LabelEntry> {
        var parentId = initialParentId
        return labels.map { snapshot ->
            val id = generateEntryId(retainedIds)
            retainedIds += id
            LabelEntry(
                id = id,
                parentId = parentId,
                timestamp = snapshot.timestamp,
                targetId = snapshot.targetId,
                label = snapshot.label,
            ).also { parentId = it.id }
        }
    }

    private fun appendEntry(entry: SessionEntry): String {
        entries += entry
        byId[entry.id] = entry
        leafId = entry.id
        persist(entry)
        return entry.id
    }

    private fun persist(entry: SessionEntry) {
        val path = sessionFile
        if (persist && path != null) {
            if (!hasAssistantMessage()) {
                if (flushed) fileSystem.appendLine(path, encodeSessionEntry(entry).toString()) else flushed = false
            } else if (!flushed) {
                fileSystem.writeLines(path, encodedFileLines(), createNew = true)
                flushed = true
            } else {
                fileSystem.appendLine(path, encodeSessionEntry(entry).toString())
            }
        }
    }

    private fun buildIndex() {
        byId.clear()
        labelsById.clear()
        labelTimestampsById.clear()
        leafId = null
        entries.forEach { entry ->
            byId[entry.id] = entry
            leafId = entry.id
            if (entry is LabelEntry) applyLabel(entry)
        }
    }

    private fun applyLabel(entry: LabelEntry) {
        if (!entry.label.isNullOrEmpty()) {
            labelsById[entry.targetId] = entry.label
            labelTimestampsById[entry.targetId] = entry.timestamp
        } else {
            labelsById.remove(entry.targetId)
            labelTimestampsById.remove(entry.targetId)
        }
    }

    private fun rewriteFile() {
        val path = sessionFile ?: return
        if (persist) fileSystem.writeLines(path, encodedFileLines())
    }

    private fun encodedFileLines(): List<String> =
        buildList {
            add(encodeSessionHeader(header).toString())
            entries.mapTo(this) { encodeSessionEntry(it).toString() }
        }

    private fun hasAssistantMessage(): Boolean =
        entries.any { entry ->
            entry is SessionMessageEntry &&
                entry.message is AssistantMessage
        }

    private fun nextEntryId(): String = generateEntryId(byId.keys)

    private fun now(): String = currentIsoTimestamp()

    private fun newHeader(
        id: String,
        parentSession: String?,
    ): SessionHeader =
        SessionHeader(
            type = "session",
            version = CURRENT_SESSION_VERSION,
            id = id,
            timestamp = now(),
            cwd = cwd,
            parentSession = parentSession,
        )

    private data class LabelSnapshot(
        val targetId: String,
        val label: String,
        val timestamp: String,
    )

    companion object {
        fun create(
            cwd: String,
            sessionDir: String? = null,
            options: NewSessionOptions? = null,
            fileSystem: SessionFileSystem = PlatformSessionFileSystem,
        ): SessionManager {
            val directory =
                sessionDir?.let(fileSystem::normalizePath)
                    ?: getDefaultSessionDir(cwd, fileSystem = fileSystem)
            return SessionManager(cwd, directory, null, true, fileSystem, options)
        }

        fun open(
            path: String,
            sessionDir: String? = null,
            cwdOverride: String? = null,
            fileSystem: SessionFileSystem = PlatformSessionFileSystem,
        ): SessionManager {
            val resolvedPath = fileSystem.resolvePath(path)
            val header = loadEntriesFromFile(resolvedPath, fileSystem).firstOrNull()?.let(::decodeSessionHeader)
            val cwd = cwdOverride ?: header?.cwd ?: fileSystem.currentWorkingDirectory
            val directory = sessionDir?.let(fileSystem::normalizePath) ?: fileSystem.parent(resolvedPath)
            return SessionManager(cwd, directory, resolvedPath, true, fileSystem)
        }

        fun continueRecent(
            cwd: String,
            sessionDir: String? = null,
            fileSystem: SessionFileSystem = PlatformSessionFileSystem,
        ): SessionManager {
            val directory =
                sessionDir?.let(fileSystem::normalizePath)
                    ?: getDefaultSessionDir(cwd, fileSystem = fileSystem)
            val filterByCwd = sessionDir != null && directory != getDefaultSessionDirPath(cwd, fileSystem = fileSystem)
            val recent = findMostRecentSession(directory, cwd.takeIf { filterByCwd }, fileSystem)
            return SessionManager(cwd, directory, recent, true, fileSystem)
        }

        fun inMemory(
            cwd: String = PlatformSessionFileSystem.currentWorkingDirectory,
            options: NewSessionOptions? = null,
        ): SessionManager = SessionManager(cwd, "", null, false, PlatformSessionFileSystem, options)

        fun forkFrom(
            sourcePath: String,
            targetCwd: String,
            sessionDir: String? = null,
            options: NewSessionOptions? = null,
            fileSystem: SessionFileSystem = PlatformSessionFileSystem,
        ): SessionManager {
            val resolvedSource = fileSystem.resolvePath(sourcePath)
            val resolvedTargetCwd = fileSystem.resolvePath(targetCwd)
            val sourceEntries = loadEntriesFromFile(resolvedSource, fileSystem)
            require(sourceEntries.isNotEmpty()) {
                "Cannot fork: source session file is empty or invalid: $resolvedSource"
            }
            requireNotNull(decodeSessionHeader(sourceEntries.first())) {
                "Cannot fork: source session has no header: $resolvedSource"
            }
            val directory =
                sessionDir?.let(fileSystem::normalizePath)
                    ?: getDefaultSessionDir(resolvedTargetCwd, fileSystem = fileSystem)
            if (!fileSystem.exists(directory)) fileSystem.createDirectories(directory)
            options?.id?.let(::assertValidSessionId)
            val newId = options?.id ?: createSessionId()
            val timestamp = currentIsoTimestamp()
            val newPath = fileSystem.join(directory, "${fileTimestamp(timestamp)}_$newId.jsonl")
            val newHeader =
                SessionHeader(
                    type = "session",
                    version = CURRENT_SESSION_VERSION,
                    id = newId,
                    timestamp = timestamp,
                    cwd = resolvedTargetCwd,
                    parentSession = resolvedSource,
                )
            fileSystem.writeLines(
                newPath,
                listOf(encodeSessionHeader(newHeader).toString()) + sourceEntries.drop(1).map(JsonObject::toString),
                createNew = true,
            )
            return SessionManager(resolvedTargetCwd, directory, newPath, true, fileSystem)
        }

        // Pi's list APIs are asynchronous; keep that ABI even though this injected backend is synchronous.
        @Suppress("RedundantSuspendModifier")
        suspend fun list(
            cwd: String,
            sessionDir: String? = null,
            onProgress: ((loaded: Int, total: Int) -> Unit)? = null,
            fileSystem: SessionFileSystem = PlatformSessionFileSystem,
        ): List<SessionInfo> {
            val directory =
                sessionDir?.let(fileSystem::normalizePath)
                    ?: getDefaultSessionDir(cwd, fileSystem = fileSystem)
            val filterByCwd = sessionDir != null && directory != getDefaultSessionDirPath(cwd, fileSystem = fileSystem)
            val resolvedCwd = fileSystem.resolvePath(cwd)
            return listSessionsFromDirectory(directory, fileSystem, onProgress)
                .filter { !filterByCwd || sessionCwdMatches(it.cwd, resolvedCwd, fileSystem) }
                .sortedByDescending(SessionInfo::modified)
        }

        @Suppress("RedundantSuspendModifier")
        suspend fun listAll(
            sessionDir: String? = null,
            onProgress: ((loaded: Int, total: Int) -> Unit)? = null,
            fileSystem: SessionFileSystem = PlatformSessionFileSystem,
        ): List<SessionInfo> {
            val sessions =
                if (sessionDir != null) {
                    listSessionsFromDirectory(fileSystem.normalizePath(sessionDir), fileSystem, onProgress)
                } else {
                    listAllDefaultDirectories(fileSystem, onProgress)
                }
            return sessions.sortedByDescending(SessionInfo::modified)
        }

        private fun listAllDefaultDirectories(
            fileSystem: SessionFileSystem,
            onProgress: ((loaded: Int, total: Int) -> Unit)?,
        ): List<SessionInfo> {
            val sessionsRoot = fileSystem.join(fileSystem.defaultAgentDirectory, "sessions")
            if (!fileSystem.exists(sessionsRoot)) return emptyList()
            val directories = runCatching { fileSystem.listDirectories(sessionsRoot) }.getOrDefault(emptyList())
            val fileCounts =
                directories.associateWith { directory ->
                    runCatching { fileSystem.listFiles(directory).count { it.path.endsWith(".jsonl") } }
                        .getOrDefault(0)
                }
            val total = fileCounts.values.sum()
            var offset = 0
            return buildList {
                directories.forEach { directory ->
                    addAll(
                        listSessionsFromDirectory(directory, fileSystem) { loaded, _ ->
                            onProgress?.invoke(offset + loaded, total)
                        },
                    )
                    offset += fileCounts.getValue(directory)
                }
            }
        }
    }
}

private fun fileTimestamp(timestamp: String): String = timestamp.replace(Regex("[:.]"), "-")

private val SESSION_NAME_NEWLINES = Regex("[\\r\\n]+")
