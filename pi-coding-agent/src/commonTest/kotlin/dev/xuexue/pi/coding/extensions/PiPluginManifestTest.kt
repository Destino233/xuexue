package dev.xuexue.pi.coding.extensions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PiPluginManifestTest {
    @Test
    fun manifestDependenciesConvertToModPackage() {
        val manifest =
            PiPluginManifestCodec.decode(
                """
                {
                  "schemaVersion": 1,
                  "id": "example.review",
                  "version": "1.2.3",
                  "apiVersion": "1",
                  "runtime": "jvm",
                  "entrypoint": "example.ReviewMod",
                  "dependencies": ["example.api@>=1.0.0"],
                  "permissions": ["workspace.read"],
                  "contributes": ["tool", "panel"]
                }
                """.trimIndent(),
            )

        val packageInfo = manifest.toModPackage("/mods/review.jar")

        assertEquals(ModId("example.review"), packageInfo.id)
        assertEquals(ModVersion("1.2.3"), packageInfo.version)
        assertEquals(ModId("example.api"), packageInfo.dependencies.single().id)
        assertTrue(packageInfo.permissions.contains("workspace.read"))
        assertTrue(packageInfo.contributes.contains("panel"))
        assertEquals("/mods/review.jar", packageInfo.sourcePath)
    }
}
