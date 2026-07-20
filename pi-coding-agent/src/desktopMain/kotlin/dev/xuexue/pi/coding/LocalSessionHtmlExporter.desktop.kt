@file:Suppress("MatchingDeclarationName")

package dev.xuexue.pi.coding

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Desktop file writer for the core HTML transcript renderer. */
class LocalSessionHtmlExporter : AgentSessionHtmlExporter {
    override suspend fun export(
        session: AgentSession,
        outputPath: String?,
    ): String {
        val sessionFile =
            requireNotNull(session.sessionFile) {
                "Cannot export an in-memory session to HTML"
            }
        val path = outputPath?.let(Path::of) ?: defaultPath(sessionFile)
        val normalized = path.toAbsolutePath().normalize()
        normalized.parent?.let(Files::createDirectories)
        Files.writeString(
            normalized,
            renderSessionHtml(session),
            Charsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        return normalized.toString()
    }

    private fun defaultPath(sessionFile: String): Path {
        val fileName =
            Path
                .of(sessionFile)
                .fileName
                .toString()
                .removeSuffix(".jsonl")
        return Path.of("pi-session-$fileName.html")
    }
}
