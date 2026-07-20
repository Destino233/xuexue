@file:Suppress("MatchingDeclarationName", "CyclomaticComplexMethod", "LongMethod", "TooManyFunctions")

package dev.xuexue.pi.coding

import dev.xuexue.pi.ai.AssistantContent
import dev.xuexue.pi.ai.AssistantMessage
import dev.xuexue.pi.ai.ImageContent
import dev.xuexue.pi.ai.TextContent
import dev.xuexue.pi.ai.ThinkingContent
import dev.xuexue.pi.ai.ToolCall
import dev.xuexue.pi.ai.ToolResultContent
import dev.xuexue.pi.ai.ToolResultMessage
import dev.xuexue.pi.ai.UserBlocks
import dev.xuexue.pi.ai.UserContent
import dev.xuexue.pi.ai.UserContentBlock
import dev.xuexue.pi.ai.UserMessage
import dev.xuexue.pi.ai.UserText
import dev.xuexue.pi.coding.session.BashExecutionMessage
import dev.xuexue.pi.coding.session.BranchSummaryEntry
import dev.xuexue.pi.coding.session.BranchSummaryMessage
import dev.xuexue.pi.coding.session.CompactionEntry
import dev.xuexue.pi.coding.session.CompactionSummaryMessage
import dev.xuexue.pi.coding.session.CustomEntry
import dev.xuexue.pi.coding.session.CustomMessage
import dev.xuexue.pi.coding.session.CustomMessageEntry
import dev.xuexue.pi.coding.session.LabelEntry
import dev.xuexue.pi.coding.session.ModelChangeEntry
import dev.xuexue.pi.coding.session.RawAgentMessage
import dev.xuexue.pi.coding.session.RawSessionEntry
import dev.xuexue.pi.coding.session.SessionEntry
import dev.xuexue.pi.coding.session.SessionInfoEntry
import dev.xuexue.pi.coding.session.SessionMessageEntry
import dev.xuexue.pi.coding.session.SessionTreeNode
import dev.xuexue.pi.coding.session.ThinkingLevelChangeEntry
import dev.xuexue.pi.coding.session.bashExecutionToText
import dev.xuexue.pi.coding.session.type
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Platform boundary for writing an exported session without making the core JVM-only. */
fun interface AgentSessionHtmlExporter {
    suspend fun export(
        session: AgentSession,
        outputPath: String?,
    ): String
}

