package dev.xuexue.pi.coding

import kotlin.time.Clock

enum class TimingNamespace {
    MAIN,
    EXTENSIONS,
}

private data class TimingEntry(
    val label: String,
    val ms: Long,
)

private data class TimingNamespaceState(
    val timings: MutableList<TimingEntry> = mutableListOf(),
    var lastTime: Long = 0,
)

private val timingNamespaces = mutableMapOf<TimingNamespace, TimingNamespaceState>()

private var enabled: Boolean = false

/** Enable timing instrumentation (PI_TIMING=1 equivalent). Must be called before first use. */
fun enableTimings() {
    enabled = true
}

fun resetTimings(namespace: TimingNamespace = TimingNamespace.MAIN) {
    if (!enabled) return
    timingNamespaces[namespace] = TimingNamespaceState(lastTime = Clock.System.now().toEpochMilliseconds())
}

fun time(
    label: String,
    namespace: TimingNamespace = TimingNamespace.MAIN,
) {
    if (!enabled) return
    val now = Clock.System.now().toEpochMilliseconds()
    val state = timingNamespaces.getOrPut(namespace) { TimingNamespaceState() }
    if (state.lastTime == 0L) state.lastTime = now
    state.timings += TimingEntry(label, now - state.lastTime)
    state.lastTime = now
}

private fun printTimingGroup(
    title: String,
    timings: List<TimingEntry>,
) {
    val printable = timings.filter { it.ms >= 0 }
    if (printable.isEmpty()) return
    println()
    println("--- " + title + " ---")
    for (timing in printable) {
        println("  " + timing.label + ": " + timing.ms + "ms")
    }
    println("  TOTAL: " + printable.sumOf { it.ms } + "ms")
    println("-".repeat(title.length + 8))
    println()
}

fun printTimings() {
    if (!enabled) return
    for ((namespace, state) in timingNamespaces) {
        printTimingGroup("Startup Timings: " + namespace.name.lowercase(), state.timings)
    }
}