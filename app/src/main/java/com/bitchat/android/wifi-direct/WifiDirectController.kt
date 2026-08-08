package com.bitchat.android.wifidirect

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * WifiDirectController manages lifecycle and debug surfacing for the WifiDirectMeshService.
 *
 * Wi-Fi is the primary transport: Direct provides a high-bandwidth P2P medium (no access point)
 * alongside Wi-Fi Aware, with BLE running as a secondary fallback for discovery.
 */
object WifiDirectController {
    private const val TAG = "WifiDirectController"

    private var service: WifiDirectMeshService? = null
    private var appContext: Context? = null
    private val lifecycleLock = Any()
    private var starting = false

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _supported = MutableStateFlow(false)
    val supported: StateFlow<Boolean> = _supported.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    // Simple debug surfacing
    private val _connectedPeers = MutableStateFlow<Map<String, String>>(emptyMap()) // peerID -> ip
    val connectedPeers: StateFlow<Map<String, String>> = _connectedPeers.asStateFlow()

    private val _knownPeers = MutableStateFlow<Map<String, String>>(emptyMap()) // peerID -> nickname
    val knownPeers: StateFlow<Map<String, String>> = _knownPeers.asStateFlow()

    private val _discoveredPeers = MutableStateFlow<Set<String>>(emptySet())
    val discoveredPeers: StateFlow<Set<String>> = _discoveredPeers.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun initialize(context: Context, enabledByDefault: Boolean) {
        appContext = context.applicationContext
        _supported.value = WifiDirectSupport.isSupported(context)
        setEnabled(enabledByDefault)
    }

    internal fun publishDebugSnapshot(
        connected: Map<String, String>,
        known: Map<String, String>,
        discovered: Set<String>
    ) {
        _connectedPeers.value = connected
        _knownPeers.value = known
        _discoveredPeers.value = discovered
    }

    private fun clearDebugSnapshot() {
        publishDebugSnapshot(emptyMap(), emptyMap(), emptySet())
    }

    fun setEnabled(value: Boolean) {
        _enabled.value = value
        if (value) startIfPossible() else stop()
    }

    fun startIfPossible() {
        val reusableService = synchronized(lifecycleLock) {
            if (!_enabled.value) return
            val existing = service
            if (existing?.isRunning() == true) {
                _running.value = true
                return
            }
            if (starting) return
            starting = true
            existing
        }

        val ctx = appContext ?: run {
            synchronized(lifecycleLock) { starting = false }
            return
        }

        val status = WifiDirectSupport.evaluate(ctx)
        _supported.value = status.supported
        if (!status.supported) {
            Log.w(TAG, "Wi-Fi Direct unsupported; not starting (${status.reason})")
            synchronized(lifecycleLock) { starting = false }
            return
        }

        if (!_enabled.value) {
            synchronized(lifecycleLock) { starting = false }
            return
        }

        try {
            val startedService = reusableService ?: run {
                Log.i(TAG, "Instantiating WifiDirectMeshService...")
                WifiDirectMeshService(ctx)
            }
            startedService.startServices()
            synchronized(lifecycleLock) {
                val canPublish = startedService.isRunning()
                if (canPublish) {
                    service = startedService
                    _running.value = true
                } else {
                    if (service === startedService) service = null
                    _running.value = false
                }
                canPublish
            }.also { published ->
                if (published) {
                    try { com.bitchat.android.service.MeshServiceHolder.unifiedMeshService?.refreshDelegates() } catch (_: Exception) { }
                    try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().addDebugMessage(com.bitchat.android.ui.debug.DebugMessage.SystemMessage("Wi‑Fi Direct started")) } catch (_: Exception) {}
                } else {
                    try { startedService.stopServices() } catch (_: Exception) { }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start WifiDirectMeshService", e)
            _running.value = false
            try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().addDebugMessage(com.bitchat.android.ui.debug.DebugMessage.SystemMessage("Wi‑Fi Direct failed to start: ${e.message}")) } catch (_: Exception) {}
        } finally {
            synchronized(lifecycleLock) { starting = false }
        }
    }

    fun stop() {
        val stopped = synchronized(lifecycleLock) {
            val current = service
            service = null
            starting = false
            _running.value = false
            current
        }
        try { stopped?.stopServices() } catch (_: Exception) { }
        try { com.bitchat.android.services.AppStateStore.clearTransportPeers("P2P") } catch (_: Exception) { }
        clearDebugSnapshot()
        try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().addDebugMessage(com.bitchat.android.ui.debug.DebugMessage.SystemMessage("Wi‑Fi Direct stopped")) } catch (_: Exception) {}
    }

    internal fun onServiceStopped(stoppedService: WifiDirectMeshService) {
        synchronized(lifecycleLock) {
            if (service !== stoppedService) return
            service = null
            _running.value = false
            try { com.bitchat.android.services.AppStateStore.clearTransportPeers("P2P") } catch (_: Exception) { }
            clearDebugSnapshot()
        }
    }

    fun getService(): WifiDirectMeshService? = service

    /**
     * Test seam: swaps in a fake/stubbed transport service (or clears it with null).
     * Production code routes exclusively through [startIfPossible]/[stop]; this exists so
     * unit tests can exercise transport selection without driving the real Wi-Fi stack.
     */
    @VisibleForTesting
    internal fun setServiceForTest(service: WifiDirectMeshService?) {
        synchronized(lifecycleLock) { this.service = service }
    }
}
