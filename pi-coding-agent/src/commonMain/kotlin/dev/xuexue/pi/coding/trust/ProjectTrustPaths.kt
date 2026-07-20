package dev.xuexue.pi.coding.trust

import dev.xuexue.pi.coding.resources.ResourceFileSystem

internal fun normalizeCwd(
    path: String,
    fileSystem: ResourceFileSystem,
): String = fileSystem.canonicalizePath(fileSystem.resolvePath(path))

internal fun findNearestTrustEntry(
    data: Map<String, ProjectTrustValue>,
    cwd: String,
    fileSystem: ResourceFileSystem,
): ProjectTrustStoreEntry? {
    var current = cwd
    while (true) {
        val decision = data[current]
        if (decision != null) return ProjectTrustStoreEntry(current, decision)
        val parent = fileSystem.parent(current)
        if (parent == current) return null
        current = normalizeCwd(parent, fileSystem)
    }
}
