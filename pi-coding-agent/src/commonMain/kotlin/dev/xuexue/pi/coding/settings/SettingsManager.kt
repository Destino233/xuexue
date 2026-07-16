@file:Suppress("TooManyFunctions", "LargeClass")

package dev.xuexue.pi.coding.settings

import dev.xuexue.pi.coding.AgentSessionSettings
import dev.xuexue.pi.coding.RetrySettings
import dev.xuexue.pi.coding.compaction.CompactionSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

enum class DefaultProjectTrust {
    ASK,
    ALWAYS,
    NEVER,
}

data class SettingsError(
    val scope: SettingsScope,
    val error: Throwable,
)

/**
 * Kotlin port of Pi's scoped settings behavior. Values remain JSON so unknown future settings survive edits.
 */
class SettingsManager private constructor(
    private val storage: SettingsStorage,
    initialGlobalSettings: JsonObject,
    initialProjectSettings: JsonObject,
    private var globalLoadError: Throwable?,
    private var projectLoadError: Throwable?,
    initialErrors: List<SettingsError>,
    projectTrusted: Boolean,
) : AgentSessionSettings {
    private var globalSettings = initialGlobalSettings
    private var projectSettings = initialProjectSettings
    private var effectiveSettings = deepMergeSettings(globalSettings, projectSettings)
    private var projectTrusted = projectTrusted
    private val errors = initialErrors.toMutableList()
    private val modifiedGlobalFields = linkedSetOf<String>()
    private val modifiedGlobalNestedFields = linkedMapOf<String, MutableSet<String>>()
    private val modifiedProjectFields = linkedSetOf<String>()
    private val modifiedProjectNestedFields = linkedMapOf<String, MutableSet<String>>()

    companion object {
        const val DEFAULT_HTTP_IDLE_TIMEOUT_MS = 300_000L

        fun create(
            cwd: String,
            agentDirectory: String,
            projectTrusted: Boolean = true,
        ): SettingsManager =
            fromStorage(
                storage = PlatformSettingsStorage.create(cwd, agentDirectory),
                projectTrusted = projectTrusted,
            )

        fun fromStorage(
            storage: SettingsStorage,
            projectTrusted: Boolean = true,
        ): SettingsManager {
            val globalLoad = tryLoad(storage, SettingsScope.GLOBAL)
            val projectLoad = tryLoad(storage, SettingsScope.PROJECT, projectTrusted)
            val errors =
                buildList {
                    globalLoad.error?.let { add(SettingsError(SettingsScope.GLOBAL, it)) }
                    projectLoad.error?.let { add(SettingsError(SettingsScope.PROJECT, it)) }
                }
            return SettingsManager(
                storage = storage,
                initialGlobalSettings = globalLoad.settings,
                initialProjectSettings = projectLoad.settings,
                globalLoadError = globalLoad.error,
                projectLoadError = projectLoad.error,
                initialErrors = errors,
                projectTrusted = projectTrusted,
            )
        }

        fun inMemory(
            globalSettings: JsonObject = EMPTY_SETTINGS,
            projectSettings: JsonObject = EMPTY_SETTINGS,
            projectTrusted: Boolean = true,
        ): SettingsManager {
            val storage =
                InMemorySettingsStorage(
                    global = encode(migrateSettings(globalSettings)),
                    project = encode(migrateSettings(projectSettings)),
                )
            return fromStorage(storage, projectTrusted)
        }

        @Suppress("TooGenericExceptionCaught")
        private fun tryLoad(
            storage: SettingsStorage,
            scope: SettingsScope,
            projectTrusted: Boolean = true,
        ): SettingsLoad =
            try {
                SettingsLoad(load(storage, scope, projectTrusted), null)
            } catch (error: Exception) {
                SettingsLoad(EMPTY_SETTINGS, error)
            }

        private fun load(
            storage: SettingsStorage,
            scope: SettingsScope,
            projectTrusted: Boolean,
        ): JsonObject {
            if (scope == SettingsScope.PROJECT && !projectTrusted) return EMPTY_SETTINGS
            var content: String? = null
            storage.withLock(scope) { current ->
                content = current
                null
            }
            return content
                ?.takeIf(String::isNotBlank)
                ?.let(::parseSettings)
                ?.let(::migrateSettings)
                ?: EMPTY_SETTINGS
        }

        private fun parseSettings(content: String): JsonObject = SETTINGS_JSON.parseToJsonElement(content).jsonObject

        private fun migrateSettings(settings: JsonObject): JsonObject {
            val migrated = settings.toMutableMap()
            if ("queueMode" in migrated && "steeringMode" !in migrated) {
                migrated["steeringMode"] = requireNotNull(migrated.remove("queueMode"))
            }
            val websockets = (migrated["websockets"] as? JsonPrimitive)?.booleanOrNull
            if ("transport" !in migrated && websockets != null) {
                migrated["transport"] = JsonPrimitive(if (websockets) "websocket" else "sse")
                migrated.remove("websockets")
            }
            migrateSkills(migrated)
            migrateRetryDelay(migrated)
            return JsonObject(migrated)
        }

        private fun migrateSkills(settings: MutableMap<String, JsonElement>) {
            val legacySkills = settings["skills"] as? JsonObject ?: return
            if ("enableSkillCommands" !in settings) {
                legacySkills["enableSkillCommands"]?.let { settings["enableSkillCommands"] = it }
            }
            val customDirectories = legacySkills["customDirectories"] as? JsonArray
            if (customDirectories?.isNotEmpty() == true) {
                settings["skills"] = customDirectories
            } else {
                settings.remove("skills")
            }
        }

        private fun migrateRetryDelay(settings: MutableMap<String, JsonElement>) {
            val retry = settings["retry"] as? JsonObject ?: return
            val mutableRetry = retry.toMutableMap()
            val provider = (mutableRetry["provider"] as? JsonObject)?.toMutableMap() ?: linkedMapOf()
            val legacyMaxDelay = mutableRetry["maxDelayMs"]
            if (legacyMaxDelay is JsonPrimitive && provider["maxRetryDelayMs"] == null) {
                provider["maxRetryDelayMs"] = legacyMaxDelay
            }
            mutableRetry.remove("maxDelayMs")
            if (provider.isNotEmpty()) mutableRetry["provider"] = JsonObject(provider)
            settings["retry"] = JsonObject(mutableRetry)
        }

        private fun encode(settings: JsonObject): String {
            val serializer = JsonObject.serializer()
            return SETTINGS_JSON.encodeToString(serializer, settings)
        }
    }

    fun getSettings(): JsonObject = effectiveSettings

    fun getGlobalSettings(): JsonObject = globalSettings

    fun getProjectSettings(): JsonObject = projectSettings

    fun isProjectTrusted(): Boolean = projectTrusted

    fun setProjectTrusted(trusted: Boolean) {
        if (projectTrusted == trusted) return
        projectTrusted = trusted
        clearModified(SettingsScope.PROJECT)
        if (!trusted) {
            projectSettings = EMPTY_SETTINGS
            projectLoadError = null
        } else {
            val loaded = tryLoad(storage, SettingsScope.PROJECT, projectTrusted = true)
            projectSettings = loaded.settings
            projectLoadError = loaded.error
            loaded.error?.let { recordError(SettingsScope.PROJECT, it) }
        }
        recomputeSettings()
    }

    fun reload() {
        val global = tryLoad(storage, SettingsScope.GLOBAL)
        if (global.error == null) {
            globalSettings = global.settings
            globalLoadError = null
        } else {
            globalLoadError = global.error
            recordError(SettingsScope.GLOBAL, global.error)
        }
        val project = tryLoad(storage, SettingsScope.PROJECT, projectTrusted)
        if (project.error == null) {
            projectSettings = project.settings
            projectLoadError = null
        } else {
            projectLoadError = project.error
            recordError(SettingsScope.PROJECT, project.error)
        }
        clearModified(SettingsScope.GLOBAL)
        clearModified(SettingsScope.PROJECT)
        recomputeSettings()
    }

    fun flush() = Unit

    fun drainErrors(): List<SettingsError> = errors.toList().also { errors.clear() }

    fun applyOverrides(overrides: JsonObject) {
        effectiveSettings = deepMergeSettings(effectiveSettings, overrides)
    }

    fun getSetting(name: String): JsonElement? = effectiveSettings[name]

    fun setGlobalSetting(
        name: String,
        value: JsonElement,
    ) {
        globalSettings = globalSettings.updated(name, value)
        markModified(SettingsScope.GLOBAL, name)
        saveGlobalSettings()
    }

    fun setGlobalNestedSetting(
        name: String,
        nestedName: String,
        value: JsonElement,
    ) {
        globalSettings = globalSettings.updatedNested(name, nestedName, value)
        markModified(SettingsScope.GLOBAL, name, nestedName)
        saveGlobalSettings()
    }

    fun setProjectSetting(
        name: String,
        value: JsonElement,
    ) {
        assertProjectTrustedForWrite()
        projectSettings = projectSettings.updated(name, value)
        markModified(SettingsScope.PROJECT, name)
        saveProjectSettings()
    }

    fun setProjectNestedSetting(
        name: String,
        nestedName: String,
        value: JsonElement,
    ) {
        assertProjectTrustedForWrite()
        projectSettings = projectSettings.updatedNested(name, nestedName, value)
        markModified(SettingsScope.PROJECT, name, nestedName)
        saveProjectSettings()
    }

    fun getThemeSetting(): String? = effectiveSettings.string("theme")

    fun getTheme(): String? = getThemeSetting()?.takeUnless { '/' in it }

    fun setTheme(theme: String) = setGlobalSetting("theme", JsonPrimitive(theme))

    fun getDefaultProvider(): String? = effectiveSettings.string("defaultProvider")

    fun setDefaultProvider(provider: String) = setGlobalSetting("defaultProvider", JsonPrimitive(provider))

    fun getDefaultModel(): String? = effectiveSettings.string("defaultModel")

    fun setDefaultModel(model: String) = setGlobalSetting("defaultModel", JsonPrimitive(model))

    fun setDefaultThinkingLevel(level: String) = setGlobalSetting("defaultThinkingLevel", JsonPrimitive(level))

    fun getDefaultThinkingLevel(): String? = effectiveSettings.string("defaultThinkingLevel")

    fun getSteeringMode(): String = effectiveSettings.string("steeringMode") ?: "one-at-a-time"

    fun getFollowUpMode(): String = effectiveSettings.string("followUpMode") ?: "one-at-a-time"

    fun getDefaultProjectTrust(): DefaultProjectTrust =
        when (globalSettings.string("defaultProjectTrust")) {
            "always" -> DefaultProjectTrust.ALWAYS
            "never" -> DefaultProjectTrust.NEVER
            else -> DefaultProjectTrust.ASK
        }

    fun getHttpIdleTimeoutMs(): Long {
        val configured = effectiveSettings.long("httpIdleTimeoutMs") ?: return DEFAULT_HTTP_IDLE_TIMEOUT_MS
        require(configured >= 0) { "Invalid httpIdleTimeoutMs setting: $configured" }
        return configured
    }

    fun setHttpIdleTimeoutMs(timeoutMs: Long) {
        require(timeoutMs >= 0) { "Invalid httpIdleTimeoutMs setting: $timeoutMs" }
        setGlobalSetting("httpIdleTimeoutMs", JsonPrimitive(timeoutMs))
    }

    fun getSessionDirectory(): String? {
        val sessionDirectory = effectiveSettings.string("sessionDir")
        return sessionDirectory?.let(PlatformSettingsStorage::normalizePath)
    }

    fun getShellPath(): String? {
        val shellPath = effectiveSettings.string("shellPath")
        return shellPath?.let(PlatformSettingsStorage::normalizePath)
    }

    fun getExtensionPaths(): List<String> = effectiveSettings.stringArray("extensions")

    fun setExtensionPaths(paths: List<String>) = setGlobalStringArray("extensions", paths)

    fun setProjectExtensionPaths(paths: List<String>) = setProjectStringArray("extensions", paths)

    fun getSkillPaths(): List<String> = effectiveSettings.stringArray("skills")

    fun setSkillPaths(paths: List<String>) = setGlobalStringArray("skills", paths)

    fun setProjectSkillPaths(paths: List<String>) = setProjectStringArray("skills", paths)

    fun getPromptTemplatePaths(): List<String> = effectiveSettings.stringArray("prompts")

    fun setPromptTemplatePaths(paths: List<String>) = setGlobalStringArray("prompts", paths)

    fun setProjectPromptTemplatePaths(paths: List<String>) = setProjectStringArray("prompts", paths)

    fun getThemePaths(): List<String> = effectiveSettings.stringArray("themes")

    fun getPackages(): List<JsonElement> =
        (effectiveSettings["packages"] as? JsonArray)
            ?.toList()
            .orEmpty()

    fun setPackages(packages: List<JsonElement>) = setGlobalSetting("packages", JsonArray(packages))

    fun setProjectPackages(packages: List<JsonElement>) = setProjectSetting("packages", JsonArray(packages))

    override fun getRetrySettings(): RetrySettings {
        val retry = effectiveSettings["retry"] as? JsonObject
        return RetrySettings(
            enabled = retry.boolean("enabled") ?: true,
            maxRetries = retry.long("maxRetries")?.toInt() ?: 3,
            baseDelayMs = retry.long("baseDelayMs") ?: 2_000,
        )
    }

    override fun setRetryEnabled(enabled: Boolean) {
        setGlobalNestedSetting("retry", "enabled", JsonPrimitive(enabled))
    }

    override fun getCompactionSettings(): CompactionSettings {
        val compaction = effectiveSettings["compaction"] as? JsonObject
        return CompactionSettings(
            enabled = compaction.boolean("enabled") ?: true,
            reserveTokens = compaction.long("reserveTokens") ?: 16_384,
            keepRecentTokens = compaction.long("keepRecentTokens") ?: 20_000,
        )
    }

    override fun setCompactionEnabled(enabled: Boolean) {
        setGlobalNestedSetting("compaction", "enabled", JsonPrimitive(enabled))
    }

    private fun setGlobalStringArray(
        name: String,
        values: List<String>,
    ) = setGlobalSetting(name, JsonArray(values.map(::JsonPrimitive)))

    private fun setProjectStringArray(
        name: String,
        values: List<String>,
    ) = setProjectSetting(name, JsonArray(values.map(::JsonPrimitive)))

    private fun saveGlobalSettings() {
        recomputeSettings()
        if (globalLoadError != null) return
        persist(
            scope = SettingsScope.GLOBAL,
            snapshot = globalSettings,
            fields = modifiedGlobalFields.toSet(),
            nestedFields = modifiedGlobalNestedFields.copySets(),
        )
    }

    private fun saveProjectSettings() {
        recomputeSettings()
        if (projectLoadError != null) return
        persist(
            scope = SettingsScope.PROJECT,
            snapshot = projectSettings,
            fields = modifiedProjectFields.toSet(),
            nestedFields = modifiedProjectNestedFields.copySets(),
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private fun persist(
        scope: SettingsScope,
        snapshot: JsonObject,
        fields: Set<String>,
        nestedFields: Map<String, Set<String>>,
    ) {
        try {
            storage.withLock(scope) { current ->
                val currentSettings =
                    current?.takeIf(String::isNotBlank)?.let(::parseSettings)?.let(::migrateSettings)
                        ?: EMPTY_SETTINGS
                encode(mergeModifiedSettings(currentSettings, snapshot, fields, nestedFields))
            }
            clearModified(scope)
        } catch (error: Exception) {
            recordError(scope, error)
        }
    }

    private fun assertProjectTrustedForWrite() {
        check(projectTrusted) { "Project is not trusted; refusing to write project settings" }
    }

    private fun markModified(
        scope: SettingsScope,
        name: String,
        nestedName: String? = null,
    ) {
        val fields = if (scope == SettingsScope.GLOBAL) modifiedGlobalFields else modifiedProjectFields
        val nested = if (scope == SettingsScope.GLOBAL) modifiedGlobalNestedFields else modifiedProjectNestedFields
        fields += name
        nestedName?.let { nested.getOrPut(name) { linkedSetOf() } += it }
    }

    private fun clearModified(scope: SettingsScope) {
        if (scope == SettingsScope.GLOBAL) {
            modifiedGlobalFields.clear()
            modifiedGlobalNestedFields.clear()
        } else {
            modifiedProjectFields.clear()
            modifiedProjectNestedFields.clear()
        }
    }

    private fun recomputeSettings() {
        effectiveSettings = deepMergeSettings(globalSettings, projectSettings)
    }

    private fun recordError(
        scope: SettingsScope,
        error: Throwable?,
    ) {
        error?.let { errors += SettingsError(scope, it) }
    }
}

private data class SettingsLoad(
    val settings: JsonObject,
    val error: Throwable?,
)

private val SETTINGS_JSON =
    Json {
        prettyPrint = true
        explicitNulls = false
    }

private val EMPTY_SETTINGS = buildJsonObject {}

private fun deepMergeSettings(
    base: JsonObject,
    overrides: JsonObject,
): JsonObject =
    buildJsonObject {
        base.forEach { (key, value) -> put(key, value) }
        overrides.forEach { (key, value) ->
            val baseValue = base[key]
            put(
                key,
                if (baseValue is JsonObject && value is JsonObject) deepMergeSettings(baseValue, value) else value,
            )
        }
    }

private fun mergeModifiedSettings(
    current: JsonObject,
    snapshot: JsonObject,
    fields: Set<String>,
    nestedFields: Map<String, Set<String>>,
): JsonObject =
    buildJsonObject {
        current.forEach { (key, value) -> put(key, value) }
        fields.forEach { field ->
            val snapshotValue = snapshot[field] ?: return@forEach
            val nested = nestedFields[field]
            val mergedValue =
                if (nested != null && snapshotValue is JsonObject) {
                    val currentObject = (current[field] as? JsonObject)?.toMutableMap() ?: linkedMapOf()
                    nested.forEach { name -> snapshotValue[name]?.let { currentObject[name] = it } }
                    JsonObject(currentObject)
                } else {
                    snapshotValue
                }
            put(field, mergedValue)
        }
    }

private fun JsonObject.updated(
    name: String,
    value: JsonElement,
): JsonObject = JsonObject(toMutableMap().apply { put(name, value) })

private fun JsonObject.updatedNested(
    name: String,
    nestedName: String,
    value: JsonElement,
): JsonObject {
    val nested = (this[name] as? JsonObject)?.toMutableMap() ?: linkedMapOf()
    nested[nestedName] = value
    return updated(name, JsonObject(nested))
}

private fun MutableMap<String, MutableSet<String>>.copySets(): Map<String, Set<String>> {
    val copies = mapValues { (_, values) -> values.toSet() }
    return copies
}

private fun JsonObject.string(name: String): String? {
    val primitive = this[name] as? JsonPrimitive
    return primitive?.takeIf(JsonPrimitive::isString)?.contentOrNull
}

private fun JsonObject?.long(name: String): Long? = this?.get(name)?.let { (it as? JsonPrimitive)?.longOrNull }

private fun JsonObject?.boolean(name: String): Boolean? = this?.get(name)?.let { (it as? JsonPrimitive)?.booleanOrNull }

private fun JsonObject.stringArray(name: String): List<String> =
    (this[name] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull }
        .orEmpty()
