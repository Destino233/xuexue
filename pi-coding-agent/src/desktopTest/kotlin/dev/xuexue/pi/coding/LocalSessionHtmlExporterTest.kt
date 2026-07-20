package dev.xuexue.pi.coding

import dev.xuexue.pi.ai.AssistantMessage
import dev.xuexue.pi.ai.ImageContent
import dev.xuexue.pi.ai.TextContent
import dev.xuexue.pi.ai.ThinkingContent
import dev.xuexue.pi.ai.ToolCall
import dev.xuexue.pi.ai.ToolResultMessage
import dev.xuexue.pi.ai.UserMessage
import dev.xuexue.pi.ai.UserText
import dev.xuexue.pi.coding.session.SessionManager
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalSessionHtmlExporterTest {
    @Test
    fun writesEscapedTranscriptToRequestedPath() =
        runTest {
            val root = Files.createTempDirectory("pi-html-export-")
            try {
                val manager = SessionManager.create(root.toString(), root.resolve("sessions").toString())
                val harness = AgentSessionHarness(sessionManager = manager)
                harness.session.prompt("<script>\nkeep whitespace")
                val output = root.resolve("transcript.html")

                val actual = LocalSessionHtmlExporter().export(harness.session, output.toString())

                assertEquals(output.toAbsolutePath().normalize().toString(), actual)
                val html = Files.readString(output)
                assertTrue("&lt;script&gt;" in html)
                assertTrue("keep whitespace" in html)
                assertTrue("<script>" !in html)
            } finally {
                Files.walk(root).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                }
            }
        }

    @Test
    fun rendersStructuredMessagesSkillBlocksImagesBranchesAndSafeMarkdown() =
        runTest {
            val manager = SessionManager.inMemory("/workspace")
            val harness = AgentSessionHarness(sessionManager = manager)
            manager.appendSessionInfo("Review <script>")
            val rootId =
                manager.appendMessage(
                    UserMessage(
                        UserText(
                            """
                            <skill name="review" location="/tmp/SKILL.md">
                            Use <tool> safely
                            </skill>

                            Please inspect <script>.
                            """.trimIndent(),
                        ),
                        timestamp = 1,
                    ),
                )
            manager.appendMessage(
                AssistantMessage(
                    content =
                        listOf(
                            ThinkingContent("reasoning <hidden>"),
                            ToolCall(
                                id = "call-<script>",
                                name = "read",
                                arguments = buildJsonObject { put("path", "/tmp/<unsafe>") },
                            ),
                            TextContent("Done with `read`"),
                        ),
                    api = "openai-responses",
                    provider = "openai",
                    model = "mock",
                    usage = testUsage(),
                    stopReason = dev.xuexue.pi.ai.StopReason.TOOL_USE,
                    timestamp = 2,
                ),
            )
            manager.appendMessage(
                ToolResultMessage(
                    toolCallId = "call-<script>",
                    toolName = "read",
                    content = listOf(TextContent("<tool-result>"), ImageContent(ONE_PIXEL_GIF, "image/gif")),
                    details = buildJsonObject { put("detail", "<unsafe>") },
                    isError = false,
                    timestamp = 3,
                ),
            )
            manager.branch(rootId)
            manager.appendMessage(
                UserMessage(
                    UserText("branched [safe](https://example.com/?q=1&x=2) [bad](javascript:alert(1)) `code`"),
                    timestamp = 4,
                ),
            )

            val html = renderSessionHtml(harness.session)

            assertTrue("Session tree" in html)
            assertTrue("Review &lt;script&gt;" in html)
            assertTrue("Thinking" in html)
            assertTrue("data-tool-call-id=\"call-&lt;script&gt;\"" in html)
            assertTrue("&lt;tool-result&gt;" in html)
            assertTrue("data:image/gif;base64,$ONE_PIXEL_GIF" in html)
            assertTrue("Skill: review" in html)
            assertTrue("/tmp/SKILL.md" in html)
            assertTrue("data-parent-id=\"$rootId\"" in html)
            assertTrue("<a href=\"https://example.com/?q=1&amp;x=2\">safe</a>" in html)
            assertTrue("href=\"javascript:" !in html)
            assertTrue("<code>code</code>" in html)
            assertTrue("<script>" !in html)
            assertTrue("<unsafe>" !in html)
        }

    private companion object {
        const val ONE_PIXEL_GIF = "R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw=="
    }
}
