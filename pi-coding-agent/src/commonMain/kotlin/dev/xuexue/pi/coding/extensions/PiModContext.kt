package dev.xuexue.pi.coding.extensions

import kotlinx.coroutines.CoroutineScope

/** Context shared by all capability registries exposed to a running mod. */
interface ModContext {
    val mod: ModInfo

    /** Scope cancelled automatically when this mod run stops. */
    val scope: CoroutineScope
}
