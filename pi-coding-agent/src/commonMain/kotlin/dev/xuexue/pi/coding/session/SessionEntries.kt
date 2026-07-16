@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.xuexue.pi.coding.session

import dev.xuexue.pi.ai.AgentMessage
import dev.xuexue.pi.ai.UserContent
import dev.xuexue.pi.ai.UserContentSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.time.Instant

const val CURRENT_SESSION_VERSION: Int = 3

internal val SessionJson: Json =
    Json {
        classDiscriminator = "type"
        encodeDefaults = false
        explicitNulls = false
        ignoreUnknownKeys = true
    }

@Serializable
data class SessionHeader(
    val type: String,
    val version: Int? = null,
    val id: String,
    val timestamp: String,
    val cwd: String,
    val parentSession: String? = null,
)

data class NewSessionOptions(
    val id: String? = null,
    val parentSession: String? = null,
)

@Serializable(with = SessionEntrySerializer::class)
sealed interface SessionEntry {
    val id: String
    val parentId: String?
    val timestamp: String
}

@Serializable
data class SessionMessageEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    @Serializable(with = AgentMessageSerializer::class)
    val message: AgentMessage,
) : SessionEntry

@Serializable
data class ThinkingLevelChangeEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val thinkingLevel: String,
) : SessionEntry

@Serializable
data class ModelChangeEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val provider: String,
    val modelId: String,
) : SessionEntry

@Serializable
data class CompactionEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val summary: String,
    val firstKeptEntryId: String,
    val tokensBefore: Long,
    val details: JsonElement? = null,
    val fromHook: Boolean? = null,
) : SessionEntry

@Serializable
data class BranchSummaryEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val fromId: String,
    val summary: String,
    val details: JsonElement? = null,
    val fromHook: Boolean? = null,
) : SessionEntry

@Serializable
data class CustomEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val customType: String,
    val data: JsonElement? = null,
) : SessionEntry

@Serializable
data class CustomMessageEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val customType: String,
    @Serializable(with = UserContentSerializer::class)
    val content: UserContent,
    val details: JsonElement? = null,
    val display: Boolean,
) : SessionEntry

@Serializable
data class LabelEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val targetId: String,
    val label: String? = null,
) : SessionEntry

@Serializable
data class SessionInfoEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val name: String? = null,
) : SessionEntry

data class RawSessionEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val rawType: String,
    val payload: JsonObject,
) : SessionEntry

val SessionEntry.type: String
    get() =
        when (this) {
            is SessionMessageEntry -> "message"
            is ThinkingLevelChangeEntry -> "thinking_level_change"
            is ModelChangeEntry -> "model_change"
            is CompactionEntry -> "compaction"
            is BranchSummaryEntry -> "branch_summary"
            is CustomEntry -> "custom"
            is CustomMessageEntry -> "custom_message"
            is LabelEntry -> "label"
            is SessionInfoEntry -> "session_info"
            is RawSessionEntry -> rawType
        }

data class SessionTreeNode(
    val entry: SessionEntry,
    val children: MutableList<SessionTreeNode> = mutableListOf(),
    val label: String? = null,
    val labelTimestamp: String? = null,
)

data class SessionModel(
    val provider: String,
    val modelId: String,
)

data class SessionContext(
    val messages: List<AgentMessage>,
    val thinkingLevel: String,
    val model: SessionModel?,
)

data class SessionInfo(
    val path: String,
    val id: String,
    val cwd: String,
    val name: String? = null,
    val parentSessionPath: String? = null,
    val created: Instant,
    val modified: Instant,
    val messageCount: Int,
    val firstMessage: String,
    val allMessagesText: String,
)

internal object AgentMessageSerializer : KSerializer<AgentMessage> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

    override fun serialize(
        encoder: Encoder,
        value: AgentMessage,
    ) {
        val jsonEncoder = encoder as? JsonEncoder ?: error("Agent messages can only be encoded as JSON")
        jsonEncoder.encodeJsonElement(encodeAgentMessage(value))
    }

    override fun deserialize(decoder: Decoder): AgentMessage {
        val jsonDecoder = decoder as? JsonDecoder ?: error("Agent messages can only be decoded from JSON")
        return decodeAgentMessage(jsonDecoder.decodeJsonElement())
    }
}

