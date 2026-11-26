package dev.xuexue

import dev.xuexue.platform.WorkspaceService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DshAgentTest {
    @Test
    fun projectsChunksAndUpdatesToolsInPlace() {
        val session = AgentSessionState("local", "Example")
        val projection = DshTurnProjection(session)

        projection.accept(
            """{"sessionUpdate":"tool_call","toolCallId":"a","title":"read","rawInput":{"path":"N.md"}}""",
        )
        projection.accept(
            """{"sessionUpdate":"tool_call_update","toolCallId":"a","status":"completed","content":[
                {"type":"content","content":{"type":"text","text":"Read one file"}}]}""",
        )
        projection.accept("""{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"One "}}""")
        projection.accept("""{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"file."}}""")

        assertEquals(listOf("tool", "assistant"), session.messages.map { it.role })
        assertEquals("One file.", session.messages.last().content)
        assertTrue(assertNotNull(projection.toolMessage("a")).content.endsWith("Read one file"))
    }

    @Test
    fun loadsPiArchivesAlongsideResumableDshSessions() =
        runBlocking {
            val root = createTempDirectory("xuexue-dsh-test")
            try {
                val legacy = Files.createDirectory(root.resolve(".xuexue")).resolve("agent-sessions.jsonl")
                Files.writeString(
                    legacy,
                    """{"id":"pi","name":"Pi","protocolMessages":[{"role":"user"}],"messages":[
                        {"role":"assistant","content":"Archived","decision":"accepted"}]}""".replace("\n", ""),
                )
                val store = AgentTranscriptStore(WorkspaceService())
                val live = AgentSessionState("local", "DSH", listOf(AgentMessage("user", "Hello")), dshId = "remote")
                store.save(root, listOf(live) + store.load(root))

                val (dsh, pi) = store.load(root)
                assertEquals("remote", dsh.dshId)
                assertFalse(dsh.legacy)
                assertTrue(pi.legacy)
                assertEquals("Archived", pi.messages.single().content)
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    private fun DshTurnProjection.accept(value: String) = update(Json.parseToJsonElement(value).jsonObject)
}
