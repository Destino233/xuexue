package dev.xuexue.pi.coding.resources

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SystemPromptTest {
    @Test
    fun customPromptAppendsResourcesThenDateAndCwdInPiOrder() {
        val prompt =
            buildSystemPrompt(
                SystemPromptOptions(
                    customPrompt = "base prompt",
                    appendSystemPrompt = "append prompt",
                    selectedTools = listOf("read"),
                    cwd = "C:\\work\\project",
                    currentDate = "2026-07-16",
                    documentation = docs(),
                    contextFiles = listOf(ContextFile("/work/AGENTS.md", "follow instructions")),
                    skills = listOf(skill()),
                ),
            )

        assertTrue(prompt.indexOf("base prompt") < prompt.indexOf("append prompt"))
        assertTrue(prompt.indexOf("append prompt") < prompt.indexOf("<project_context>"))
        assertTrue(prompt.indexOf("<project_context>") < prompt.indexOf("<available_skills>"))
        assertTrue(prompt.endsWith("Current date: 2026-07-16\nCurrent working directory: C:/work/project"))
    }

    @Test
    fun defaultPromptShowsOnlySnippetedToolsAndDeduplicatesGuidelines() {
        val prompt =
            buildSystemPrompt(
                SystemPromptOptions(
                    selectedTools = listOf("bash", "read", "grep"),
                    toolSnippets = mapOf("read" to "Read a file"),
                    promptGuidelines = listOf("Custom rule", "Custom rule", "  "),
                    cwd = "/workspace",
                    currentDate = "2026-07-16",
                    documentation = docs(),
                ),
            )

        assertTrue(prompt.contains("- read: Read a file"))
        assertFalse(prompt.contains("- bash:"))
        assertTrue(prompt.contains("- Custom rule"))
        assertTrue(prompt.indexOf("- Custom rule") == prompt.lastIndexOf("- Custom rule"))
        assertFalse(prompt.contains("Use bash for file operations"))
    }

    @Test
    fun customPromptOmitsSkillSectionWhenReadIsUnavailable() {
        val prompt =
            buildSystemPrompt(
                SystemPromptOptions(
                    customPrompt = "base",
                    selectedTools = listOf("bash"),
                    cwd = "/workspace",
                    currentDate = "2026-07-16",
                    documentation = docs(),
                    skills = listOf(skill()),
                ),
            )

        assertFalse(prompt.contains("<available_skills>"))
    }

    private fun docs() = SystemPromptDocumentation("/README.md", "/docs", "/examples")

    private fun skill() =
        Skill(
            name = "review",
            description = "Review code",
            filePath = "/skills/review/SKILL.md",
            baseDirectory = "/skills/review",
            sourceInfo =
                ResourceSourceInfo(
                    path = "/skills/review/SKILL.md",
                    source = "local",
                    scope = ResourceScope.USER,
                ),
            disableModelInvocation = false,
        )
}
