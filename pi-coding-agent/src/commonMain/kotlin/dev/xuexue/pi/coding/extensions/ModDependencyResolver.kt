package dev.xuexue.pi.coding.extensions

/** A package candidate paired with the executable Mod implementation it supplies. */
data class ModCandidate<T>(
    val packageInfo: ModPackage,
    val value: T,
)

data class ModDependencyDiagnostic(
    val modId: ModId,
    val message: String,
)

/** Deterministic load plan after version selection and dependency validation. */
data class ModDependencyResolution<T>(
    val selected: List<ModCandidate<T>>,
    val diagnostics: List<ModDependencyDiagnostic>,
) {
    val isComplete: Boolean
        get() = diagnostics.isEmpty()
}

/**
 * Selects one immutable package version per mod ID and returns dependency-first
 * activation order. An invalid dependency only disables its dependent branch;
 * unrelated mods remain available.
 */
object ModDependencyResolver {
    fun <T> resolve(candidates: List<ModCandidate<T>>): ModDependencyResolution<T> {
        val grouped =
            candidates
                .groupBy { it.packageInfo.id }
                .mapValues { (_, values) -> values.sortedWith(candidateOrder()) }
        val activeIds = grouped.keys.toMutableSet()
        val diagnostics = linkedSetOf<ModDependencyDiagnostic>()
        var selected = activeIds.associateWith { id -> requireNotNull(grouped[id]).first() }

        while (true) {
            val invalid = linkedSetOf<ModId>()
            val constraints = linkedMapOf<ModId, MutableList<DependencyUse>>()
            activeIds.sortedBy(ModId::value).forEach { sourceId ->
                val source = selected.getValue(sourceId)
                source.packageInfo.dependencies
                    .filterNot(ModDependency::optional)
                    .forEach { dependency ->
                        if (dependency.id !in activeIds || dependency.id !in grouped) {
                            invalid += sourceId
                            diagnostics +=
                                ModDependencyDiagnostic(
                                    sourceId,
                                    "Missing required dependency ${dependency.id}",
                                )
                        } else {
                            constraints.getOrPut(dependency.id) { mutableListOf() } +=
                                DependencyUse(sourceId, dependency)
                        }
                    }
            }
            constraints.forEach { (targetId, uses) ->
                val compatible =
                    grouped.getValue(targetId).firstOrNull { candidate ->
                        uses.all { use -> use.dependency.versionRange.accepts(candidate.packageInfo.version) }
                    }
                if (compatible == null) {
                    uses.forEach { use ->
                        invalid += use.sourceId
                        diagnostics +=
                            ModDependencyDiagnostic(
                                use.sourceId,
                                "No compatible ${targetId} version satisfies ${use.dependency.versionRange}",
                            )
                    }
                } else {
                    selected = selected + (targetId to compatible)
                }
            }
            if (invalid.isNotEmpty()) {
                activeIds.removeAll(invalid)
                selected = selected.filterKeys(activeIds::contains)
                continue
            }

            val ordered = topologicalOrder(activeIds, selected)
            if (ordered.size == activeIds.size) {
                return ModDependencyResolution(ordered.map(selected::getValue), diagnostics.toList())
            }
            val blocked = activeIds - ordered.toSet()
            blocked.sortedBy(ModId::value).forEach { id ->
                diagnostics += ModDependencyDiagnostic(id, "Dependency cycle prevents activation")
            }
            activeIds.removeAll(blocked)
            selected = selected.filterKeys(activeIds::contains)
        }
    }

    private fun <T> candidateOrder(): Comparator<ModCandidate<T>> =
        compareByDescending<ModCandidate<T>> { it.packageInfo.version }
            .thenBy { it.packageInfo.sourcePath.orEmpty() }
            .thenBy { it.packageInfo.entrypoint.orEmpty() }

    private fun <T> topologicalOrder(
        activeIds: Set<ModId>,
        selected: Map<ModId, ModCandidate<T>>,
    ): List<ModId> {
        val dependencies =
            activeIds.associateWith { id ->
                selected
                    .getValue(id)
                    .packageInfo
                    .dependencies
                    .filterNot(ModDependency::optional)
                    .map(ModDependency::id)
                    .filter(activeIds::contains)
                    .toMutableSet()
            }.toMutableMap()
        val ordered = mutableListOf<ModId>()
        while (true) {
            val ready = dependencies.filterValues(Set<ModId>::isEmpty).keys.sortedBy(ModId::value)
            if (ready.isEmpty()) return ordered
            ready.forEach { id ->
                ordered += id
                dependencies.remove(id)
            }
            dependencies.values.forEach { it.removeAll(ready.toSet()) }
        }
    }

    private data class DependencyUse(
        val sourceId: ModId,
        val dependency: ModDependency,
    )
}
