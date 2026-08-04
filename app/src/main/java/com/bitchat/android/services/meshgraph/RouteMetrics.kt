package com.bitchat.android.services.meshgraph

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Live multi-hop routing metrics, accumulated app-wide and exposed to the debug
 * UI. Updated from three sources:
 *
 *  - [RoutePlanner] — every path computation records the hop count and detects
 *    path changes per (source, destination) pair.
 *  - `TransportBridgeService.sendToPeer` — per-transport unicast relay results
 *    (which transport actually accepted a next-hop write).
 *  - `PacketRelayManager` — source-routed drops and fallback floods.
 *
 * StateFlow updates are coalesced to ~5 Hz with a trailing-edge debounce so busy
 * mesh relays do not allocate a fresh snapshot per packet. State is always
 * captured under a lock; [flush] forces the accumulated state out synchronously
 * (used by tests and the debug test hook).
 */
object RouteMetrics {
    private const val PUBLISH_INTERVAL_MS = 200L

    data class Snapshot(
        val relaySuccessByTransport: Map<String, Int> = emptyMap(),
        val relayFailureByTransport: Map<String, Int> = emptyMap(),
        val routedDrops: Int = 0,
        val fallbackFloods: Int = 0,
        val pathChanges: Int = 0,
        /** hop count -> number of distinct routes computed with that length (1+). */
        val hopCountHistogram: Map<Int, Int> = emptyMap(),
        /** number of (source, destination) pairs currently planned with >= 2 hops. */
        val routesKnown: Int = 0
    )

    private val lock = Any()
    private val flushScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _metrics = MutableStateFlow(Snapshot())
    val metrics: StateFlow<Snapshot> = _metrics.asStateFlow()

    private var relaySuccess = mutableMapOf<String, Int>()
    private var relayFailure = mutableMapOf<String, Int>()
    private var routedDrops = 0
    private var fallbackFloods = 0
    private var pathChanges = 0
    private var hopHistogram = mutableMapOf<Int, Int>()
    private val lastPaths = mutableMapOf<String, List<String>>()

    // Coalescing state: events mark `dirty`; a single debounce loop publishes.
    private var dirty = false
    private var flushJob: Job? = null

    /** Record one relay unicast outcome for a transport (e.g. "BLE", "WIFI"). */
    fun recordRelay(transportId: String, success: Boolean) {
        synchronized(lock) {
            val map = if (success) relaySuccess else relayFailure
            map[transportId] = (map[transportId] ?: 0) + 1
            schedulePublish()
        }
    }

    /** A source-routed packet was dropped (off-route, duplicate hops, expired TTL). */
    fun recordRoutedDrop() {
        synchronized(lock) {
            routedDrops++
            schedulePublish()
        }
    }

    /** A source-route next hop was unreachable and the packet degraded to a flood. */
    fun recordFallbackFlood() {
        synchronized(lock) {
            fallbackFloods++
            schedulePublish()
        }
    }

    /**
     * Record the outcome of one path computation. The histogram and path-change
     * counter only advance when the path for a given (src, dst) actually changes,
     * so repeated recomputation of an unchanged topology does not pollute them.
     * Hop counts are edges on the path (path.size - 1), so an A→B→C route counts
     * as 2 hops.
     */
    fun recordPath(src: String, dst: String, path: List<String>?) {
        synchronized(lock) {
            val key = "$src|$dst"
            val previous = lastPaths[key]
            if (previous == path) return
            lastPaths[key] = path ?: emptyList()
            if (previous != null) pathChanges++
            if (path != null) {
                val hops = path.size - 1
                if (hops >= 1) {
                    hopHistogram[hops] = (hopHistogram[hops] ?: 0) + 1
                }
            }
            schedulePublish()
        }
    }

    /**
     * Force-publish the accumulated state and return it. Used by tests and the
     * debug test hook (route_metrics) so callers never read a stale coalesced
     * value.
     */
    fun flush(): Snapshot {
        // Assign inside the lock so a concurrent reset() can never be followed by a
        // stale out-of-lock publish from an in-flight loop iteration.
        return synchronized(lock) {
            dirty = false
            buildSnapshot().also { _metrics.value = it }
        }
    }

    fun reset() {
        synchronized(lock) {
            relaySuccess.clear()
            relayFailure.clear()
            routedDrops = 0
            fallbackFloods = 0
            pathChanges = 0
            hopHistogram.clear()
            lastPaths.clear()
            dirty = false
            flushJob?.cancel()
            flushJob = null
            _metrics.value = buildSnapshot()
        }
    }

    /** Single-line-friendly debug dump for getDebugStatus()/state dumps. */
    fun debugSummary(): String = buildString {
        val s = metrics.value
        appendLine("=== Route Metrics ===")
        if (s.relaySuccessByTransport.isEmpty() && s.relayFailureByTransport.isEmpty()) {
            appendLine("No relay traffic yet")
        } else {
            (s.relaySuccessByTransport.keys + s.relayFailureByTransport.keys)
                .distinct()
                .sorted()
                .forEach { id ->
                    appendLine(
                        "$id: success=${s.relaySuccessByTransport[id] ?: 0} failure=${s.relayFailureByTransport[id] ?: 0}"
                    )
                }
        }
        appendLine("Routes planned: ${s.routesKnown}, path changes: ${s.pathChanges}")
        val hist = s.hopCountHistogram.entries.sortedBy { it.key }
        appendLine(
            if (hist.isEmpty()) "Hop profile: none"
            else "Hop profile: " + hist.joinToString(", ") { "${it.key}-hop x ${it.value}" }
        )
        appendLine("Routed drops: ${s.routedDrops}, fallback floods: ${s.fallbackFloods}")
    }

    /**
     * Marks state dirty and ensures the single trailing-edge debounce loop is
     * running. The loop publishes every [PUBLISH_INTERVAL_MS] while events keep
     * arriving and exits once the state is quiescent, so the final event always
     * lands at most one interval later.
     */
    private fun schedulePublish() {
        dirty = true
        if (flushJob?.isActive == true) return
        flushJob = flushScope.launch {
            while (true) {
                delay(PUBLISH_INTERVAL_MS)
                val published = synchronized(lock) {
                    if (!dirty) {
                        false
                    } else {
                        dirty = false
                        _metrics.value = buildSnapshot()
                        true
                    }
                }
                if (!published) return@launch
            }
        }
    }

    private fun buildSnapshot(): Snapshot {
        // Distinct sources with at least one live 2+ hop plan.
        val sourcesWithRoutes = lastPaths.entries
            .filter { it.value.size >= 2 }
            .map { it.key.substringBefore('|') }
            .toSet()
        return Snapshot(
            relaySuccessByTransport = relaySuccess.toMap(),
            relayFailureByTransport = relayFailure.toMap(),
            routedDrops = routedDrops,
            fallbackFloods = fallbackFloods,
            pathChanges = pathChanges,
            hopCountHistogram = hopHistogram.toMap(),
            routesKnown = sourcesWithRoutes.size
        )
    }
}