/** Renders a safe, structured HTML transcript for RPC and desktop hosts. */
fun renderSessionHtml(session: AgentSession): String {
    val header = session.sessionManager.getHeader()
    val entries = session.sessionManager.getEntries()
    val activeLeafId = session.sessionManager.getLeafId()
    val body =
        if (entries.isEmpty()) {
            "<p class=\"empty\">No messages yet.</p>"
        } else {
            entries.joinToString("") { entry -> renderEntry(entry, activeLeafId) }
        }
    val tree = renderTree(session.sessionManager.getTree(), activeLeafId)
    val model = session.model
    val systemPrompt = session.systemPrompt.trim()
    val systemPromptBlock =
        if (systemPrompt.isEmpty()) {
            ""
        } else {
            """
            <details class="system-prompt">
              <summary>System prompt</summary>
              <div class="rich-text">${renderRichText(systemPrompt)}</div>
            </details>
            """.trimIndent()
        }
    return """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>${escapeHtml(session.sessionName ?: "Pi Session")}</title>
          <style>
            :root {
              color-scheme: light dark;
              --page-bg: #f3f5f7;
              --surface: #ffffff;
              --surface-muted: #edf1f4;
              --surface-code: #f7f8fa;
              --text: #1d2329;
              --muted: #68727d;
              --border: #d7dde3;
              --accent: #2563a8;
              --user-bg: #e8f1fc;
              --assistant-accent: #1c7c54;
              --tool-accent: #a06400;
              --event-accent: #75612b;
              --error: #b42318;
              font-family: system-ui, -apple-system, sans-serif;
            }
            * { box-sizing: border-box; }
            body { margin: 0; background: var(--page-bg); color: var(--text); }
            .export-shell { display: flex; min-height: 100vh; }
            .session-tree { width: 290px; flex: 0 0 290px; padding: 20px 12px;
              background: var(--surface); border-right: 1px solid var(--border); }
            .session-tree h2 { margin: 0 8px 12px; font-size: .82rem; color: var(--muted);
              letter-spacing: .06em; text-transform: uppercase; }
            .session-tree ul { list-style: none; margin: 0; padding: 0; }
            .session-tree li { margin: 1px 0; }
            .session-tree a { display: block; padding: 5px 7px; color: var(--muted); border-radius: 4px;
              font: .76rem/1.35 ui-monospace, SFMono-Regular, Menlo, monospace; text-decoration: none; }
            .session-tree a:hover, .session-tree li.active > a { background: var(--surface-muted); color: var(--text); }
            .session-tree li.active > a { box-shadow: inset 2px 0 var(--accent); }
            .session-tree .tree-event { color: var(--event-accent); }
            .session-main { width: min(980px, 100%); margin: 0 auto; padding: 28px 24px 64px; }
            .session-header { margin-bottom: 20px; padding: 18px 20px; background: var(--surface);
              border: 1px solid var(--border); border-radius: 6px; }
            .session-header h1 { margin: 0 0 14px; font-size: 1.35rem; }
            .metadata { display: grid; grid-template-columns: max-content minmax(0, 1fr); gap: 4px 14px;
              color: var(--muted); font-size: .78rem; }
            .metadata strong { color: var(--text); font-weight: 600; }
            .system-prompt { margin-top: 16px; border-top: 1px solid var(--border); padding-top: 12px; }
            .system-prompt summary { color: var(--muted); cursor: pointer; font-size: .78rem; }
            .messages { display: flex; flex-direction: column; gap: 12px; }
            .entry { scroll-margin-top: 16px; background: var(--surface); border: 1px solid var(--border);
              border-left: 3px solid var(--muted); border-radius: 6px; overflow: hidden; }
            .entry.active { box-shadow: 0 0 0 2px color-mix(in srgb, var(--accent) 22%, transparent); }
            .entry.user { border-left-color: var(--accent); }
            .entry.assistant { border-left-color: var(--assistant-accent); }
            .entry.tool { border-left-color: var(--tool-accent); }
            .entry.event { border-left-color: var(--event-accent); }
            .entry.error { border-left-color: var(--error); }
            .entry-header { display: flex; align-items: baseline; justify-content: space-between; gap: 12px;
              padding: 8px 12px; background: var(--surface-muted); color: var(--muted); font-size: .73rem;
              font-weight: 650; letter-spacing: .04em; text-transform: uppercase; }
            .entry-header time { font-size: .68rem; font-weight: 400; letter-spacing: 0; text-transform: none; }
            .entry-body { padding: 12px 14px; min-width: 0; }
            .rich-text { line-height: 1.55; overflow-wrap: anywhere; }
            .rich-text p { margin: 0 0 8px; }
            .rich-text p:last-child { margin-bottom: 0; }
            .rich-text h4 { margin: 0 0 8px; font-size: .95rem; }
            .rich-text .line-break { display: block; height: 8px; }
            pre, code { font: .84rem/1.5 ui-monospace, SFMono-Regular, Menlo, monospace; }
            pre { margin: 8px 0 0; padding: 10px 12px; background: var(--surface-code); border: 1px solid var(--border);
              border-radius: 4px; overflow-x: auto; white-space: pre-wrap; overflow-wrap: anywhere; }
            .content-block + .content-block { margin-top: 10px; }
            details.block { margin: 8px 0; border: 1px solid var(--border); border-radius: 4px; background: var(--surface-muted); }
            details.block summary { padding: 8px 10px; cursor: pointer; color: var(--muted); font-size: .78rem; }
            details.block > .details-body { padding: 0 10px 10px; }
            .skill-block { border-color: var(--assistant-accent); }
            .skill-block summary { color: var(--assistant-accent); }
            .tool-result { border-left: 2px solid var(--tool-accent); padding-left: 10px; }
            .tool-result.error { border-left-color: var(--error); color: var(--error); }
            .tool-name { color: var(--tool-accent); font-size: .78rem; font-weight: 650; }
            .image-content { display: block; max-width: min(100%, 640px); max-height: 480px; margin: 8px 0;
              border: 1px solid var(--border); border-radius: 4px; }
            .markdown-image { display: block; max-width: min(100%, 640px); max-height: 480px; margin: 8px 0;
              border: 1px solid var(--border); border-radius: 4px; }
            .image-placeholder { color: var(--muted); font-size: .78rem; }
            .empty { color: var(--muted); padding: 24px; text-align: center; }
            @media (prefers-color-scheme: dark) {
              :root { --page-bg: #171a1e; --surface: #20252b; --surface-muted: #2a3037; --surface-code: #181c21;
                --text: #e8ebee; --muted: #a8b1bb; --border: #3b444e; --accent: #7eb6f2; --user-bg: #273b54; }
            }
            @media (max-width: 760px) {
              .export-shell { display: block; }
              .session-tree { width: auto; border-right: 0; border-bottom: 1px solid var(--border); }
              .session-tree ul { max-height: 220px; overflow: auto; }
              .session-main { padding: 16px 12px 40px; }
            }
            @media print {
              .session-tree { display: none; }
              .session-main { width: 100%; max-width: none; padding: 0; }
              .entry { break-inside: avoid; }
            }
          </style>
        </head>
        <body data-session-id="${escapeHtml(header.id)}" data-leaf-id="${escapeHtml(activeLeafId.orEmpty())}">
          <div class="export-shell">
            <aside class="session-tree" aria-label="Session tree">
              <h2>Session tree</h2>
              $tree
            </aside>
            <main class="session-main">
              <header class="session-header">
                <h1>${escapeHtml(session.sessionName ?: "Pi Session")}</h1>
                <div class="metadata">
                  <span>Session</span><strong>${escapeHtml(header.id)}</strong>
                  <span>Working directory</span><strong>${escapeHtml(header.cwd)}</strong>
                  <span>Created</span><strong>${escapeHtml(header.timestamp)}</strong>
                  <span>Model</span><strong>${escapeHtml("${model.provider}/${model.id}")}</strong>
                  <span>Thinking</span><strong>${escapeHtml(session.thinkingLevel.name.lowercase())}</strong>
                  <span>Leaf</span><strong>${escapeHtml(activeLeafId ?: "root")}</strong>
                </div>
                $systemPromptBlock
              </header>
              <section class="messages" aria-label="Session messages">
                $body
              </section>
            </main>
          </div>
        </body>
        </html>
        """.trimIndent()
}

