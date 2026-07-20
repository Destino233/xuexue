package dev.xuexue.pi.coding.session

import dev.xuexue.pi.ai.AgentMessage
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Stable wire helpers shared by the JSONL RPC host and process extensions. */
fun encodeAgentMessageForWire(message: AgentMessage): JsonObject = encodeAgentMessage(message)

fun decodeAgentMessageFromWire(element: JsonElement): AgentMessage = decodeAgentMessage(element)

fun encodeSessionEntryForWire(entry: SessionEntry): JsonObject = encodeSessionEntry(entry)
