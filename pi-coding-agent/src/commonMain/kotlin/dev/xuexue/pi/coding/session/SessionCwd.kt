package dev.xuexue.pi.coding.session

data class SessionCwdIssue(
    val sessionFile: String? = null,
    val sessionCwd: String,
    val fallbackCwd: String,
)

interface SessionCwdSource {
    fun getCwd(): String

    fun getSessionFile(): String?
}

fun getMissingSessionCwdIssue(
    sessionManager: SessionCwdSource,
    fallbackCwd: String,
    fileSystem: SessionFileSystem = PlatformSessionFileSystem,
): SessionCwdIssue? {
    val sessionFile = sessionManager.getSessionFile() ?: return null
    val sessionCwd = sessionManager.getCwd()
    return if (sessionCwd.isEmpty() || fileSystem.exists(sessionCwd)) {
        null
    } else {
        SessionCwdIssue(sessionFile, sessionCwd, fallbackCwd)
    }
}

fun formatMissingSessionCwdError(issue: SessionCwdIssue): String {
    val sessionFile = issue.sessionFile?.let { "\nSession file: $it" }.orEmpty()
    return "Stored session working directory does not exist: ${issue.sessionCwd}$sessionFile\n" +
        "Current working directory: ${issue.fallbackCwd}"
}

fun formatMissingSessionCwdPrompt(issue: SessionCwdIssue): String =
    "cwd from session file does not exist\n${issue.sessionCwd}\n\n" +
        "continue in current cwd\n${issue.fallbackCwd}"

class MissingSessionCwdException(
    val issue: SessionCwdIssue,
) : IllegalStateException(formatMissingSessionCwdError(issue))

fun assertSessionCwdExists(
    sessionManager: SessionCwdSource,
    fallbackCwd: String,
    fileSystem: SessionFileSystem = PlatformSessionFileSystem,
) {
    getMissingSessionCwdIssue(sessionManager, fallbackCwd, fileSystem)?.let { issue ->
        throw MissingSessionCwdException(issue)
    }
}