private fun renderTree(
    roots: List<SessionTreeNode>,
    activeLeafId: String?,
): String {
    if (roots.isEmpty()) return "<p class=\"empty\">No entries.</p>"

    fun renderNodes(
        nodes: List<SessionTreeNode>,
        depth: Int,
    ): String =
        nodes.joinToString("") { node ->
            val entry = node.entry
            val active = if (entry.id == activeLeafId) " active" else ""
            val eventClass = if (entry !is SessionMessageEntry) " tree-event" else ""
            val skillClass = if (isSkillEntry(entry)) " tree-role-skill" else ""
            val id = htmlId(entry.id)
            val padding = depth * 14
            """
            <li class="$active$eventClass$skillClass" data-entry-id="${escapeHtml(entry.id)}">
              <a href="#entry-$id" style="padding-left:${padding + 7}px">${escapeHtml(treeLabel(entry))}</a>
              ${if (node.children.isEmpty()) "" else "<ul>${renderNodes(node.children, depth + 1)}</ul>"}
            </li>
            """.trimIndent()
        }
    return "<ul>${renderNodes(roots, 0)}</ul>"
}

private fun treeLabel(entry: SessionEntry): String =
    when (entry) {
        is SessionMessageEntry -> {
            val role =
                when (entry.message) {
                    is UserMessage -> "user"
                    is AssistantMessage -> "assistant"
                    is ToolResultMessage -> "tool"
                    is BashExecutionMessage -> "bash"
                    else -> "message"
                }
            "$role: ${messagePreview(entry.message)}"
        }

        is ThinkingLevelChangeEntry -> {
            "thinking: ${entry.thinkingLevel}"
        }

        is ModelChangeEntry -> {
            "model: ${entry.provider}/${entry.modelId}"
        }

        is CompactionEntry -> {
            "compaction: ${textPreview(entry.summary)}"
        }

        is BranchSummaryEntry -> {
            "branch: ${textPreview(entry.summary)}"
        }

        is CustomEntry -> {
            "custom: ${entry.customType}"
        }

        is CustomMessageEntry -> {
            "custom: ${entry.customType}"
        }

        is LabelEntry -> {
            "label: ${entry.label.orEmpty()}"
        }

        is SessionInfoEntry -> {
            "session: ${entry.name.orEmpty()}"
        }

        is RawSessionEntry -> {
            entry.rawType
        }
    }

