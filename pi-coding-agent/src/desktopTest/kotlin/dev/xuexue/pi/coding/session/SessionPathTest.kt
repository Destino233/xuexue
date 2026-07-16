package dev.xuexue.pi.coding.session

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionPathTest {
    @Test
    fun fileUrlPathsResolveToTheSameSessionFile() {
        val directory = Files.createTempDirectory("pi-session-url")
        val file = directory.resolve("url-session.jsonl")
        val header =
            buildJsonObject {
                put("type", "session")
                put("version", CURRENT_SESSION_VERSION)
                put("id", "url-session")
                put("timestamp", TEST_TIMESTAMP)
                put("cwd", directory.toString())
            }
        Files.writeString(file, "$header\n")
        try {
            val manager = SessionManager.open(file.toUri().toString())

            assertEquals(file.toString(), manager.getSessionFile())
            assertEquals("url-session", manager.getSessionId())
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(directory)
        }
    }
}
