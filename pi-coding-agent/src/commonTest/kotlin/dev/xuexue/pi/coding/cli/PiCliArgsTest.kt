package dev.xuexue.pi.coding.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PiCliArgsTest {
    @Test
    fun parsesVersionAndHelpFlags() {
        val version = parsePiArgs(listOf("--version"))
        assertTrue(version.version)
        val short = parsePiArgs(listOf("-v"))
        assertTrue(short.version)
        val help = parsePiArgs(listOf("--help"))
        assertTrue(help.help)
        val combined = parsePiArgs(listOf("--version", "--help", "some message"))
        assertTrue(combined.version)
        assertTrue(combined.help)
        assertEquals(listOf("some message"), combined.messages)
    }

    @Test
    fun parsesPrintFlagAndConsumesFollowingPrompt() {
        val result = parsePiArgs(listOf("--print"))
        assertTrue(result.print)
        val short = parsePiArgs(listOf("-p"))
        assertTrue(short.print)
        val prompt = "---\ntitle: hello\n---\nSay hi."
        val withPrompt = parsePiArgs(listOf("-p", prompt))
        assertTrue(withPrompt.print)
        assertEquals(listOf(prompt), withPrompt.messages)
        assertEquals(emptyMap(), withPrompt.unknownFlags)
        val options = parsePiArgs(listOf("-p", "--provider", "openai", "Say hi."))
        assertTrue(options.print)
        assertEquals("openai", options.provider)
        assertEquals(listOf("Say hi."), options.messages)
    }

    @Test
    fun parsesFlagsWithValues() {
        val result = parsePiArgs(listOf("--provider", "openai", "--model", "gpt-4o", "--api-key", "sk-test"))
        assertEquals("openai", result.provider)
        assertEquals("gpt-4o", result.model)
        assertEquals("sk-test", result.apiKey)
        val appended = parsePiArgs(listOf("--append-system-prompt", "A", "--append-system-prompt", "B"))
        assertEquals(listOf("A", "B"), appended.appendSystemPrompt)
        assertEquals(PiCliMode.JSON, parsePiArgs(listOf("--mode", "json")).mode)
        assertEquals(PiCliMode.RPC, parsePiArgs(listOf("--mode", "rpc")).mode)
    }

    @Test
    fun parsesCommaSeparatedLists() {
        val models = parsePiArgs(listOf("--models", "a, b ,c"))
        assertEquals(listOf("a", "b", "c"), models.models)
        val tools = parsePiArgs(listOf("--tools", "read,grep"))
        assertEquals(listOf("read", "grep"), tools.tools)
        val excluded = parsePiArgs(listOf("--exclude-tools", "ask_question"))
        assertEquals(listOf("ask_question"), excluded.excludeTools)
    }

    @Test
    fun validatesThinkingLevel() {
        val result = parsePiArgs(listOf("--thinking", "high"))
        assertEquals("high", result.thinking)
        assertEquals(emptyList(), result.diagnostics)
        val invalid = parsePiArgs(listOf("--thinking", "ultra"))
        assertEquals(null, invalid.thinking)
        assertTrue(invalid.diagnostics.any { it.type == "warning" && "ultra" in it.message })
    }

    @Test
    fun capturesUnknownFlags() {
        val result = parsePiArgs(listOf("--plan", "value", "--flag", "--eq=1", "message"))
        assertEquals("value", result.unknownFlags["plan"])
        assertEquals("true", result.unknownFlags["flag"])
        assertEquals("1", result.unknownFlags["eq"])
        assertEquals(listOf("message"), result.messages)
    }

    @Test
    fun collectsFileArgsAndMessages() {
        val result = parsePiArgs(listOf("@prompt.md", "hello world", "--no-session"))
        assertEquals(listOf("prompt.md"), result.fileArgs)
        assertEquals(listOf("hello world"), result.messages)
        assertTrue(result.noSession)
    }

    @Test
    fun parsesSessionAndTrustFlags() {
        val result =
            parsePiArgs(listOf("--session", "s.jsonl", "--fork", "f", "--session-dir", "dir", "--approve"))
        assertEquals("s.jsonl", result.session)
        assertEquals("f", result.fork)
        assertEquals("dir", result.sessionDir)
        assertEquals(true, result.projectTrustOverride)
        assertEquals(false, parsePiArgs(listOf("--no-approve")).projectTrustOverride)
    }

    @Test
    fun reportsUnknownSingleDashOptions() {
        val result = parsePiArgs(listOf("-x"))
        assertTrue(result.diagnostics.any { it.type == "error" && "Unknown option" in it.message })
    }

    @Test
    fun parsesListModelsWithOptionalPattern() {
        assertEquals("", parsePiArgs(listOf("--list-models")).listModels)
        assertEquals("sonnet", parsePiArgs(listOf("--list-models", "sonnet")).listModels)
    }

    @Test
    fun buildInitialMessageMergesStdinFileAndFirstMessage() {
        val parsed = parsePiArgs(listOf("Summarize the text given"))
        val result = buildInitialMessage(
            InitialMessageInput(messages = parsed.messages, stdinContent = "README contents\n"),
        )
        assertEquals("README contents\nSummarize the text given", result.initialMessage)
        assertEquals(emptyList(), result.remainingMessages)

        val noMessage = buildInitialMessage(InitialMessageInput(messages = emptyList(), stdinContent = "README"))
        assertEquals("README", noMessage.initialMessage)

        val combined =
            buildInitialMessage(
                InitialMessageInput(
                    messages = listOf("Explain it", "Second message"),
                    stdinContent = "stdin\n",
                    fileText = "file\n",
                ),
            )
        assertEquals("stdin\nfile\nExplain it", combined.initialMessage)
        assertEquals(listOf("Second message"), combined.remainingMessages)
    }
}