private fun renderEntry(
    entry: SessionEntry,
    activeLeafId: String?,
): String {
    val role = entryRole(entry)
    val errorClass = if (entryContainsError(entry)) " error" else ""
    val activeClass = if (entry.id == activeLeafId) " active" else ""
    val htmlId = htmlId(entry.id)
    return """
        <article id="entry-$htmlId" class="entry $role$errorClass$activeClass" data-entry-id="${escapeHtml(entry.id)}"
          data-parent-id="${escapeHtml(entry.parentId.orEmpty())}" data-entry-type="${escapeHtml(entry.type)}">
          <header class="entry-header"><span>${escapeHtml(entryRoleLabel(entry))}</span>
            <time datetime="${escapeHtml(entry.timestamp)}">${escapeHtml(entry.timestamp)}</time></header>
          <div class="entry-body">${renderEntryBody(entry)}</div>
        </article>
        """.trimIndent()
}

private fun renderEntryBody(entry: SessionEntry): String =
    when (entry) {
        is SessionMessageEntry -> renderAgentMessage(entry.message)
        is ThinkingLevelChangeEntry -> "<p>${escapeHtml(entry.thinkingLevel)}</p>"
        is ModelChangeEntry -> "<p>${escapeHtml("${entry.provider}/${entry.modelId}")}</p>"
        is CompactionEntry -> renderRichText(entry.summary)
        is BranchSummaryEntry -> renderRichText(entry.summary)
        is CustomEntry -> renderJson(entry.data)
        is CustomMessageEntry -> renderUserContent(entry.content)
        is LabelEntry -> "<p>${escapeHtml(entry.label.orEmpty())}</p>"
        is SessionInfoEntry -> "<p>${escapeHtml(entry.name.orEmpty())}</p>"
        is RawSessionEntry -> renderJson(entry.payload)
    }

private fun renderAgentMessage(message: dev.xuexue.pi.ai.AgentMessage): String =
    when (message) {
        is UserMessage -> {
            renderUserContent(message.content)
        }

        is AssistantMessage -> {
            renderAssistantContent(message.content)
        }

        is ToolResultMessage -> {
            renderToolResult(message)
        }

        is BashExecutionMessage -> {
            "<details class=\"block\" open><summary>Bash execution</summary>" +
                "<div class=\"details-body\"><pre>${escapeHtml(bashExecutionToText(message))}</pre></div></details>"
        }

        is CustomMessage -> {
            renderUserContent(message.content)
        }

        is BranchSummaryMessage -> {
            renderRichText(message.summary)
        }

        is CompactionSummaryMessage -> {
            renderRichText(message.summary)
        }

        is RawAgentMessage -> {
            renderJson(message.payload)
        }

        else -> {
            "<p class=\"empty\">Unsupported message</p>"
        }
    }

