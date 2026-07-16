package dev.xuexue.pi.coding.session

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SessionCwdTest {
    @Test
    fun detectsMissingCwdFromPersistedSession() =
        withCwdFixture { fallback, missing, sessionFile ->
            val manager = SessionManager.open(sessionFile.toString())

            val issue = getMissingSessionCwdIssue(manager, fallback.toString())

            assertEquals(SessionCwdIssue(sessionFile.toString(), missing.toString(), fallback.toString()), issue)
        }

    @Test
    fun cwdOverrideUsesExistingFallback() =
        withCwdFixture { fallback, _, sessionFile ->
            val manager = SessionManager.open(sessionFile.toString(), cwdOverride = fallback.toString())

            assertEquals(fallback.toString(), manager.getCwd())
            assertNull(getMissingSessionCwdIssue(manager, fallback.toString()))
        }

    @Test
    fun assertionThrowsStructuredErrorBeforeRuntimeCreation() =
        withCwdFixture { fallback, missing, sessionFile ->
            val manager = SessionManager.open(sessionFile.toString())

            val error =
                assertFailsWith<MissingSessionCwdException> {
                    assertSessionCwdExists(manager, fallback.toString())
                }

            assertEquals(missing.toString(), error.issue.sessionCwd)
            assertEquals(
                "Stored session working directory does not exist: $missing\n" +
                    "Session file: $sessionFile\nCurrent working directory: $fallback",
                error.message,
            )
            assertEquals(
                "cwd from session file does not exist\n$missing\n\ncontinue in current cwd\n$fallback",
                formatMissingSessionCwdPrompt(error.issue),
            )
        }
}

private fun withCwdFixture(block: (fallback: Path, missing: Path, sessionFile: Path) -> Unit) {
    val root = Files.createTempDirectory("pi-session-cwd")
    val fallback = root.resolve("fallback")
    val missing = root.resolve("does-not-exist")
    val sessionDirectory = root.resolve("sessions")
    Files.createDirectories(fallback)
    Files.createDirectories(sessionDirectory)
    val sessionFile = sessionDirectory.resolve("session.jsonl")
    val header =
        buildJsonObject {
            put("type", "session")
            put("version", CURRENT_SESSION_VERSION)
            put("id", "session-id")
            put("timestamp", TEST_TIMESTAMP)
            put("cwd", missing.toString())
        }
    Files.writeString(sessionFile, "$header\n")
    try {
        block(fallback, missing, sessionFile)
    } finally {
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
