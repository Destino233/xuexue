package dev.xuexue.pi.coding.extensions

/** A manifest discovered on disk, including packages that are not active. */
data class ModDiscoveredPackage(
    val id: ModId,
    val version: ModVersion,
    val runtime: PiPluginRuntime,
    val sourcePath: String,
    val scope: String,
    val trusted: Boolean,
    val status: ModDiscoveryStatus = ModDiscoveryStatus.DISCOVERED,
    val error: String? = null,
)

enum class ModDiscoveryStatus {
    DISCOVERED,
    RUNNING,
    BLOCKED,
    FAILED,
    DISABLED,
}

data class ModDiscoveryDiagnostic(
    val sourcePath: String,
    val message: String,
)

/** Read-only host projection used by plugin management UI and diagnostics. */
data class ModLoaderSnapshot(
    val runs: List<ModRunSnapshot> = emptyList(),
    val discovered: List<ModDiscoveredPackage> = emptyList(),
    val discoveryDiagnostics: List<ModDiscoveryDiagnostic> = emptyList(),
    val diagnostics: List<ModRunDiagnostic> = emptyList(),
    val permissions: List<ModPermissionGrant> = emptyList(),
    val ui: ModUiSnapshot = ModUiSnapshot(),
)
