package dev.xuexue.pi.coding.settings

enum class SettingsScope {
    GLOBAL,
    PROJECT,
}

interface SettingsStorage {
    /**
     * Runs a read-modify-write transaction for one settings scope. Returning null leaves the file untouched.
     */
    fun withLock(
        scope: SettingsScope,
        update: (current: String?) -> String?,
    )
}

class InMemorySettingsStorage(
    global: String? = null,
    project: String? = null,
) : SettingsStorage {
    private var global = global
    private var project = project

    override fun withLock(
        scope: SettingsScope,
        update: (current: String?) -> String?,
    ) {
        val current = if (scope == SettingsScope.GLOBAL) global else project
        update(current)?.let { next ->
            if (scope == SettingsScope.GLOBAL) {
                global = next
            } else {
                project = next
            }
        }
    }

    fun replace(
        scope: SettingsScope,
        content: String?,
    ) {
        if (scope == SettingsScope.GLOBAL) {
            global = content
        } else {
            project = content
        }
    }

    fun content(scope: SettingsScope): String? = if (scope == SettingsScope.GLOBAL) global else project
}

expect object PlatformSettingsStorage {
    fun create(
        cwd: String,
        agentDirectory: String,
    ): SettingsStorage

    fun normalizePath(path: String): String
}