private fun renderUserContent(content: UserContent): String =
    when (content) {
        is UserText -> {
            renderRichText(content.value)
        }

        is UserBlocks -> {
            content.value.joinToString("") { block ->
                "<div class=\"content-block\">${renderUserBlock(block)}</div>"
            }
        }
    }

private fun renderUserBlock(block: UserContentBlock): String =
    when (block) {
        is TextContent -> renderRichText(block.text)
        is ImageContent -> renderImage(block)
    }

private fun renderAssistantContent(content: List<AssistantContent>): String =
    content.joinToString("") { block ->
        when (block) {
            is TextContent -> {
                "<div class=\"content-block rich-text\">${renderRichText(block.text)}</div>"
            }

            is ThinkingContent -> {
                val label = if (block.redacted == true) "Thinking (redacted)" else "Thinking"
                "<details class=\"block thinking\"><summary>${escapeHtml(label)}</summary>" +
                    "<div class=\"details-body rich-text\">${renderRichText(block.thinking)}</div></details>"
            }

            is ToolCall -> {
                renderToolCall(block)
            }
        }
    }

private fun renderToolCall(call: ToolCall): String =
    """
    <details class="block tool-call" data-tool-call-id="${escapeHtml(call.id)}">
      <summary>Tool: ${escapeHtml(call.name)}</summary>
      <div class="details-body"><pre>${escapeHtml(call.arguments.toString())}</pre></div>
    </details>
    """.trimIndent()

private fun renderToolResult(message: ToolResultMessage): String {
    val errorClass = if (message.isError) " error" else ""
    val content =
        message.content.joinToString("") { block ->
            when (block) {
                is TextContent -> "<div class=\"rich-text\">${renderRichText(block.text)}</div>"
                is ImageContent -> renderImage(block)
            }
        }
    val details = message.details?.let { "<pre>${escapeHtml(it.toString())}</pre>" }.orEmpty()
    return """
        <div class="tool-result$errorClass" data-tool-call-id="${escapeHtml(message.toolCallId)}">
          <div class="tool-name">${escapeHtml(message.toolName)}</div>
          $content
          $details
        </div>
        """.trimIndent()
}

private fun renderImage(image: ImageContent): String {
    val mime = image.mimeType.trim()
    val data = image.data.filterNot(Char::isWhitespace)
    return if (IMAGE_MIME.matches(mime) && BASE64.matches(data)) {
        "<img class=\"image-content\" src=\"data:${escapeHtml(mime)};base64,${escapeHtml(data)}\" alt=\"${escapeHtml(mime)}\">"
    } else {
        "<span class=\"image-placeholder\">[image: ${escapeHtml(mime)}]</span>"
    }
}

private fun renderRichText(value: String): String {
    parseSkillBlock(value)?.let { skill ->
        val userMessage = skill.userMessage?.let { "<div class=\"rich-text\">${renderRichText(it)}</div>" }.orEmpty()
        return """
            <details class="block skill-block skill-invocation" open>
              <summary>Skill: ${escapeHtml(skill.name)} (${escapeHtml(skill.location)})</summary>
              <div class="details-body rich-text">${renderRichText(skill.content)}</div>
            </details>
            $userMessage
            """.trimIndent()
    }
    val lines = value.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val builder = StringBuilder()
    var inCode = false
    var language = ""
    lines.forEach { line ->
        val fence = CODE_FENCE.matchEntire(line)
        if (fence != null) {
            if (inCode) {
                builder.append("</code></pre>")
                inCode = false
                language = ""
            } else {
                language = fence.groupValues[1]
                val className = languageClassName(language)
                builder.append("<pre><code$className>")
                inCode = true
            }
        } else if (inCode) {
            builder.append(escapeHtml(line)).append('\n')
        } else if (line.isBlank()) {
            builder.append("<br class=\"line-break\">")
        } else {
            builder.append(renderMarkdownLine(line))
        }
    }
    if (inCode) builder.append("</code></pre>")
    return builder.toString()
}