internal object SessionEntrySerializer : KSerializer<SessionEntry> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

    override fun serialize(
        encoder: Encoder,
        value: SessionEntry,
    ) {
        val jsonEncoder = encoder as? JsonEncoder ?: error("Session entries can only be encoded as JSON")
        val payload =
            when (value) {
                is SessionMessageEntry -> encode(value, SessionMessageEntry.serializer())
                is ThinkingLevelChangeEntry -> encode(value, ThinkingLevelChangeEntry.serializer())
                is ModelChangeEntry -> encode(value, ModelChangeEntry.serializer())
                is CompactionEntry -> encode(value, CompactionEntry.serializer())
                is BranchSummaryEntry -> encode(value, BranchSummaryEntry.serializer())
                is CustomEntry -> encode(value, CustomEntry.serializer())
                is CustomMessageEntry -> encode(value, CustomMessageEntry.serializer())
                is LabelEntry -> encode(value, LabelEntry.serializer())
                is SessionInfoEntry -> encode(value, SessionInfoEntry.serializer())
                is RawSessionEntry -> value.payload
            }
        jsonEncoder.encodeJsonElement(
            buildJsonObject {
                put("type", value.type)
                payload.forEach { (key, element) ->
                    if (key != "type") put(key, element)
                }
            },
        )
    }

    override fun deserialize(decoder: Decoder): SessionEntry {
        val jsonDecoder = decoder as? JsonDecoder ?: error("Session entries can only be decoded from JSON")
        val payload = jsonDecoder.decodeJsonElement() as? JsonObject ?: error("Session entry must be an object")
        return decode(payload, jsonDecoder.json)
    }

    private fun <T> encode(
        value: T,
        serializer: KSerializer<T>,
    ): JsonObject = SessionJson.encodeToJsonElement(serializer, value).jsonObject

    private fun decode(
        payload: JsonObject,
        json: Json,
    ): SessionEntry {
        val type = payload.string("type").orEmpty()
        return runCatching {
            when (type) {
                "message" -> json.decodeFromJsonElement(SessionMessageEntry.serializer(), payload)
                "thinking_level_change" -> json.decodeFromJsonElement(ThinkingLevelChangeEntry.serializer(), payload)
                "model_change" -> json.decodeFromJsonElement(ModelChangeEntry.serializer(), payload)
                "compaction" -> json.decodeFromJsonElement(CompactionEntry.serializer(), payload)
                "branch_summary" -> json.decodeFromJsonElement(BranchSummaryEntry.serializer(), payload)
                "custom" -> json.decodeFromJsonElement(CustomEntry.serializer(), payload)
                "custom_message" -> json.decodeFromJsonElement(CustomMessageEntry.serializer(), payload)
                "label" -> json.decodeFromJsonElement(LabelEntry.serializer(), payload)
                "session_info" -> json.decodeFromJsonElement(SessionInfoEntry.serializer(), payload)
                else -> rawEntry(payload, type)
            }
        }.getOrElse { rawEntry(payload, type) }
    }

    private fun rawEntry(
        payload: JsonObject,
        type: String,
    ): RawSessionEntry =
        RawSessionEntry(
            id = payload.string("id").orEmpty(),
            parentId = payload.string("parentId"),
            timestamp = payload.string("timestamp").orEmpty(),
            rawType = type,
            payload = payload,
        )
}

internal fun encodeSessionHeader(header: SessionHeader): JsonObject =
    SessionJson
        .encodeToJsonElement(SessionHeader.serializer(), header)
        .jsonObject

internal fun decodeSessionHeader(payload: JsonObject): SessionHeader? =
    runCatching { SessionJson.decodeFromJsonElement(SessionHeader.serializer(), payload) }
        .getOrNull()
        ?.takeIf { it.type == "session" && it.id.isNotEmpty() }

internal fun encodeSessionEntry(entry: SessionEntry): JsonObject =
    SessionJson
        .encodeToJsonElement(SessionEntrySerializer, entry)
        .jsonObject

internal fun decodeSessionEntry(payload: JsonObject): SessionEntry =
    SessionJson.decodeFromJsonElement(
        SessionEntrySerializer,
        payload,
    )

internal fun SessionEntry.withParentId(parentId: String?): SessionEntry =
    when (this) {
        is SessionMessageEntry -> {
            copy(parentId = parentId)
        }

        is ThinkingLevelChangeEntry -> {
            copy(parentId = parentId)
        }

        is ModelChangeEntry -> {
            copy(parentId = parentId)
        }

        is CompactionEntry -> {
            copy(parentId = parentId)
        }

        is BranchSummaryEntry -> {
            copy(parentId = parentId)
        }

        is CustomEntry -> {
            copy(parentId = parentId)
        }

        is CustomMessageEntry -> {
            copy(parentId = parentId)
        }

        is LabelEntry -> {
            copy(parentId = parentId)
        }

        is SessionInfoEntry -> {
            copy(parentId = parentId)
        }

        is RawSessionEntry -> {
            copy(
                parentId = parentId,
                payload =
                    buildJsonObject {
                        payload.forEach { (key, value) -> if (key != "parentId") put(key, value) }
                        if (parentId == null) put("parentId", JsonNull) else put("parentId", parentId)
                    },
            )
        }
    }
