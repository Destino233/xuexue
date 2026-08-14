package dev.xuexue.pi.coding.session

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionPersistenceTest {
    @Test
    fun loadEntriesRejectsMissingEmptyMalformedAndHeaderlessFiles() =
        withTempDirectory { directory ->
            assertTrue(loadEntriesFromFile(directory.resolve("missing.jsonl").toString()).isEmpty())

            val empty = directory.resolve("empty.jsonl")
            Files.writeString(empty, "")
            assertTrue(loadEntriesFromFile(empty.toString()).isEmpty())

            val malformed = directory.resolve("malformed.jsonl")
            Files.writeString(malformed, "not json\n")
            assertTrue(loadEntriesFromFile(malformed.toString()).isEmpty())

            val headerless = directory.resolve("headerless.jsonl")
            Files.writeString(headerless, "{\"type\":\"message\",\"id\":\"1\"}\n")
            assertTrue(loadEntriesFromFile(headerless.toString()).isEmpty())
        }

    @Test
    fun loadEntriesSkipsMalformedLinesAfterValidHeader() =
        withTempDirectory { directory ->
            val file = directory.resolve("mixed.jsonl")
            Files.writeString(
                file,
                """
                {"type":"session","version":3,"id":"abc","timestamp":"2025-01-01T00:00:00Z","cwd":"/tmp"}
                not valid json
                {"type":"message","id":"1","parentId":null,"timestamp":"2025-01-01T00:00:01Z","message":{"role":"user","content":"hi","timestamp":1}}
                """.trimIndent() + "\n",
            )

            val entries = loadEntriesFromFile(file.toString())

            assertEquals(2, entries.size)
        }

    @Test
    fun newSessionDoesNotCreateFileUntilAssistantArrives() =
        withTempDirectory { directory ->
            val session = SessionManager.create(directory.toString(), directory.toString())
            val file = Path.of(assertNotNull(session.getSessionFile()))

            session.appendMessage(userMessage("hello"))
            assertFalse(file.exists())

            session.appendCustomEntry("preset", buildJsonObject { put("name", "plan") })
            assertFalse(file.exists())

            session.appendMessage(assistantMessage("hi"))
            assertTrue(file.exists())
            assertEquals(4, Files.readAllLines(file).size)

            session.appendThinkingLevelChange("high")
            assertEquals(5, Files.readAllLines(file).size)
        }

    @Test
    fun initializedEmptyExplicitFilePersistsHeaderAndSubsequentEntries() =
        withTempDirectory { directory ->
            val file = directory.resolve("explicit.jsonl")
            Files.writeString(file, "")

            val session = SessionManager.open(file.toString(), directory.toString())

            assertEquals(file.toString(), session.getSessionFile())
            assertEquals("session", loadEntriesFromFile(file.toString()).single().string("type"))
            session.appendMessage(userMessage("hello"))
            assertEquals(2, Files.readAllLines(file).size)
        }

    @Test
    fun invalidNonEmptyExplicitFileIsPreserved() =
        withTempDirectory { directory ->
            val file = directory.resolve("invalid.jsonl")
            val original = "{\"type\":\"event\",\"data\":\"not a session\"}\n"
            Files.writeString(file, original)

            val error =
                assertFailsWith<IllegalStateException> {
                    SessionManager.open(file.toString(), directory.toString())
                }

            assertEquals("Session file is not a valid pi session: $file", error.message)
            assertEquals(original, file.readText())
        }

    @Test
    fun nonExistingExplicitPathIsPreservedWithoutPrematureCreation() =
        withTempDirectory { directory ->
            val file = directory.resolve("chosen-name.jsonl")

            val session = SessionManager.open(file.toString(), directory.toString(), directory.toString())

            assertEquals(file.toString(), session.getSessionFile())
            assertFalse(file.exists())
            session.appendMessage(userMessage("hello"))
            session.appendMessage(assistantMessage("hi"))
            assertTrue(file.exists())
        }

    @Test
    fun openingV1SessionMigratesAndBuildsContext() =
        withTempDirectory { directory ->
            val file = directory.resolve("v1.jsonl")
            val encodedDirectory = JsonPrimitive(directory.toString())
            Files.writeString(
                file,
                """
                {"type":"session","id":"legacy","timestamp":"2025-01-01T00:00:00Z","cwd":$encodedDirectory}
                {"type":"message","timestamp":"2025-01-01T00:00:01Z","message":{"role":"user","content":"hello","timestamp":1}}
                {"type":"message","timestamp":"2025-01-01T00:00:02Z","message":{"role":"assistant","content":[{"type":"text","text":"hi"}],"api":"test","provider":"test","model":"test","usage":{"input":1,"output":1,"cacheRead":0,"cacheWrite":0},"stopReason":"stop","timestamp":2}}
                """.trimIndent() + "\n",
            )

            val session = SessionManager.open(file.toString(), directory.toString())

            assertEquals(CURRENT_SESSION_VERSION, session.getHeader().version)
            assertEquals(2, session.getEntries().size)
            assertEquals(2, session.buildSessionContext().messages.size)
            assertTrue(Files.readAllLines(file).first().contains("\"version\":3"))
        }

    @Test
    fun mostRecentSessionUsesMtimeAndOptionalCwdFilter() =
        withTempDirectory { directory ->
            val projectA = directory.resolve("project-a")
            val projectB = directory.resolve("project-b")
            Files.createDirectories(projectA)
            Files.createDirectories(projectB)
            val first = directory.resolve("first.jsonl")
            val second = directory.resolve("second.jsonl")
            writeHeader(first, "first", projectA)
            writeHeader(second, "second", projectB)
            Files.setLastModifiedTime(first, FileTime.fromMillis(1_000))
            Files.setLastModifiedTime(second, FileTime.fromMillis(2_000))

            assertEquals(second.toString(), findMostRecentSession(directory.toString()))
            assertEquals(first.toString(), findMostRecentSession(directory.toString(), projectA.toString()))
            assertEquals(second.toString(), findMostRecentSession(directory.toString(), projectB.toString()))
        }

    @Test
    fun customSessionIdIsUsedInHeaderAndFileName() =
        withTempDirectory { directory ->
            val session =
                SessionManager.create(
                    directory.toString(),
                    directory.toString(),
                    NewSessionOptions(id = "custom.session-1"),
                )
            session.appendMessage(userMessage("hello"))
            session.appendMessage(assistantMessage("hi"))

            assertEquals("custom.session-1", session.getSessionId())
            val fileName = Path.of(assertNotNull(session.getSessionFile())).fileName.toString()
            assertTrue(fileName.endsWith("_custom.session-1.jsonl"))
            assertTrue(SESSION_FILE_PATTERN.matches(fileName), fileName)
            assertEquals("custom.session-1", session.getHeader().id)
        }

    @Test
    fun branchedPathWithoutAssistantDefersFileAndAvoidsDuplicateHeader() =
        withTempDirectory { directory ->
            val session = SessionManager.create(directory.toString(), directory.toString())
            val first = session.appendMessage(userMessage("first question"))
            session.appendMessage(assistantMessage("first answer"))
            session.appendMessage(userMessage("second question"))
            session.appendMessage(assistantMessage("second answer"))

            val branchedFile = Path.of(assertNotNull(session.createBranchedSession(first)))
            assertFalse(branchedFile.exists())
            session.appendCustomEntry("preset-state", buildJsonObject { put("name", "plan") })
            session.appendMessage(assistantMessage("new answer"))

            val records = loadEntriesFromFile(branchedFile.toString())
            assertEquals(1, records.count { it.string("type") == "session" })
            val ids = records.drop(1).mapNotNull { it.string("id") }
            assertEquals(ids.size, ids.toSet().size)
        }

    @Test
    fun flatCustomDirectoryScopesListAndContinueByCwd() =
        runTest {
            val directory = Files.createTempDirectory("pi-session-test")
            try {
                val projectA = directory.resolve("project-a")
                val projectB = directory.resolve("project-b")
                Files.createDirectories(projectA)
                Files.createDirectories(projectB)
                val sessionA = createPersistedSession(projectA, directory, "from A")
                val sessionB = createPersistedSession(projectB, directory, "from B")
                Files.setLastModifiedTime(Path.of(sessionA), FileTime.fromMillis(1_000))
                Files.setLastModifiedTime(Path.of(sessionB), FileTime.fromMillis(2_000))

                val currentProject = SessionManager.list(projectA.toString(), directory.toString())
                val allProjects = SessionManager.listAll(directory.toString())
                val continued = SessionManager.continueRecent(projectA.toString(), directory.toString())
                assertEquals(listOf(sessionA), currentProject.map(SessionInfo::path))
                assertEquals(setOf(sessionA, sessionB), allProjects.map(SessionInfo::path).toSet())
                assertEquals(sessionA, continued.getSessionFile())
            } finally {
                deleteDirectory(directory)
            }
        }

    private companion object {
        val SESSION_FILE_PATTERN =
            Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}-\\d{2}-\\d{2}-\\d{3}Z_custom\\.session-1\\.jsonl$")
    }
}

private fun createPersistedSession(
    cwd: Path,
    sessionDirectory: Path,
    text: String,
): String {
    val session = SessionManager.create(cwd.toString(), sessionDirectory.toString())
    session.appendMessage(userMessage(text))
    session.appendMessage(assistantMessage("reply to $text"))
    return assertNotNull(session.getSessionFile())
}

private fun writeHeader(
    file: Path,
    id: String,
    cwd: Path,
) {
    val header =
        buildJsonObject {
            put("type", "session")
            put("version", CURRENT_SESSION_VERSION)
            put("id", id)
            put("timestamp", TEST_TIMESTAMP)
            put("cwd", cwd.toString())
        }
    Files.writeString(file, "$header\n")
}

private fun withTempDirectory(block: (Path) -> Unit) {
    val directory = Files.createTempDirectory("pi-session-test")
    try {
        block(directory)
    } finally {
        deleteDirectory(directory)
    }
}

private fun deleteDirectory(directory: Path) {
    Files.walk(directory).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
}
