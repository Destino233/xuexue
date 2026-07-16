package dev.xuexue.pi.coding.resources

/** Port of Pi's shell-like prompt-template argument parser. */
fun parseCommandArgs(arguments: String): List<String> {
    val parsed = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null

    arguments.forEach { character ->
        when {
            quote != null && character == quote -> {
                quote = null
            }

            quote != null -> {
                current.append(character)
            }

            character == '\'' || character == '"' -> {
                quote = character
            }

            character.isWhitespace() -> {
                if (current.isNotEmpty()) {
                    parsed += current.toString()
                    current.clear()
                }
            }

            else -> {
                current.append(character)
            }
        }
    }
    if (current.isNotEmpty()) parsed += current.toString()
    return parsed
}

/** Port of Pi's non-recursive $1, $@, ${N:-default}, and ${@:N[:L]} replacement rules. */
fun substitutePromptTemplateArguments(
    content: String,
    arguments: List<String>,
): String {
    val allArguments = arguments.joinToString(" ")
    return TEMPLATE_ARGUMENT_PATTERN.replace(content) { match ->
        val defaultIndex = match.groups[1]?.value
        val defaultValue = match.groups[2]?.value
        val sliceStart = match.groups[3]?.value
        val sliceLength = match.groups[4]?.value
        val simple = match.groups[5]?.value
        when {
            defaultIndex != null -> {
                val index = defaultIndex.toInt() - 1
                arguments.getOrNull(index)?.takeIf(String::isNotEmpty) ?: defaultValue.orEmpty()
            }

            sliceStart != null -> {
                val start = (sliceStart.toInt() - 1).coerceAtLeast(0)
                val sliced = arguments.drop(start)
                val selected = sliceLength?.toInt()?.let(sliced::take) ?: sliced
                selected.joinToString(" ")
            }

            simple == "ARGUMENTS" || simple == "@" -> {
                allArguments
            }

            else -> {
                arguments.getOrNull(requireNotNull(simple).toInt() - 1).orEmpty()
            }
        }
    }
}

fun expandPromptTemplate(
    text: String,
    templates: List<PromptTemplate>,
): String =
    TEMPLATE_COMMAND_PATTERN
        .matchEntire(text)
        ?.let { match ->
            templates
                .firstOrNull { it.name == match.groups[1]?.value }
                ?.let { template ->
                    substitutePromptTemplateArguments(
                        content = template.content,
                        arguments = parseCommandArgs(match.groups[2]?.value.orEmpty()),
                    )
                }
        } ?: text

fun formatSkillsForPrompt(skills: List<Skill>): String {
    val visibleSkills = skills.filterNot(Skill::disableModelInvocation)
    if (visibleSkills.isEmpty()) return ""
    return buildList {
        add("\n\nThe following skills provide specialized instructions for specific tasks.")
        add("Use the read tool to load a skill's file when the task matches its description.")
        add(
            "When a skill file references a relative path, resolve it against the skill directory " +
                "(parent of SKILL.md / dirname of the path) and use that absolute path in tool commands.",
        )
        add("")
        add("<available_skills>")
        visibleSkills.forEach { skill ->
            add("  <skill>")
            add("    <name>${escapeXml(skill.name)}</name>")
            add("    <description>${escapeXml(skill.description)}</description>")
            add("    <location>${escapeXml(skill.filePath)}</location>")
            add("  </skill>")
        }
        add("</available_skills>")
    }.joinToString("\n")
}

private fun escapeXml(value: String): String =
    value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

private val TEMPLATE_ARGUMENT_PATTERN =
    Regex("""\$\{(\d+):-([^}]*)\}|\$\{@:(\d+)(?::(\d+))?\}|\$(ARGUMENTS|@|\d+)""")

private val TEMPLATE_COMMAND_PATTERN = Regex("""^/([^\s]+)(?:\s+([\s\S]*))?$""")
