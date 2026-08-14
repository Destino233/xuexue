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
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
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
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(kotlin.io.path.ExperimentalPathApi::class)
// Keep one fixture class so every Pi tool test shares the same authorized workspace setup.
@Suppress("TooManyFunctions")
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
            val output = ByteArrayOutputStream()
            check(ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", output))
            root.resolve("pixel.png").writeBytes(output.toByteArray())
            val result = execute("read", buildJsonObject { put("path", "pixel.png") })
            assertIs<ImageContent>(result.content[1])
            assertEquals("image/png", (result.content[1] as ImageContent).mimeType)
        }

    @Test
    fun readConvertsBmpToPngAndReportsConversionHint() =
        runTest {
            val output = ByteArrayOutputStream()
            check(ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "bmp", output))
            root.resolve("pixel.bmp").writeBytes(output.toByteArray())
            val result = execute("read", buildJsonObject { put("path", "pixel.bmp") })
            assertContains(assertIs<TextContent>(result.content.first()).text, "converted from image/bmp to image/png")
            assertEquals("image/png", assertIs<ImageContent>(result.content[1]).mimeType)
        }

    @Test
    fun readRejectsJpegLsfVariantLikePi() =
        runTest {
            val bytes =
                byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xf7.toByte()) +
                    ByteArray(16) { 0x01 }
            root.resolve("variant.jpg").writeBytes(bytes)
            val result = execute("read", buildJsonObject { put("path", "variant.jpg") })
            assertIs<TextContent>(result.content.single())
        }

    @Test
    fun readRejectsFakeBmpWithoutValidDibHeaderLikePi() =
        runTest {
            root.resolve("fake.bmp").writeBytes(byteArrayOf('B'.code.toByte(), 'M'.code.toByte()) + ByteArray(20))
            val result = execute("read", buildJsonObject { put("path", "fake.bmp") })
            assertIs<TextContent>(result.content.single())
        }

    @Test
    fun readLongFirstLineUsesTheOriginalPathInFallback() =
        runTest {
            root.resolve("long.txt").writeText("x".repeat(60 * 1024))
            val result = execute("read", buildJsonObject { put("path", "long.txt") })
            val text = assertIs<TextContent>(result.content.single()).text
            assertContains(text, "sed -n '1p' long.txt")
            assertTrue("<path>" !in text)
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
    fun findScopesNestedGitignoreRulesLikePiRegression3303() =
        runTest {
            root.resolve("a/deep").createDirectories()
            root.resolve("b").createDirectories()
            root.resolve("a/.gitignore").writeText("ignored.txt\n")
            root.resolve("a/deep/.gitignore").writeText("secret.txt\n")
            listOf(
                "a/ignored.txt",
                "a/kept.txt",
                "a/deep/ignored.txt",
                "a/deep/secret.txt",
                "a/deep/kept.txt",
                "b/ignored.txt",
                "b/kept.txt",
                "root.txt",
            ).forEach { relative -> root.resolve(relative).writeText(relative) }

            val result = execute("find", buildJsonObject { put("pattern", "**/*.txt") })
            val output = assertIs<TextContent>(result.content.single()).text
            assertEquals(
                setOf("a/deep/kept.txt", "a/kept.txt", "b/ignored.txt", "b/kept.txt", "root.txt"),
                output.lineSequence().filter(String::isNotBlank).toSet(),
            )
        }

    @Test
    fun findHonorsNegationAndNestedGitRepositoryBoundaries() =
        runTest {
            root.resolve(".git").createDirectories()
            root.resolve("nested/.git").createDirectories()
            root.resolve("nested").createDirectories()
            root.resolve(".gitignore").writeText("*.generated\n!keep.generated\nrepo-hidden.txt\n")
            root.resolve("drop.generated").writeText("drop")
            root.resolve("keep.generated").writeText("keep")
            root.resolve("nested/drop.generated").writeText("nested drop")
            root.resolve("nested/keep.generated").writeText("nested keep")
            root.resolve("repo-hidden.txt").writeText("hidden by parent repository")
            root.resolve("nested/repo-hidden.txt").writeText("visible in nested repository")

            val result = execute("find", buildJsonObject { put("pattern", "**") })
            val output = assertIs<TextContent>(result.content.single()).text
            assertContains(output, "keep.generated")
            assertContains(output, "nested/keep.generated")
            assertContains(output, "nested/repo-hidden.txt")
            assertTrue(output.lineSequence().none { it == "drop.generated" })
            assertContains(output, "nested/drop.generated")
            assertTrue("\nrepo-hidden.txt" !in "\n$output")
            assertTrue(".git/" !in output)
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
    fun grepTruncatesLongMatchLinesWithPiSuffix() =
        runTest {
            root.resolve("long.txt").writeText("needle " + "x".repeat(600) + "\n")
            val result = execute("grep", buildJsonObject { put("pattern", "needle") })
            val output = assertIs<TextContent>(result.content.single()).text
            val matchLine = output.lineSequence().first { it.startsWith("long.txt:1: ") }
            assertEquals("long.txt:1: " + "needle ".length.let { "needle " + "x".repeat(500 - it) } + "... [truncated]", matchLine)
            assertContains(output, "Some lines truncated to 500 chars")
            assertEquals(
                true,
                result.details.jsonObject["linesTruncated"]
                    ?.toString()
                    ?.toBoolean(),
            )
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
    fun editPreservesBomAndCrLfWhileMatchingNormalizedText() =
        runTest {
            root.resolve("crlf.txt").writeBytes("\uFEFFalpha\r\nbeta\r\n".toByteArray(StandardCharsets.UTF_8))
            execute(
                "edit",
                buildJsonObject {
                    put("path", "crlf.txt")
                    putJsonArray("edits") {
                        add(
                            buildJsonObject {
                                put("oldText", "alpha\n")
                                put("newText", "ALPHA\n")
                            },
                        )
                    }
                },
            )
            val bytes = Files.readAllBytes(root.resolve("crlf.txt"))
            val content = bytes.toString(StandardCharsets.UTF_8)
            assertTrue(content.startsWith("\uFEFF"))
            assertContains(content, "ALPHA\r\nbeta\r\n")
        }

    @Test
    fun editUsesPiFuzzyMatchingForSmartQuotesAndTrailingWhitespace() =
        runTest {
            root.resolve("fuzzy.txt").writeText("say “hello”   \n")
            execute(
                "edit",
                buildJsonObject {
                    put("path", "fuzzy.txt")
                    putJsonArray("edits") {
                        add(
                            buildJsonObject {
                                put("oldText", "say \"hello\"\n")
                                put("newText", "say hi\n")
                            },
                        )
                    }
                },
            )
            assertEquals("say hi\n", root.resolve("fuzzy.txt").readText())
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

    @Test
    fun bashEmitsInitialAndCoalescedStreamingUpdates() =
        runTest {
            val updates = CopyOnWriteArrayList<AgentToolResult>()
            val result =
                requireNotNull(tools["bash"]).execute(
                    "test-bash-updates",
                    buildJsonObject { put("command", "seq 5000") },
                    null,
                ) { update -> updates += update }
            assertTrue(updates.isNotEmpty())
            assertTrue(updates.first().content.isEmpty())
            assertTrue(updates.size < 100)
            assertContains(assertIs<TextContent>(result.content.single()).text, "4999")
        }

    @Test
    fun bashPersistsFullOutputForLineTruncation() =
        runTest {
            val result = execute("bash", buildJsonObject { put("command", "seq 3000") })
            val text = assertIs<TextContent>(result.content.single()).text
            assertContains(text, "Showing lines 1001-3000 of 3000")
            val path = assertNotNull(result.details.jsonObject["fullOutputPath"])
            val fullOutput = Files.readString(Path.of(path.jsonPrimitive.content))
            assertContains(fullOutput, "1\n2\n3")
            assertContains(fullOutput, "2998\n2999\n3000")
        }

    @Test
    fun bashTimeoutIncludesOutputCollectedBeforeCancellation() =
        runTest {
            val timeoutSeconds = if (isWindows()) 1.0 else 0.1
            val error =
                assertFailsWith<IllegalStateException> {
                    execute(
                        "bash",
                        buildJsonObject {
                            put("command", "printf 'before\\n'; sleep 2")
                            put("timeout", timeoutSeconds)
                        },
                    )
                }
            assertContains(error.message.orEmpty(), "before")
            assertContains(error.message.orEmpty(), "Command timed out after $timeoutSeconds seconds")
        }

    private suspend fun execute(
        name: String,
        arguments: JsonObject,
    ): AgentToolResult {
        val tool = requireNotNull(tools[name])
        return tool.execute("test-$name", arguments, null, null)
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").orEmpty().contains("win", ignoreCase = true)
}
