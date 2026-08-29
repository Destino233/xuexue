package dev.xuexue.pi.coding.extensions

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PiModModelsTest {
    @Test
    fun idsAreValidatedAndLocalContributionsAreQualified() {
        val id = ModId("com.example.review")

        assertEquals("com.example.review", id.value)
        assertEquals("com.example.review:inspect", id.qualify("inspect"))
        assertEquals("other:inspect", id.qualify("other:inspect"))
        assertFailsWith<IllegalArgumentException> { ModId("Com.Example") }
        assertFailsWith<IllegalArgumentException> { id.qualify("bad name") }
    }

    @Test
    fun versionsUseSemanticOrderingAndDependenciesParse() {
        assertTrue(ModVersion("1.2.0") > ModVersion("1.1.9"))
        assertTrue(ModVersion("1.2.0") > ModVersion("1.2.0-beta.2"))
        assertTrue(ModVersion("1.2.0-beta.10") > ModVersion("1.2.0-beta.2"))

        val dependency = ModDependency.parse("com.example.api@>=1.4.0")
        assertEquals(ModId("com.example.api"), dependency.id)
        assertTrue(dependency.versionRange.accepts(ModVersion("1.5.0")))
        assertFalse(dependency.versionRange.accepts(ModVersion("1.3.9")))
    }

    @Test
    fun registrationsBelongToRunAndStaleRemovalCannotDeleteReplacement() =
        runTest {
            val diagnostics = mutableListOf<ModRunDiagnostic>()
            val first = testRun("first", diagnostics)
            val second = testRun("second", diagnostics)
            val registry = ModOwnedRegistry<String>(ModContributionKind.TOOL)

            val firstRegistration = registry.register(first, "example:tool", "first")
            assertEquals("first", registry.get("example:tool"))
            assertFailsWith<IllegalStateException> {
                registry.register(second, "example:tool", "second")
            }

            val replacement =
                registry.register(
                    owner = second,
                    id = "example:tool",
                    value = "second",
                    replace = true,
                )
            assertFalse(firstRegistration.isActive)
            assertTrue(replacement.isActive)
            assertEquals("second", registry.get("example:tool"))

            firstRegistration.unregister()
            assertEquals("second", registry.get("example:tool"))

            second.stop()
            assertFalse(replacement.isActive)
            assertNull(registry.get("example:tool"))
            assertEquals(ModRunState.STOPPED, second.state)
        }

    @Test
    fun stoppedRunsRejectNewRegistrations() =
        runTest {
            val diagnostics = mutableListOf<ModRunDiagnostic>()
            val run = testRun("stopped", diagnostics)
            run.stop()
            assertFailsWith<IllegalStateException> {
                ModOwnedRegistry<String>(ModContributionKind.COMMAND)
                    .register(run, "stopped:command", "value")
            }
        }

    private fun testRun(
        id: String,
        diagnostics: MutableList<ModRunDiagnostic>,
    ): ModRun {
        val run =
            ModRun.create(
                packageInfo =
                    ModPackage(
                        id = ModId("example.$id"),
                        version = ModVersion("1.0.0"),
                        apiVersion = PI_PLUGIN_API_VERSION,
                        runtime = PiPluginRuntime.JVM,
                    ),
                trusted = true,
                diagnostics = diagnostics,
            )
        run.beginStart()
        run.markRunning()
        return run
    }
}
