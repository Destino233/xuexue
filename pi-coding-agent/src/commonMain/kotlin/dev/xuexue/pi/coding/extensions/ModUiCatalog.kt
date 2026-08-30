package dev.xuexue.pi.coding.extensions

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Host-facing snapshot of all active UI contributions. */
data class ModUiSnapshot(
    val panels: List<RegisteredModEntry<UiPanelContribution>> = emptyList(),
    val actions: List<RegisteredModEntry<UiActionContribution>> = emptyList(),
)

/** Ownership-aware UI contribution registry shared by all active mod runs. */
class ModUiCatalog {
    private val panels = ModOwnedRegistry<UiPanelContribution>(ModContributionKind.UI)
    private val actions = ModOwnedRegistry<UiActionContribution>(ModContributionKind.UI)
    private val mutableSnapshot = MutableStateFlow(ModUiSnapshot())

    val snapshot: StateFlow<ModUiSnapshot> = mutableSnapshot.asStateFlow()

    fun forRun(owner: ModRun): UiRegistry =
        object : UiRegistry {
            override fun registerPanel(contribution: UiPanelContribution): Registration {
                val registration =
                    panels.register(
                        owner,
                        owner.info.id.qualify(contribution.id),
                        contribution,
                    )
                refresh()
                return RefreshingRegistration(registration, ::refresh)
            }

            override fun registerAction(contribution: UiActionContribution): Registration {
                val registration =
                    actions.register(
                        owner,
                        owner.info.id.qualify(contribution.id),
                        contribution,
                    )
                refresh()
                return RefreshingRegistration(registration, ::refresh)
            }

            override fun panels(): List<RegisteredModEntry<UiPanelContribution>> = panels.snapshot()

            override fun actions(): List<RegisteredModEntry<UiActionContribution>> = actions.snapshot()
        }

    fun panels(): List<RegisteredModEntry<UiPanelContribution>> = panels.snapshot()

    fun actions(): List<RegisteredModEntry<UiActionContribution>> = actions.snapshot()

    fun refresh() {
        mutableSnapshot.value = ModUiSnapshot(panels(), actions())
    }

    fun clear() {
        panels.clear()
        actions.clear()
        refresh()
    }

    private class RefreshingRegistration(
        private val delegate: Registration,
        private val onChanged: () -> Unit,
    ) : Registration {
        override val id: String
            get() = delegate.id
        override val owner: ModRunId
            get() = delegate.owner
        override val kind: ModContributionKind
            get() = delegate.kind
        override val isActive: Boolean
            get() = delegate.isActive

        override fun unregister() {
            delegate.unregister()
            onChanged()
        }
    }
}
