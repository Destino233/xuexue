package dev.xuexue.pi.ai

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

val PiJson: Json =
    Json {
        classDiscriminator = "type"
        encodeDefaults = false
        explicitNulls = false
        ignoreUnknownKeys = false
    }

object UserContentSerializer : KSerializer<UserContent> {
    override val descriptor: SerialDescriptor = JsonPrimitive.serializer().descriptor

    override fun serialize(
        encoder: Encoder,
        value: UserContent,
    ) {
        val jsonEncoder = encoder as? JsonEncoder ?: error("UserContent can only be encoded as JSON")
        val element =
            when (value) {
                is UserText -> JsonPrimitive(value.value)
                is UserBlocks -> JsonArray(value.value.map(::encodeUserBlock))
            }
        jsonEncoder.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): UserContent {
        val jsonDecoder = decoder as? JsonDecoder ?: error("UserContent can only be decoded from JSON")
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive -> {
                require(element.isString) { "User message content must be a string or an array" }
                UserText(element.content)
            }

            is JsonArray -> {
                UserBlocks(element.map(::decodeUserBlock))
            }

            else -> {
                error("User message content must be a string or an array")
            }
        }
    }

    private fun encodeUserBlock(block: UserContentBlock): JsonObject =
        when (block) {
            is TextContent -> {
                buildJsonObject {
                    put("type", "text")
                    put("text", block.text)
                    block.textSignature?.let { put("textSignature", it) }
                }
            }

            is ImageContent -> {
                buildJsonObject {
                    put("type", "image")
                    put("data", block.data)
                    put("mimeType", block.mimeType)
                }
            }
        }

    private fun decodeUserBlock(element: JsonElement): UserContentBlock {
        val block = element as? JsonObject ?: error("User content blocks must be objects")
        return when (block.requiredString("type")) {
            "text" -> TextContent(block.requiredString("text"), block.optionalString("textSignature"))
            "image" -> ImageContent(block.requiredString("data"), block.requiredString("mimeType"))
            else -> error("Unsupported user content block type: ${block["type"]}")
        }
    }

    private fun JsonObject.requiredString(
        name: String,
    ): String = (get(name) as? JsonPrimitive)?.content ?: error("User content block is missing '$name'")

    private fun JsonObject.optionalString(
        name: String,
    ): String? = (get(name) as? JsonPrimitive)?.content
}
