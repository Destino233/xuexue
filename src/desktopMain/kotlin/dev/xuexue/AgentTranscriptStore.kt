package dev.xuexue

import dev.xuexue.platform.WorkspaceService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext

@Serializable
data class AgentMessage(
    val role: String,
    val content: String,
    val error: Boolean = false,
)

@Serializable
private data class AgentTranscript(
    val id: String,
    val name: String,
    val messages: List<AgentMessage>,
    val dshId: String? = null,
    val legacy: Boolean = false,
)

internal class AgentTranscriptStore(
    private val workspace: WorkspaceService,
    private val io: CoroutineContext = Dispatchers.IO,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(root: Path): List<AgentSessionState> {
        val loaded = withContext(io) { read(root) }
        return loaded.distinctBy { it.id }.map { transcript ->
            AgentSessionState(transcript.id, transcript.name, transcript.messages, transcript.dshId, transcript.legacy)
        }
    }

    suspend fun save(
        root: Path,
        sessions: List<AgentSessionState>,
    ) {
        val snapshots =
            sessions.filterNot { it.legacy || (it.dshId == null && it.messages.isEmpty()) }.map {
                AgentTranscript(it.id, it.name, it.messages.toList(), it.dshId)
            }
        if (snapshots.isEmpty()) return
        withContext(io) {
            workspace.atomicWrite(
                workspace.resolveForWriteInside(root, CURRENT),
                snapshots.joinToString(separator = "\n", postfix = "\n") { json.encodeToString(it) },
            )
        }
    }

    private fun read(root: Path): List<AgentTranscript> =
        buildList {
            addAll(readLines(root, CURRENT))
            val old = readable(root, LEGACY_LINES)?.let(::readLines) ?: readLegacy(root)
            addAll(old.map { it.copy(legacy = true, dshId = null) })
        }

    private fun readLegacy(root: Path): List<AgentTranscript> {
        val archive = readable(root, ".xuexue/agent-sessions.json")
        if (archive != null) {
            return runCatching { json.decodeFromString<List<AgentTranscript>>(Files.readString(archive)) }
                .getOrDefault(emptyList())
        }
        return readable(root, ".xuexue/agent-session.json")
            ?.let { single ->
                runCatching {
                    listOf(AgentTranscript("default", "旧会话", json.decodeFromString(Files.readString(single))))
                }.getOrDefault(emptyList())
            }.orEmpty()
    }

    private fun readLines(
        root: Path,
        name: String,
    ): List<AgentTranscript> = readable(root, name)?.let(::readLines).orEmpty()

    private fun readLines(file: Path): List<AgentTranscript> =
        Files.lines(file).use { lines ->
            lines
                .map { runCatching { json.decodeFromString<AgentTranscript>(it) }.getOrNull() }
                .filter { it != null }
                .toList()
                .filterNotNull()
        }

    private fun readable(
        root: Path,
        name: String,
    ): Path? =
        runCatching { workspace.resolveExistingInside(root, name) }
            .getOrNull()
            ?.takeIf(Files::isRegularFile)

    private companion object {
        const val CURRENT = ".xuexue/dsh-transcripts.jsonl"
        const val LEGACY_LINES = ".xuexue/agent-sessions.jsonl"
    }
}
