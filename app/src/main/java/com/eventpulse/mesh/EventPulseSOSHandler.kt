package com.eventpulse.mesh

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Feature D: Priority SOS/Emergency Flag
 *
 * - SOS quick-action button in the UI sets "type": "SOS" in the payload.
 * - Incoming SOS packets bypass channel filters and trigger a top-level alert banner
 *   on all connected mesh devices.
 *
 * Thread-safe: uses CopyOnWriteArrayList + StateFlow for reactive UI observation.
 * UI subscribes to [activeAlertsFlow] instead of polling [activeAlerts].
 */

/**
 * Represents an incoming SOS alert received over the mesh.
 */
data class SOSAlert(
    /** The sender's display name. */
    val senderName: String,

    /** The sender's peer ID for identification. */
    val senderPeerID: String,

    /** The SOS message body. */
    val message: String,

    /** When the SOS was received (system time millis). */
    val receivedAt: Long = System.currentTimeMillis()
)

/**
 * Manages SOS alerts state with thread-safe operations and reactive StateFlow.
 */
object EventPulseSOSHandler {

    private const val TAG = "EventPulseSOSHandler"

    /** Thread-safe alert list using CopyOnWriteArrayList for concurrent read/write. */
    private val _activeAlerts = CopyOnWriteArrayList<SOSAlert>()

    /** Reactive state flow for UI observation (replaces polling activeAlerts). */
    private val _activeAlertsFlow = MutableStateFlow<List<SOSAlert>>(emptyList())

    /** Observable state flow for Compose UI to collect. */
    val activeAlertsFlow: StateFlow<List<SOSAlert>> = _activeAlertsFlow.asStateFlow()

    /** Snapshot of active alerts (for non-Compose use). */
    val activeAlerts: List<SOSAlert> get() = _activeAlerts.toList()

    /** Callback invoked when a new SOS is received (for toast/banner). */
    var onSOSReceived: ((SOSAlert) -> Unit)? = null

    /** Callback invoked when all alerts have been cleared. */
    var onAllAlertsCleared: (() -> Unit)? = null

    /** Dedup window for same sender+message (milliseconds). */
    private const val DEDUP_WINDOW_MS = 60_000L

    /**
     * Register a new SOS alert from an incoming mesh message.
     * Returns true if this is a new alert, false if it's a duplicate.
     */
    fun registerSOS(alert: SOSAlert): Boolean {
        val now = System.currentTimeMillis()

        // Deduplicate by sender + message within dedup window
        val isDuplicate = _activeAlerts.any {
            it.senderPeerID == alert.senderPeerID &&
            it.message == alert.message &&
            (now - it.receivedAt) < DEDUP_WINDOW_MS
        }
        if (isDuplicate) {
            Log.v(TAG, "Dropping duplicate SOS from ${alert.senderName}")
            return false
        }

        _activeAlerts.add(alert)
        _activeAlertsFlow.value = _activeAlerts.toList()
        Log.i(TAG, "SOS alert from ${alert.senderName}: \"${alert.message.take(50)}\" (${_activeAlerts.size} active)")
        onSOSReceived?.invoke(alert)
        return true
    }

    /**
     * Dismiss a specific SOS alert by index.
     */
    fun dismissAlert(index: Int) {
        if (index in _activeAlerts.indices) {
            val removed = _activeAlerts.removeAt(index)
            _activeAlertsFlow.value = _activeAlerts.toList()
            Log.d(TAG, "Dismissed SOS from ${removed.senderName} (${_activeAlerts.size} remaining)")
            if (_activeAlerts.isEmpty()) {
                onAllAlertsCleared?.invoke()
            }
        }
    }

    /**
     * Dismiss a specific SOS alert by sender peer ID (for programmatic dismissal).
     */
    fun dismissAlertBySender(senderPeerID: String) {
        val removed = _activeAlerts.removeAll { it.senderPeerID == senderPeerID }
        if (removed) {
            _activeAlertsFlow.value = _activeAlerts.toList()
            if (_activeAlerts.isEmpty()) {
                onAllAlertsCleared?.invoke()
            }
        }
    }

    /**
     * Dismiss all active SOS alerts.
     */
    fun dismissAllAlerts() {
        _activeAlerts.clear()
        _activeAlertsFlow.value = emptyList()
        Log.d(TAG, "All SOS alerts dismissed")
        onAllAlertsCleared?.invoke()
    }

    /**
     * Get the current alert count.
     */
    val alertCount: Int get() = _activeAlerts.size

    /**
     * Clear all state (on panic/identity reset).
     */
    fun clear() {
        _activeAlerts.clear()
        _activeAlertsFlow.value = emptyList()
        onSOSReceived = null
        onAllAlertsCleared = null
    }
}
