package dev.xuexue.pi.coding.session

import dev.xuexue.pi.ai.AssistantMessage
import dev.xuexue.pi.ai.TextContent
import dev.xuexue.pi.ai.UserBlocks
import dev.xuexue.pi.ai.UserMessage
import dev.xuexue.pi.ai.UserText
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

fun getDefaultSessionDir(
    cwd: String,
    agentDir: String = PlatformSessionFileSystem.defaultAgentDirectory,
    fileSystem: SessionFileSystem = PlatformSessionFileSystem,
): String {
    val sessionDir = getDefaultSessionDirPath(cwd, agentDir, fileSystem)
    if (!fileSystem.exists(sessionDir)) fileSystem.createDirectories(sessionDir)
    return sessionDir
}

fun loadEntriesFromFile(
    filePath: String,
    fileSystem: SessionFileSystem = PlatformSessionFileSystem,
): List<JsonObject> {
    val resolvedPath = fileSystem.normalizePath(filePath)
    return if (!fileSystem.exists(resolvedPath)) {
        emptyList()
    } else {
        val entries =
            runCatching {
                buildList {
                    fileSystem.readLines(resolvedPath) { line ->
                        parseSessionEntryLine(line)?.let(::add)
                    }
                }
            }.getOrDefault(emptyList())
        val header = entries.firstOrNull()
        if (header?.string("type") == "session" && !header.string("id").isNullOrEmpty()) entries else emptyList()
    }
}

fun findMostRecentSession(
    sessionDir: String,
    cwd: String? = null,
    fileSystem: SessionFileSystem = PlatformSessionFileSystem,
): String? {
    val resolvedDirectory = fileSystem.normalizePath(sessionDir)
    val resolvedCwd = cwd?.let(fileSystem::resolvePath)
    return runCatching {
        fileSystem
            .listFiles(resolvedDirectory)
            .asSequence()
            .filter { it.path.endsWith(".jsonl") }
            .mapNotNull { file -> readSessionHeader(file.path, fileSystem)?.let { header -> file to header } }
            .filter { (_, header) ->
                resolvedCwd == null || sessionCwdMatches(header.cwd, resolvedCwd, fileSystem)
            }.maxByOrNull { (file) -> file.modifiedAtMillis }
            ?.first
            ?.path
    }.getOrNull()
}

internal fun getDefaultSessionDirPath(
    cwd: String,
    agentDir: String = PlatformSessionFileSystem.defaultAgentDirectory,
    fileSystem: SessionFileSystem = PlatformSessionFileSystem,
): String {
    val resolvedCwd = fileSystem.resolvePath(cwd)
    val resolvedAgentDir = fileSystem.resolvePath(agentDir)
    val withoutRoot = resolvedCwd.replace(LEADING_SEPARATOR, "")
    val safePath = "--${withoutRoot.replace(PATH_SEPARATOR, "-")}--"
    return fileSystem.join(fileSystem.join(resolvedAgentDir, "sessions"), safePath)
}

internal fun listSessionsFromDirectory(
    directory: String,
    fileSystem: SessionFileSystem,
    onProgress: ((loaded: Int, total: Int) -> Unit)? = null,
): List<SessionInfo> {
    if (!fileSystem.exists(directory)) return emptyList()
    val files =
        runCatching { fileSystem.listFiles(directory).filter { it.path.endsWith(".jsonl") } }
            .getOrDefault(emptyList())
    var loaded = 0
    return files.mapNotNull { file ->
        val info = buildSessionInfo(file, fileSystem)
        loaded++
        onProgress?.invoke(loaded, files.size)
        info
    }
}

internal fun readSessionHeader(
    filePath: String,
    fileSystem: SessionFileSystem,
): SessionHeader? =
    runCatching {
        val line = fileSystem.readFirstLine(filePath, SESSION_HEADER_READ_SIZE) ?: return null
        val payload = parseSessionEntryLine(line) ?: return null
        decodeSessionHeader(payload)
    }.getOrNull()

internal fun sessionCwdMatches(
    cwd: String?,
    resolvedCwd: String,
    fileSystem: SessionFileSystem,
): Boolean = !cwd.isNullOrEmpty() && fileSystem.resolvePath(cwd) == resolvedCwd

private fun buildSessionInfo(
    file: SessionFileMetadata,
    fileSystem: SessionFileSystem,
): SessionInfo? =
    runCatching {
        val rawEntries = loadEntriesFromFile(file.path, fileSystem)
        if (rawEntries.isEmpty()) return null
        val header = decodeSessionHeader(rawEntries.first()) ?: return null
        val accumulator = SessionInfoAccumulator()
        rawEntries.drop(1).map(::decodeSessionEntry).forEach(accumulator::accept)
        accumulator.build(header, file)
    }.getOrNull()

private class SessionInfoAccumulator {
    private var messageCount = 0
    private var firstMessage = ""
    private val allMessages = mutableListOf<String>()
    private var name: String? = null
    private var lastActivityMillis: Long? = null

    fun accept(entry: SessionEntry) {
        if (entry is SessionInfoEntry) name = entry.name?.trim()?.ifEmpty { null }
        val messageEntry = entry as? SessionMessageEntry ?: return
        messageCount++
        val message = messageEntry.message
        if (message is UserMessage || message is AssistantMessage) {
            val activityMillis =
                message.timestamp.takeIf { it > 0 }
                    ?: messageEntry.timestamp.toInstantOrNull()?.toEpochMilliseconds()
            if (activityMillis != null) lastActivityMillis = maxOf(lastActivityMillis ?: 0L, activityMillis)
        }
        val text = message.activityText() ?: return
        allMessages += text
        if (firstMessage.isEmpty() && message is UserMessage) firstMessage = text
    }

    fun build(
        header: SessionHeader,
        file: SessionFileMetadata,
    ): SessionInfo {
        val fileModified = Instant.fromEpochMilliseconds(file.modifiedAtMillis)
        val headerTimestamp = header.timestamp.toInstantOrNull()
        return SessionInfo(
            path = file.path,
            id = header.id,
            cwd = header.cwd,
            name = name,
            parentSessionPath = header.parentSession,
            created = headerTimestamp ?: fileModified,
            modified =
                lastActivityMillis
                    ?.takeIf { it > 0 }
                    ?.let(Instant::fromEpochMilliseconds)
                    ?: headerTimestamp
                    ?: fileModified,
            messageCount = messageCount,
            firstMessage = firstMessage.ifEmpty { "(no messages)" },
            allMessagesText = allMessages.joinToString(" "),
        )
    }
}

private fun dev.xuexue.pi.ai.AgentMessage.activityText(): String? =
    when (this) {
        is UserMessage -> {
            when (val value = content) {
                is UserText -> value.value
                is UserBlocks -> value.value.filterIsInstance<TextContent>().joinToString(" ") { it.text }
            }
        }

        is AssistantMessage -> {
            content.filterIsInstance<TextContent>().joinToString(" ") { it.text }
        }

        else -> {
            null
        }
    }?.takeIf(String::isNotEmpty)

internal fun String.toInstantOrNull(): Instant? = runCatching { Instant.parse(this) }.getOrNull()

private const val SESSION_HEADER_READ_SIZE = 512
private val LEADING_SEPARATOR = Regex("^[/\\\\]")
private val PATH_SEPARATOR = Regex("[/\\\\:]")
