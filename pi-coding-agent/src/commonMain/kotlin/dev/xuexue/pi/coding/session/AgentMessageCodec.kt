package dev.xuexue.pi.coding.session

import dev.xuexue.pi.ai.AgentMessage
import dev.xuexue.pi.ai.Message
import dev.xuexue.pi.ai.UserBlocks
import dev.xuexue.pi.ai.UserContent
import dev.xuexue.pi.ai.UserContentSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal fun encodeAgentMessage(message: AgentMessage): JsonObject =
    when (message) {
        is Message -> {
            SessionJson.encodeToJsonElement(Message.serializer(), message).jsonObject
        }

        is BashExecutionMessage -> {
            encodeBashMessage(message)
        }

        is CustomMessage -> {
            encodeCustomMessage(message)
        }

        is BranchSummaryMessage -> {
            buildJsonObject {
                put("role", "branchSummary")
                put("summary", message.summary)
                put("fromId", message.fromId)
                put("timestamp", message.timestamp)
            }
        }

        is CompactionSummaryMessage -> {
            buildJsonObject {
                put("role", "compactionSummary")
                put("summary", message.summary)
                put("tokensBefore", message.tokensBefore)
                put("timestamp", message.timestamp)
            }
        }

        is RawAgentMessage -> {
            message.payload
        }

        else -> {
            error("Unsupported agent message: ${message::class.simpleName}")
        }
    }

internal fun decodeAgentMessage(element: JsonElement): AgentMessage {
    val payload = element as? JsonObject ?: return RawAgentMessage("unknown", buildJsonObject {}, 0)
    val role = payload.string("role").orEmpty()
    val timestamp = payload.long("timestamp") ?: 0L
    return when (role) {
        "user", "assistant", "toolResult" -> {
            decodeStandardMessage(payload, role, timestamp)
        }

        "bashExecution" -> {
            decodeBashMessage(payload, timestamp)
        }

        "custom" -> {
            CustomMessage(
                customType = payload.string("customType").orEmpty(),
                content = decodeUserContent(payload["content"]),
                display = payload.boolean("display") ?: false,
                details = payload["details"],
                timestamp = timestamp,
            )
        }

        "branchSummary" -> {
            BranchSummaryMessage(
                summary = payload.string("summary").orEmpty(),
                fromId = payload.string("fromId").orEmpty(),
                timestamp = timestamp,
            )
        }

        "compactionSummary" -> {
            CompactionSummaryMessage(
                summary = payload.string("summary").orEmpty(),
                tokensBefore = payload.long("tokensBefore") ?: 0L,
                timestamp = timestamp,
            )
        }

        else -> {
            RawAgentMessage(role, payload, timestamp)
        }
    }
}

private fun encodeBashMessage(message: BashExecutionMessage): JsonObject =
    buildJsonObject {
        put("role", "bashExecution")
        put("command", message.command)
        put("output", message.output)
        message.exitCode?.let { put("exitCode", it) }
        put("cancelled", message.cancelled)
        put("truncated", message.truncated)
        message.fullOutputPath?.let { put("fullOutputPath", it) }
        put("timestamp", message.timestamp)
        message.excludeFromContext?.let { put("excludeFromContext", it) }
    }

private fun encodeCustomMessage(message: CustomMessage): JsonObject =
    buildJsonObject {
        put("role", "custom")
        put("customType", message.customType)
        put("content", SessionJson.encodeToJsonElement(UserContentSerializer, message.content))
        put("display", message.display)
        message.details?.let { put("details", it) }
        put("timestamp", message.timestamp)
    }

private fun decodeBashMessage(
    payload: JsonObject,
    timestamp: Long,
): BashExecutionMessage =
    BashExecutionMessage(
        command = payload.string("command").orEmpty(),
        output = payload.string("output").orEmpty(),
        exitCode = payload.long("exitCode")?.toInt(),
        cancelled = payload.boolean("cancelled") ?: false,
        truncated = payload.boolean("truncated") ?: false,
        fullOutputPath = payload.string("fullOutputPath"),
        timestamp = timestamp,
        excludeFromContext = payload.boolean("excludeFromContext"),
    )

private fun decodeStandardMessage(
    payload: JsonObject,
    role: String,
    timestamp: Long,
): AgentMessage {
    val normalized = normalizeStandardMessage(payload, role, timestamp)
    return runCatching {
        SessionJson.decodeFromJsonElement(Message.serializer(), normalized)
    }.getOrElse {
        RawAgentMessage(role, payload, timestamp)
    }
}

private fun normalizeStandardMessage(
    payload: JsonObject,
    role: String,
    timestamp: Long,
): JsonObject =
    buildJsonObject {
        payload.forEach(::put)
        put("role", role)
        put("timestamp", timestamp)
        when (role) {
            "user" -> {
                put("content", normalizeUserContent(payload["content"]))
            }

            "assistant" -> {
                put("content", normalizeBlockContent(payload["content"]))
                put("api", payload.string("api").orEmpty())
                put("provider", payload.string("provider").orEmpty())
                put("model", payload.string("model").orEmpty())
                put("usage", normalizeUsage(payload["usage"]))
                put("stopReason", payload.string("stopReason") ?: "stop")
            }

            "toolResult" -> {
                put("toolCallId", payload.string("toolCallId").orEmpty())
                put("toolName", payload.string("toolName").orEmpty())
                put("content", normalizeBlockContent(payload["content"]))
                put("isError", payload.boolean("isError") ?: false)
            }
        }
    }

private fun normalizeUsage(element: JsonElement?): JsonObject {
    val usage = element as? JsonObject ?: JsonObject(emptyMap())
    val input = usage.long("input") ?: 0L
    val output = usage.long("output") ?: 0L
    return buildJsonObject {
        usage.forEach(::put)
        put("input", input)
        put("output", output)
        put("cacheRead", usage.long("cacheRead") ?: 0L)
        put("cacheWrite", usage.long("cacheWrite") ?: 0L)
        put("totalTokens", usage.long("totalTokens") ?: input + output)
        put("cost", normalizeCost(usage["cost"]))
    }
}

private fun normalizeCost(element: JsonElement?): JsonObject {
    val cost = element as? JsonObject ?: JsonObject(emptyMap())
    return buildJsonObject {
        cost.forEach(::put)
        put("input", cost.double("input") ?: 0.0)
        put("output", cost.double("output") ?: 0.0)
        put("cacheRead", cost.double("cacheRead") ?: 0.0)
        put("cacheWrite", cost.double("cacheWrite") ?: 0.0)
        put("total", cost.double("total") ?: 0.0)
    }
}

internal fun normalizeUserContent(element: JsonElement?): JsonElement =
    when (element) {
        is JsonPrimitive -> if (element.isString) element else JsonArray(emptyList())
        is JsonArray -> element
        else -> JsonArray(emptyList())
    }

private fun normalizeBlockContent(element: JsonElement?): JsonArray =
    when (element) {
        is JsonArray -> {
            element
        }

        is JsonPrimitive -> {
            if (element.isString) {
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", element.content)
                        },
                    )
                }
            } else {
                JsonArray(emptyList())
            }
        }

        else -> {
            JsonArray(emptyList())
        }
    }
