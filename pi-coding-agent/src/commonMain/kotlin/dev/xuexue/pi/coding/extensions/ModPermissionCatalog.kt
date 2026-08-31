@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package dev.xuexue.pi.coding.extensions

import dev.xuexue.pi.coding.InteractionPort
import dev.xuexue.pi.coding.InteractionRequest
import dev.xuexue.pi.coding.InteractionResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.atomics.AtomicReference

/** Permission decision retained for one immutable mod package version. */
data class ModPermissionGrant(
    val modId: ModId,
    val version: ModVersion,
    val permission: String,
    val decision: PermissionDecision,
)

/** Central approval store shared by all runs in a Mod Loader. */
class ModPermissionCatalog(
    private val interaction: InteractionPort,
) {
    private val decisions = AtomicReference<Map<PermissionKey, PermissionDecision>>(emptyMap())
    private val mutableGrants = MutableStateFlow<List<ModPermissionGrant>>(emptyList())

    val grants: StateFlow<List<ModPermissionGrant>> = mutableGrants.asStateFlow()

    fun forRun(
        run: ModRun,
        report: (String) -> Unit,
    ): PermissionBroker =
        object : PermissionBroker {
            override fun check(permission: String): PermissionDecision {
                run.requireActive()
                return check(run, permission)
            }

            override suspend fun request(permission: String): PermissionDecision {
                run.requireActive()
                val initial = check(run, permission)
                if (initial != PermissionDecision.UNDECIDED) return initial
                val response =
                    interaction.request(
                        InteractionRequest.Confirm(
                            id = "mod-permission-${run.runId.value}-${permission.replace('.', '-')}",
                            title = "允许插件权限",
                            message = "插件 ${run.info.id} ${run.info.version} 请求权限：$permission",
                            confirmLabel = "允许",
                            cancelLabel = "拒绝",
                        ),
                    )
                val decision =
                    if (response is InteractionResponse.Confirm && response.accepted) {
                        PermissionDecision.GRANTED
                    } else {
                        PermissionDecision.DENIED
                    }
                store(run, permission, decision)
                if (decision == PermissionDecision.DENIED) {
                    report("Permission '$permission' was denied")
                }
                return decision
            }
        }

    private fun check(
        run: ModRun,
        permission: String,
    ): PermissionDecision {
        val normalized = normalize(permission)
        if (!run.info.trusted || normalized !in run.packageInfo.permissions) return PermissionDecision.DENIED
        return decisions.load()[PermissionKey(run.packageInfo.id, run.packageInfo.version, normalized)]
            ?: PermissionDecision.UNDECIDED
    }

    private fun store(
        run: ModRun,
        permission: String,
        decision: PermissionDecision,
    ) {
        val key = PermissionKey(run.packageInfo.id, run.packageInfo.version, normalize(permission))
        while (true) {
            val current = decisions.load()
            val next = current + (key to decision)
            if (decisions.compareAndSet(current, next)) {
                mutableGrants.value =
                    next
                        .map { (entry, value) ->
                            ModPermissionGrant(entry.id, entry.version, entry.permission, value)
                        }.sortedWith(compareBy({ it.modId.value }, { it.version }, { it.permission }))
                return
            }
        }
    }

    private fun normalize(permission: String): String {
        val normalized = permission.trim()
        require(normalized.isNotEmpty()) { "Permission must not be blank" }
        return normalized
    }

    private data class PermissionKey(
        val id: ModId,
        val version: ModVersion,
        val permission: String,
    )
}
