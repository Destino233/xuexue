@file:Suppress("InjectDispatcher")

package dev.xuexue.platform

import dev.xuexue.core.AgentOperationKind
import dev.xuexue.pi.agent.AgentTool
import dev.xuexue.pi.agent.AgentToolResult
import dev.xuexue.pi.ai.ImageContent
import dev.xuexue.pi.ai.TextContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class DesktopPiCodingToolsTest {
    private lateinit var root: Path
    private lateinit var tools: Map<String, AgentTool>

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("xuexue-pi-tools-")
        tools =
            DesktopPiCodingTools(
                workspaceService = WorkspaceService(),
                workspaceRoot = { root },
                authorize = { _: AgentOperationKind, _: String, _: String -> true },
                ioDispatcher = Dispatchers.IO,
            ).all().associateBy(AgentTool::name)
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun readHonorsOffsetLimitAndMacScreenshotVariant() =
        runTest {
            root.resolve("notes.txt").writeText("one\ntwo\nthree\n")
            val limited =
                execute(
                    "read",
                    buildJsonObject {
                        put("path", "notes.txt")
                        put("offset", 2)
                        put("limit", 1)
                    },
                )
            val limitedText = assertIs<TextContent>(limited.content.single()).text
            assertEquals("two\n\n[2 more lines in file. Use offset=3 to continue.]", limitedText)

            root.resolve("Capture\u202fPM.txt").writeText("screenshot")
            val variant = execute("read", buildJsonObject { put("path", "Capture PM.txt") })
            assertEquals("screenshot", assertIs<TextContent>(variant.content.single()).text)
        }

    @Test
    fun readReturnsImageContentForSupportedImageMagic() =
        runTest {
            root.resolve("pixel.png").writeBytes(
                byteArrayOf(
                    0x89.toByte(),
                    0x50,
                    0x4e,
                    0x47,
                    0x0d,
                    0x0a,
                    0x1a,
                    0x0a,
                ),
            )
            val result = execute("read", buildJsonObject { put("path", "pixel.png") })
            assertIs<ImageContent>(result.content[1])
            assertEquals("image/png", (result.content[1] as ImageContent).mimeType)
        }

    @Test
    fun findUsesGlobAndGitignoreRules() =
        runTest {
            root.resolve("src").createDirectories()
            root.resolve("src/a.kt").writeText("a")
            root.resolve("src/a.txt").writeText("a")
            root.resolve("ignored.kt").writeText("ignored")
            root.resolve("nested/no.kt").parent.createDirectories()
            root.resolve("nested/no.kt").writeText("ignored")
            root.resolve(".gitignore").writeText("ignored.kt\nnested/\n")

            val result = execute("find", buildJsonObject { put("pattern", "**/*.kt") })
            val output = assertIs<TextContent>(result.content.single()).text
            assertContains(output, "src/a.kt")
            assertTrue("ignored.kt" !in output)
            assertTrue("nested/no.kt" !in output)
        }

    @Test
    fun grepSupportsLiteralContextAndSkipsBinaryFiles() =
        runTest {
            root.resolve("text.txt").writeText("before\nNeedle\nafter\n")
            root.resolve("binary.dat").writeBytes(byteArrayOf(0, 'N'.code.toByte(), 'e'.code.toByte()))
            val result =
                execute(
                    "grep",
                    buildJsonObject {
                        put("pattern", "Needle")
                        put("literal", true)
                        put("context", 1)
                    },
                )
            val output = assertIs<TextContent>(result.content.single()).text
            assertContains(output, "text.txt:2: Needle")
            assertContains(output, "text.txt-1- before")
            assertContains(output, "text.txt-3- after")
            assertTrue("binary.dat" !in output)
        }

    @Test
    fun lsIncludesDotfilesAndReportsEntryLimit() =
        runTest {
            root.resolve(".hidden").writeText("hidden")
            root.resolve("alpha").writeText("alpha")
            root.resolve("directory").createDirectories()
            val result = execute("ls", buildJsonObject { put("limit", 2) })
            val output = assertIs<TextContent>(result.content.single()).text
            assertContains(output, "entries limit reached")
            assertEquals(
                2,
                result.details.jsonObject["entryLimitReached"]
                    ?.toString()
                    ?.toInt(),
            )
        }

    @Test
    fun editAppliesMultipleOriginalRangesAndReturnsPatchDetails() =
        runTest {
            root.resolve("edit.txt").writeText("alpha\nbeta\ngamma\n")
            val result =
                execute(
                    "edit",
                    buildJsonObject {
                        put("path", "edit.txt")
                        putJsonArray("edits") {
                            add(
                                buildJsonObject {
                                    put("oldText", "alpha")
                                    put("newText", "ALPHA")
                                },
                            )
                            add(
                                buildJsonObject {
                                    put("oldText", "gamma")
                                    put("newText", "GAMMA")
                                },
                            )
                        }
                    },
                )
            assertEquals("ALPHA\nbeta\nGAMMA\n", root.resolve("edit.txt").readText())
            assertContains(result.details.jsonObject["diff"].toString(), "GAMMA")
            assertContains(result.details.jsonObject["patch"].toString(), "+++ edit.txt")
        }

    @Test
    fun writeCreatesParentDirectories() =
        runTest {
            val result =
                execute(
                    "write",
                    buildJsonObject {
                        put("path", "nested/new.txt")
                        put("content", "created")
                    },
                )
            assertContains(assertIs<TextContent>(result.content.single()).text, "Successfully wrote")
            assertEquals("created", root.resolve("nested/new.txt").readText())
        }

    private suspend fun execute(
        name: String,
        arguments: JsonObject,
    ): AgentToolResult {
        val tool = requireNotNull(tools[name])
        return tool.execute("test-$name", arguments, null, null)
    }
}