private fun renderJson(value: JsonElement?): String = "<pre>${escapeHtml(value?.toString().orEmpty())}</pre>"

private fun renderMarkdownLine(value: String): String {
    val heading = HEADING.matchEntire(value)
    return if (heading == null) {
        "<p>${renderMarkdownInline(value)}</p>"
    } else {
        "<h4>${escapeHtml(heading.groupValues[2])}</h4>"
    }
}

private fun entryRole(entry: SessionEntry): String =
    when (entry) {
        is SessionMessageEntry -> {
            when (entry.message) {
                is UserMessage -> "user"
                is AssistantMessage -> "assistant"
                is ToolResultMessage -> "tool"
                is BashExecutionMessage -> "tool"
                else -> "event"
            }
        }

        else -> {
            "event"
        }
    }

private fun entryRoleLabel(entry: SessionEntry): String =
    when (entry) {
        is SessionMessageEntry -> {
            when (entry.message) {
                is UserMessage -> "user"
                is AssistantMessage -> "assistant"
                is ToolResultMessage -> "tool result"
                is BashExecutionMessage -> "bash"
                else -> "message"
            }
        }

        is ThinkingLevelChangeEntry -> {
            "thinking level"
        }

        is ModelChangeEntry -> {
            "model"
        }

        is CompactionEntry -> {
            "compaction"
        }

        is BranchSummaryEntry -> {
            "branch summary"
        }

        is CustomEntry -> {
            "custom: ${entry.customType}"
        }

        is CustomMessageEntry -> {
            "custom: ${entry.customType}"
        }

        is LabelEntry -> {
            "label"
        }

        is SessionInfoEntry -> {
            "session"
        }

        is RawSessionEntry -> {
            entry.rawType
        }
    }

private fun entryContainsError(entry: SessionEntry): Boolean =
    (entry as? SessionMessageEntry)?.message.let { message ->
        when (message) {
            is AssistantMessage -> {
                message.stopReason.name.equals("ERROR", ignoreCase = true) ||
                    message.stopReason.name.equals("ABORTED", ignoreCase = true)
            }

            is ToolResultMessage -> {
                message.isError
            }

            else -> {
                false
            }
        }
    }

private fun messagePreview(message: dev.xuexue.pi.ai.AgentMessage): String =
    when (message) {
        is UserMessage -> skillPreview(message.content) ?: userContentPreview(message.content)
        is AssistantMessage -> message.content.filterIsInstance<TextContent>().joinToString(" ") { it.text }
        is ToolResultMessage -> message.toolName
        is BashExecutionMessage -> message.command
        else -> "message"
    }.replace(WHITESPACE, " ").trim().take(96)

private fun textPreview(value: String): String = value.replace(WHITESPACE, " ").trim().take(96)

private fun languageClassName(language: String): String {
    val escapedLanguage = escapeHtml(language)
    return if (language.isEmpty()) "" else " class=\"language-$escapedLanguage\""
}

