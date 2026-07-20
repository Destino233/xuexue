package dev.xuexue.pi.coding.packages

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PiPackageModelsTest {
    @Test
    fun parsesSupportedSourcesAndKeepsGitRefOutOfIdentity() {
        val git = PiPackageSourceParser.parse("git:github.com/example/tools@v1")
        assertEquals(PiPackageSourceType.GIT, git.type)
        assertEquals("github.com/example/tools", git.location)
        assertEquals("v1", git.ref)
        assertEquals(
            git.identity,
            PiPackageSourceParser.parse("git:github.com/example/tools@main").identity,
        )

        val maven = PiPackageSourceParser.parse("maven:dev.example:tools:1.2.3")
        assertEquals("dev.example:tools", maven.location)
        assertEquals("1.2.3", maven.version)
    }

    @Test
    fun decodesPiPackageManifestAndSettingsFilters() {
        val manifest =
            PiPackageManifestCodec.decode(
                """
                {
                  "name": "review-tools",
                  "version": "2.0.0",
                  "pi": {
                    "extensions": ["extensions", "plugin.jar"],
                    "skills": ["skills"],
                    "prompts": ["prompts/review.md"]
                  }
                }
                """.trimIndent(),
            )
        assertEquals("review-tools", manifest.id)
        assertEquals(listOf("extensions", "plugin.jar"), manifest.extensions)
        assertEquals(listOf("skills"), manifest.skills)

        val spec =
            PiPackageManifestCodec.decodeSettingsSpec(
                Json.parseToJsonElement(
                    """{"source":"./pkg","autoload":false,"extensions":["+a.jar"]}""",
                ),
            )
        assertEquals(false, spec?.autoload)
        assertEquals(listOf("+a.jar"), spec?.extensions)
    }

    @Test
    fun rejectsMalformedMavenCoordinates() {
        assertFailsWith<IllegalArgumentException> {
            PiPackageSourceParser.parse("maven:only:two")
        }
    }
}
