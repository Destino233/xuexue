package dev.xuexue.pi.coding.extensions

/** Read-only host projection used by plugin management UI and diagnostics. */
data class ModLoaderSnapshot(
    val runs: List<ModRunSnapshot> = emptyList(),
    val diagnostics: List<ModRunDiagnostic> = emptyList(),
    val permissions: List<ModPermissionGrant> = emptyList(),
    val ui: ModUiSnapshot = ModUiSnapshot(),
)
