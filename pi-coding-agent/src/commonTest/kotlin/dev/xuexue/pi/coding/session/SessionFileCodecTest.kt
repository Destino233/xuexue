package dev.xuexue.pi.coding.session

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionFileCodecTest {
    @Test
    fun parseSkipsBlankAndMalformedLines() {
        val entries =
            parseSessionEntries(
                """
                {"type":"session","id":"abc","timestamp":"2025-01-01T00:00:00Z","cwd":"/tmp"}
                not json

                {"type":"message","id":"1","parentId":null,"timestamp":"2025-01-01T00:00:01Z","message":{"role":"user","content":"hi","timestamp":1}}
                """.trimIndent(),
            )

        assertEquals(2, entries.size)
        assertEquals("session", entries[0].string("type"))
        assertEquals("message", entries[1].string("type"))
    }

    @Test
    fun v1MigrationAddsTreeIdsAndCurrentVersion() {
        val entries =
            parseSessionEntries(
                """
                {"type":"session","id":"sess-1","timestamp":"2025-01-01T00:00:00Z","cwd":"/tmp"}
                {"type":"message","timestamp":"2025-01-01T00:00:01Z","message":{"role":"user","content":"hi","timestamp":1}}
                {"type":"message","timestamp":"2025-01-01T00:00:02Z","message":{"role":"assistant","content":[{"type":"text","text":"hello"}],"api":"test","provider":"test","model":"test","usage":{"input":1,"output":1,"cacheRead":0,"cacheWrite":0},"stopReason":"stop","timestamp":2}}
                """.trimIndent(),
            ).toMutableList()

        migrateSessionEntries(entries)

        assertEquals(CURRENT_SESSION_VERSION, entries[0]["version"]?.jsonPrimitive?.int)
        val firstId = entries[1].string("id")
        val secondId = entries[2].string("id")
        assertEquals(8, firstId?.length)
        assertEquals(8, secondId?.length)
        assertNull(entries[1].string("parentId"))
        assertEquals(firstId, entries[2].string("parentId"))
    }

    @Test
    fun v2MigrationKeepsIdsAndRenamesHookMessageRole() {
        val entries =
            parseSessionEntries(
                """
                {"type":"session","version":2,"id":"sess-1","timestamp":"2025-01-01T00:00:00Z","cwd":"/tmp"}
                {"type":"message","id":"abc12345","parentId":null,"timestamp":"2025-01-01T00:00:01Z","message":{"role":"hookMessage","customType":"test","content":"hi","display":true,"timestamp":1}}
                """.trimIndent(),
            ).toMutableList()

        migrateSessionEntries(entries)

        assertEquals("abc12345", entries[1].string("id"))
        assertEquals("custom", entries[1]["message"]?.jsonObject?.string("role"))
        assertEquals(CURRENT_SESSION_VERSION, entries[0]["version"]?.jsonPrimitive?.int)
    }

    @Test
    fun currentVersionMigrationIsIdempotent() {
        val entries =
            parseSessionEntries(
                """
                {"type":"session","version":3,"id":"sess-1","timestamp":"2025-01-01T00:00:00Z","cwd":"/tmp"}
                {"type":"message","id":"abc12345","parentId":null,"timestamp":"2025-01-01T00:00:01Z","message":{"role":"user","content":"hi","timestamp":1}}
                """.trimIndent(),
            ).toMutableList()
        val before = entries.toList()

        migrateSessionEntries(entries)

        assertEquals(before, entries)
    }

    @Test
    fun sessionIdValidationMatchesPiGrammar() {
        listOf("a", "abc-123", "with_under.score", "A9").forEach(::assertValidSessionId)
        listOf("", "-abc", "abc-", ".abc", "abc.", "has space", "slash/id").forEach { id ->
            assertFailsWith<IllegalArgumentException> { assertValidSessionId(id) }
        }
    }

    @Test
    fun generatedSessionIdsAreUuidV7() {
        val id = SessionManager.inMemory("/tmp").getSessionId()

        assertTrue(UUID_V7_PATTERN.matches(id), id)
    }

    private companion object {
        val UUID_V7_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    }
}
