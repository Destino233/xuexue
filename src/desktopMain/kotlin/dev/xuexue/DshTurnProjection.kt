package dev.xuexue

import dev.xuexue.platform.text
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

internal class DshTurnProjection(
    private val session: AgentSessionState,
) {
    private val tools = mutableMapOf<String, Int>()
    private val chunks = mutableMapOf<Pair<String, String>, Int>()

    fun toolMessage(id: String?): AgentMessage? = tools[id]?.let(session.messages::get)

    fun update(frame: JsonObject) {
        when (frame.text("sessionUpdate")) {
            "agent_message_chunk" -> appendChunk("assistant", frame)
            "agent_thought_chunk" -> appendChunk("reasoning", frame)
            "tool_call" -> startTool(frame)
            "tool_call_update" -> updateTool(frame)
        }
    }

    private fun appendChunk(
        role: String,
        frame: JsonObject,
    ) {
        val content = blockText(frame["content"] as? JsonObject) ?: return
        val key = frame.text("messageId").orEmpty() to role
        val index = chunks[key]
        if (index != null && index == session.messages.lastIndex) {
            val last = session.messages[index]
            session.messages[index] = last.copy(content = last.content + content)
        } else {
            chunks[key] = session.messages.size
            session.messages += AgentMessage(role, content)
        }
    }

    private fun startTool(frame: JsonObject) {
        val id = frame.text("toolCallId") ?: return
        tools[id] = session.messages.size
        val title = frame.text("title") ?: "工具调用"
        val input = frame["rawInput"]?.toString()?.take(2_000).orEmpty()
        val summary = listOf(title, input).filter(String::isNotBlank).joinToString("\n")
        session.messages += AgentMessage("tool_call", summary)
    }

    private fun updateTool(frame: JsonObject) {
        val id = frame.text("toolCallId") ?: return
        val index = tools[id] ?: return
        val status = frame.text("status")
        val output =
            (frame["content"] as? JsonArray)
                .orEmpty()
                .filterIsInstance<JsonObject>()
                .mapNotNull { blockText(it["content"] as? JsonObject) }
                .joinToString("\n")
        val previous = session.messages[index]
        session.messages[index] =
            AgentMessage(
                if (status == "completed" || status == "failed") "tool" else previous.role,
                listOf(frame.text("title") ?: previous.content, output).filter(String::isNotBlank).joinToString("\n"),
                error = status == "failed",
            )
    }

    private fun blockText(block: JsonObject?): String? =
        when (block?.text("type")) {
            "text" -> block.text("text")
            "image" -> "[图片]"
            else -> null
        }
}
