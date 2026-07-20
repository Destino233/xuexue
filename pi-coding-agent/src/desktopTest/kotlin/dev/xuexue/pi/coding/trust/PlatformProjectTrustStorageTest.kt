package dev.xuexue.pi.coding.trust

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlatformProjectTrustStorageTest {
    @Test
    fun persistsTrustDecisionsInAgentDirectory() {
        val root = Files.createTempDirectory("pi-trust-")
        try {
            val agentDirectory = Files.createDirectories(root.resolve("agent"))
            val projectDirectory = Files.createDirectories(root.resolve("project"))
            val store = ProjectTrustStore.create(agentDirectory.toString())

            store.set(projectDirectory.toString(), true)

            val trustFile = agentDirectory.resolve("trust.json")
            assertTrue(Files.isRegularFile(trustFile))
            assertEquals(true, ProjectTrustStore.create(agentDirectory.toString()).get(projectDirectory.toString()))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
