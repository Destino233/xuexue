package dev.xuexue.pi.coding.packages

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object PiPackageManifestCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun decode(text: String): PiPackageManifest {
        val root = json.parseToJsonElement(text).jsonObject
        val manifest = root["pi"]?.jsonObject ?: root
        val resources = manifest["resources"]?.jsonObject
        val id = manifest.string("id") ?: root.string("name")
        val version = manifest.string("version") ?: root.string("version")
        require(!id.isNullOrBlank()) { "Package manifest is missing id/name" }
        require(!version.isNullOrBlank()) { "Package manifest $id is missing version" }
        return PiPackageManifest(
            schemaVersion = manifest.int("schemaVersion") ?: 1,
            id = id,
            version = version,
            apiVersion = manifest.string("apiVersion") ?: "1",
            extensions = manifest.strings("extensions") ?: resources.strings("extensions").orEmpty(),
            skills = manifest.strings("skills") ?: resources.strings("skills").orEmpty(),
            prompts = manifest.strings("prompts") ?: resources.strings("prompts").orEmpty(),
            themes = manifest.strings("themes") ?: resources.strings("themes").orEmpty(),
            permissions = manifest.strings("permissions").orEmpty(),
            metadata = manifest["metadata"],
        ).also(PiPackageManifest::validate)
    }

    fun decodeSettingsSpec(element: JsonElement): PiPackageSpec? {
        return when (element) {
            is JsonPrimitive -> {
                element.contentOrNull?.takeIf(String::isNotBlank)?.let(::PiPackageSpec)
            }

            is JsonObject -> {
                val source = element.string("source") ?: return null
                PiPackageSpec(
                    source = source,
                    autoload = element.boolean("autoload"),
                    extensions = element.strings("extensions"),
                    skills = element.strings("skills"),
                    prompts = element.strings("prompts"),
                    themes = element.strings("themes"),
                )
            }

            else -> {
                null
            }
        }
    }

    private fun JsonObject?.strings(name: String): List<String>? =
        this?.get(name)?.let { value ->
            when (value) {
                is JsonPrimitive -> value.contentOrNull?.let(::listOf)
                else -> value.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
            }
        }

    private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.int(name: String): Int? = this[name]?.jsonPrimitive?.intOrNull()

    private fun JsonObject.boolean(name: String): Boolean? = this[name]?.jsonPrimitive?.booleanOrNull()

    private fun JsonPrimitive.intOrNull(): Int? = contentOrNull?.toIntOrNull()

    private fun JsonPrimitive.booleanOrNull(): Boolean? =
        contentOrNull?.lowercase()?.let { value ->
            when (value) {
                "true" -> true
                "false" -> false
                else -> null
            }
        }
}
