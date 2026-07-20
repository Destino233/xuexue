package dev.xuexue.pi.coding.trust

import dev.xuexue.pi.coding.resources.PlatformResourceFileSystem
import dev.xuexue.pi.coding.settings.DefaultProjectTrust
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectTrustStoreTest {
    @Test
    fun storesDecisionsAndInheritsFromParentDirectories() {
        val storage = InMemoryProjectTrustStorage()
        val store = ProjectTrustStore(storage)
        val parent = PlatformResourceFileSystem.resolvePath("/tmp/trust-parent")
        val child = PlatformResourceFileSystem.join(parent, "project")

        assertNull(store.get(child))
        store.set(parent, true)
        assertTrue(store.get(child) == true)
        assertEquals(parent, store.getEntry(child)?.path)
        store.set(child, false)
        assertFalse(store.get(child) == true)
        store.set(child, null)
        assertTrue(store.get(child) == true)
    }

    @Test
    fun writesSortedJsonAndRejectsInvalidValues() {
        val storage = InMemoryProjectTrustStorage()
        val store = ProjectTrustStore(storage)
        store.setMany(
            listOf(
                ProjectTrustUpdate("/z", false),
                ProjectTrustUpdate("/a", true),
            ),
        )

        val root = Json.parseToJsonElement(requireNotNull(storage.content())).jsonObject
        assertEquals(listOf("/a", "/z"), root.keys.toList())
        assertEquals(true, root["/a"]?.jsonPrimitive?.content?.toBoolean())

        val invalidStore = ProjectTrustStore(InMemoryProjectTrustStorage("[]"))
        kotlin.test.assertFailsWith<IllegalStateException> { invalidStore.get("/tmp/project") }
    }

    @Test
    fun detectsProjectResourcesButIgnoresUserSkillsDirectory() {
        val fileSystem = TestTrustFileSystem()
        val home = "/home/user"
        fileSystem.directories += "$home/.agents/skills"
        fileSystem.directories += "/workspace"
        assertFalse(hasTrustRequiringProjectResources("$home/project", home, fileSystem))

        fileSystem.files += "/workspace/.xuexue/pi/settings.json"
        assertTrue(hasTrustRequiringProjectResources("/workspace", home, fileSystem))
    }

    @Test
    fun resolvesOverrideSavedAndDefaultBeforeRequestingUi() =
        kotlinx.coroutines.test.runTest {
            val fileSystem = TestTrustFileSystem(files = mutableSetOf("/workspace/.xuexue/pi/extensions"))
            val store = ProjectTrustStore(InMemoryProjectTrustStorage(), fileSystem)
            assertTrue(
                resolveProjectTrusted(
                    ProjectTrustResolutionOptions(
                        cwd = "/workspace",
                        trustStore = store,
                        trustOverride = true,
                        interaction = ProjectTrustInteraction { _, _ -> error("UI should not be requested") },
                        fileSystem = fileSystem,
                    ),
                ),
            )

            store.set("/workspace", false)
            assertFalse(
                resolveProjectTrusted(
                    ProjectTrustResolutionOptions(
                        cwd = "/workspace",
                        trustStore = store,
                        interaction = ProjectTrustInteraction { _, _ -> error("UI should not be requested") },
                        fileSystem = fileSystem,
                    ),
                ),
            )
            store.set("/workspace", null)
            assertTrue(
                resolveProjectTrusted(
                    ProjectTrustResolutionOptions(
                        cwd = "/workspace",
                        trustStore = store,
                        defaultProjectTrust = DefaultProjectTrust.ALWAYS,
                        interaction = ProjectTrustInteraction { _, _ -> error("UI should not be requested") },
                        fileSystem = fileSystem,
                    ),
                ),
            )
        }

    private class TestTrustFileSystem(
        val files: MutableSet<String> = mutableSetOf(),
        val directories: MutableSet<String> = mutableSetOf(),
    ) : dev.xuexue.pi.coding.resources.ResourceFileSystem {
        override fun resolvePath(
            path: String,
            baseDirectory: String?,
        ): String =
            if (path.startsWith("/")) {
                path.trimEnd('/')
            } else {
                "${baseDirectory ?: "/"}/$path".replace("//", "/")
            }

        override fun canonicalizePath(path: String): String = path.trimEnd('/').ifEmpty { "/" }

        override fun join(
            parent: String,
            child: String,
        ): String = "${parent.trimEnd('/')}/$child"

        override fun parent(path: String): String {
            val normalized = canonicalizePath(path)
            val index = normalized.lastIndexOf('/')
            return if (index <= 0) "/" else normalized.substring(0, index)
        }

        override fun exists(path: String): Boolean =
            canonicalizePath(path) in files || canonicalizePath(path) in directories

        override fun isFile(path: String): Boolean = canonicalizePath(path) in files

        override fun isDirectory(path: String): Boolean = canonicalizePath(path) in directories

        override fun readText(path: String): String = error("not used")

        override fun listDirectory(
            path: String,
        ): List<dev.xuexue.pi.coding.resources.ResourceDirectoryEntry> = emptyList()
    }
}
