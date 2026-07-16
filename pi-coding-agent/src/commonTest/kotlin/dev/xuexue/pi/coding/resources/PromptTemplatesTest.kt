package dev.xuexue.pi.coding.resources

import kotlin.test.Test
import kotlin.test.assertEquals

class PromptTemplatesTest {
    @Test
    fun parsesQuotedCommandArgumentsLikePi() {
        assertEquals(
            listOf("one", "two words", "three words", "four"),
            parseCommandArgs("one 'two words' \"three words\" four"),
        )
    }

    @Test
    fun substitutesEachSupportedArgumentFormWithoutRecursion() {
        val template =
            "first=${'$'}1; all=${'$'}@; named=${'$'}ARGUMENTS; default=${'$'}{4:-fallback}; " +
                "slice=${'$'}{@:2:2}"

        assertEquals(
            "first=one; all=one two \$1; named=one two \$1; default=fallback; slice=two \$1",
            substitutePromptTemplateArguments(template, listOf("one", "two", "\$1")),
        )
    }

    @Test
    fun unknownTemplateLeavesInputUntouchedAndFirstDuplicateWins() {
        val templates = listOf(template("review", "first"), template("review", "second"))

        assertEquals("first", expandPromptTemplate("/review", templates))
        assertEquals("/missing hello", expandPromptTemplate("/missing hello", templates))
    }

    @Test
    fun formatsOnlyModelVisibleSkillsWithXmlEscaping() {
        val shown = skill("lint", "Use <strict> & review", "/skills/lint/SKILL.md")
        val hidden = skill("manual", "Manual", "/skills/manual/SKILL.md", disabled = true)

        val prompt = formatSkillsForPrompt(listOf(shown, hidden))

        assertEquals(true, prompt.contains("<name>lint</name>"))
        assertEquals(true, prompt.contains("Use &lt;strict&gt; &amp; review"))
        assertEquals(false, prompt.contains("manual"))
    }

    private fun template(
        name: String,
        content: String,
    ) = PromptTemplate(
        name = name,
        description = name,
        content = content,
        sourceInfo = source("/prompts/$name.md"),
        filePath = "/prompts/$name.md",
    )

    private fun skill(
        name: String,
        description: String,
        path: String,
        disabled: Boolean = false,
    ) = Skill(
        name = name,
        description = description,
        filePath = path,
        baseDirectory = path.substringBeforeLast('/'),
        sourceInfo = source(path),
        disableModelInvocation = disabled,
    )

    private fun source(path: String) =
        ResourceSourceInfo(
            path = path,
            source = "local",
            scope = ResourceScope.USER,
        )
}