private fun renderMarkdownInline(value: String): String {
    val builder = StringBuilder()
    var cursor = 0
    MARKDOWN_INLINE.findAll(value).forEach { match ->
        builder.append(escapeHtml(value.substring(cursor, match.range.first)))
        when {
            match.groups[1] != null -> {
                val alt = match.groups[1]?.value.orEmpty()
                val href = sanitizeMarkdownUrl(match.groups[2]?.value.orEmpty())
                if (href == null) {
                    builder.append(escapeHtml(alt))
                } else {
                    builder.append(
                        "<img class=\"markdown-image\" src=\"${escapeHtml(href)}\" " +
                            "alt=\"${escapeHtml(alt)}\">",
                    )
                }
            }

            match.groups[4] != null -> {
                val label = match.groups[4]?.value.orEmpty()
                val href = sanitizeMarkdownUrl(match.groups[5]?.value.orEmpty())
                if (href == null) {
                    builder.append(escapeHtml(label))
                } else {
                    val title = markdownTitle(match.groups[6]?.value)
                    builder.append("<a href=\"${escapeHtml(href)}\"$title>${escapeHtml(label)}</a>")
                }
            }

            match.groups[7] != null -> {
                builder.append("<code>${escapeHtml(match.groups[7]?.value.orEmpty())}</code>")
            }

            match.groups[8] != null -> {
                builder.append("<strong>${escapeHtml(match.groups[8]?.value.orEmpty())}</strong>")
            }

            match.groups[9] != null -> {
                builder.append("<em>${escapeHtml(match.groups[9]?.value.orEmpty())}</em>")
            }
        }
        cursor = match.range.last + 1
    }
    builder.append(escapeHtml(value.substring(cursor)))
    return builder.toString()
}

private fun sanitizeMarkdownUrl(value: String): String? {
    val href = value.trim().replace(C0_CONTROLS, "")
    if (href.isEmpty()) return href
    val scheme = MARKDOWN_SCHEME.find(href)?.groupValues?.get(1)
    return if (scheme == null || scheme.lowercase() in SAFE_MARKDOWN_SCHEMES) href else null
}

private fun markdownTitle(value: String?): String {
    val title = value ?: return ""
    return " title=\"${escapeHtml(title)}\""
}

private fun userContentPreview(content: UserContent): String =
    when (content) {
        is UserText -> content.value
        is UserBlocks -> content.value.filterIsInstance<TextContent>().joinToString(" ") { it.text }
    }

private fun skillPreview(content: UserContent): String? {
    val skill = parseSkillBlock(userContentPreview(content)) ?: return null
    return buildString {
        append("skill: ")
        append(skill.name)
        skill.userMessage?.let {
            append(" · user: ")
            append(it)
        }
    }
}

private fun isSkillEntry(entry: SessionEntry): Boolean =
    (entry as? SessionMessageEntry)?.message.let { message ->
        when (message) {
            is UserMessage -> parseSkillBlock(userContentPreview(message.content)) != null
            else -> false
        }
    }

private fun htmlId(value: String): String =
    value
        .map { character ->
            if (character.isLetterOrDigit() || character == '-' || character == '_') character else '_'
        }.joinToString("")
        .ifEmpty { "entry" }

private fun escapeHtml(value: String): String =
    buildString(value.length) {
        value.forEach { character ->
            when (character) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(character)
            }
        }
    }

private val CODE_FENCE = Regex("^\\s*```([A-Za-z0-9_+.-]*)\\s*$")
private val HEADING = Regex("^\\s*(#{1,4})\\s+(.+?)\\s*$")
private val IMAGE_MIME = Regex("^image/[A-Za-z0-9.+-]+$")
private val BASE64 = Regex("^[A-Za-z0-9+/]*={0,2}$")
private val WHITESPACE = Regex("\\s+")
private val C0_CONTROLS = Regex("[\\u0000-\\u001f\\u007f]")
private val MARKDOWN_SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")
private val SAFE_MARKDOWN_SCHEMES = setOf("http", "https", "mailto", "tel", "ftp")
private val MARKDOWN_INLINE =
    Regex(
        """!\[([^]]*)\]\(([^)\s]+)(?:\s+[\"']([^\"']*)[\"'])?\)|""" +
            """\[([^]]+)\]\(([^)\s]+)(?:\s+[\"']([^\"']*)[\"'])?\)|""" +
            """`([^`]+)`|\*\*([^*]+)\*\*|\*([^*]+)\*""",
    )
