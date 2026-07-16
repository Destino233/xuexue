package dev.xuexue.pi.coding.session

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

fun parseSessionEntries(content: String): List<JsonObject> =
    content
        .trim()
        .lineSequence()
        .mapNotNull(::parseSessionEntryLine)
        .toList()

fun migrateSessionEntries(entries: MutableList<JsonObject>) {
    migrateToCurrentVersion(entries)
}

internal fun migrateToCurrentVersion(entries: MutableList<JsonObject>): Boolean {
    val headerIndex = entries.indexOfFirst { it.string("type") == "session" }
    return if (headerIndex < 0) {
        false
    } else {
        val version = entries[headerIndex]["version"]?.jsonPrimitive?.intOrNull ?: 1
        if (version >= CURRENT_SESSION_VERSION) {
            false
        } else {
            if (version < 2) migrateV1ToV2(entries)
            if (version < 3) migrateV2ToV3(entries)
            true
        }
    }
}

internal fun parseSessionEntryLine(line: String): JsonObject? {
    if (line.isBlank()) return null
    return runCatching { SessionJson.parseToJsonElement(line).jsonObject }.getOrNull()
}

private fun migrateV1ToV2(entries: MutableList<JsonObject>) {
    val ids = mutableSetOf<String>()
    var previousId: String? = null
    entries.indices.forEach { index ->
        val entry = entries[index]
        if (entry.string("type") == "session") {
            entries[index] = entry.updated("version", JsonPrimitive(2))
        } else {
            val id = generateEntryId(ids)
            ids += id
            var migrated =
                entry
                    .updated("id", JsonPrimitive(id))
                    .updated("parentId", previousId?.let(::JsonPrimitive) ?: JsonNull)
            previousId = id
            if (migrated.string("type") == "compaction") {
                val firstKeptIndex = migrated["firstKeptEntryIndex"]?.jsonPrimitive?.intOrNull
                val targetId = firstKeptIndex?.let(entries::getOrNull)?.string("id")
                migrated =
                    buildJsonObject {
                        migrated.forEach { (key, value) ->
                            if (key != "firstKeptEntryIndex") put(key, value)
                        }
                        targetId?.let { put("firstKeptEntryId", it) }
                    }
            }
            entries[index] = migrated
        }
    }
}

private fun migrateV2ToV3(entries: MutableList<JsonObject>) {
    entries.indices.forEach { index ->
        val entry = entries[index]
        if (entry.string("type") == "session") {
            entries[index] = entry.updated("version", JsonPrimitive(3))
        } else if (entry.string("type") == "message") {
            val message = entry["message"] as? JsonObject
            if (message?.string("role") == "hookMessage") {
                entries[index] = entry.updated("message", message.updated("role", JsonPrimitive("custom")))
            }
        }
    }
}

private fun JsonObject.updated(
    key: String,
    value: JsonElement,
): JsonObject =
    buildJsonObject {
        this@updated.forEach { (existingKey, existingValue) ->
            if (existingKey != key) put(existingKey, existingValue)
        }
        put(key, value)
    }
