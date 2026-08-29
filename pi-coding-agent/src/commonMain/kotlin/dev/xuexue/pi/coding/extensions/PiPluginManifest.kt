package dev.xuexue.pi.coding.extensions

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Serializable
enum class PiPluginRuntime {
    JVM,
    PROCESS,
}

@Serializable
data class PiPluginManifest(
    val schemaVersion: Int = 1,
    val id: String,
    val version: String,
    val apiVersion: String,
    val runtime: PiPluginRuntime,
    val entrypoint: String? = null,
    val executable: String? = null,
    val dependencies: List<String> = emptyList(),
    val permissions: List<String> = emptyList(),
    val contributes: List<String> = emptyList(),
)

object PiPluginManifestCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun decode(text: String): PiPluginManifest {
        val source = json.parseToJsonElement(text).jsonObject
        val normalized =
            buildJsonObject {
                source.forEach { (key, value) ->
                    if (key == "runtime") {
                        val runtime = value.jsonPrimitive.contentOrNull
                        put(key, JsonPrimitive(runtime?.uppercase().orEmpty()))
                    } else {
                        put(key, value)
                    }
                }
            }
        return json.decodeFromJsonElement(PiPluginManifest.serializer(), normalized).also {
            it.validate()
        }
    }
}

fun PiPluginManifest.validate() {
    require(schemaVersion == PI_PLUGIN_SCHEMA_VERSION) {
        "Unsupported plugin manifest schemaVersion: $schemaVersion"
    }
    require(id.isNotBlank()) { "Plugin manifest id must not be blank" }
    require(version.isNotBlank()) { "Plugin $id manifest version must not be blank" }
    require(apiVersion == PI_PLUGIN_API_VERSION) {
        "Plugin $id requires unsupported API version: $apiVersion"
    }
    when (runtime) {
        PiPluginRuntime.JVM -> {
            require(!entrypoint.isNullOrBlank()) {
                "JVM plugin $id is missing entrypoint"
            }
        }

        PiPluginRuntime.PROCESS -> {
            require(!executable.isNullOrBlank()) {
                "Process plugin $id is missing executable"
            }
        }
    }
}

fun PiPluginManifest.toModPackage(sourcePath: String? = null): ModPackage =
    ModPackage(
        id = ModId.parse(id),
        version = ModVersion(version),
        apiVersion = apiVersion,
        runtime = runtime,
        entrypoint = entrypoint,
        executable = executable,
        dependencies = dependencies.map(ModDependency::parse),
        permissions = permissions.toSet(),
        contributes = contributes.toSet(),
        sourcePath = sourcePath,
    )

interface PiExtensionProvider {
    fun createPiExtension(): PiExtension
}

/** Entry point implemented by new, ownership-aware JVM mods. */
interface PiModProvider {
    fun createPiMod(): PiMod
}

const val PI_PLUGIN_MANIFEST_PATH: String = "META-INF/xuexue-pi-plugin.json"
const val PI_PLUGIN_SCHEMA_VERSION: Int = 1
const val PI_PLUGIN_API_VERSION: String = "1"
