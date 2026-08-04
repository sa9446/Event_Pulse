package com.bitchat.android

import android.app.Application
import com.bitchat.android.nostr.RelayDirectory
import com.bitchat.android.ui.theme.ThemePreferenceManager
import com.bitchat.android.net.ArtiTorManager
import com.eventpulse.mesh.EventPulseMediaChunker
import com.eventpulse.mesh.EventPulseRetractionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Main application class for Dead Air (forked from bitchat).
 *
 * PERF: Startup-critical init runs synchronously on the main thread (Tor, PowerManager).
 * PERF: Non-critical init deferred to background IO thread so UI becomes interactive faster.
 */
class BitchatApplication : Application() {

    /** Background scope for deferred non-critical initialization. */
    private val deferredInitScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()

        // ── CRITICAL PATH: Must complete before service starts ─────────
        startCriticalServices()

        // ── DEFERRED PATH: Can finish after UI is interactive ──────────
        deferredInitScope.launch {
            startDeferredServices()
        }
    }

    /**
     * Synchronous startup — only what the mesh and UI need immediately.
     */
    private fun startCriticalServices() {
        // Start power policy before any transport components
        try {
            com.bitchat.android.mesh.PowerManager.getInstance(this).start()
        } catch (_: Exception) {}

        // Initialize Tor first so early network goes over Tor
        try {
            ArtiTorManager.getInstance().init(this)
        } catch (_: Exception) {}

        // Initialize relay directory (needed for Nostr subscriptions)
        try {
            RelayDirectory.initialize(this)
        } catch (_: Exception) {}

        // Restore private conversations before transports deliver new messages
        try {
            com.bitchat.android.services.AppStateStore.initializeConversationPersistence(this)
        } catch (_: Exception) {}

        // Theme preference (needed for first frame rendering)
        try {
            ThemePreferenceManager.init(this)
        } catch (_: Exception) {}
    }

    /**
     * Non-critical initialization — deferred to a background coroutine
     * so the splash screen and first frame render faster.
     */
    private suspend fun startDeferredServices() {
        // Location Notes (sheet subscriptions)
        try {
            com.bitchat.android.nostr.LocationNotesInitializer.initialize(this)
        } catch (_: Exception) {}

        // Favorites persistence (MessageRouter/NostrTransport)
        try {
            com.bitchat.android.favorites.FavoritesPersistenceService.initialize(this)
        } catch (_: Exception) {}

        // Warm up Nostr identity
        try {
            com.bitchat.android.nostr.NostrIdentityBridge.getCurrentNostrIdentity(this)
        } catch (_: Exception) {}

        // Debug preferences
        try {
            com.bitchat.android.ui.debug.DebugPreferenceManager.init(this)
        } catch (_: Exception) {}

        // Wi-Fi Aware controller (auto-enabled on supported devices; can be toggled in debug)
        try {
            val enabled = com.bitchat.android.ui.debug.DebugPreferenceManager.getWifiAwareEnabled(true)
            com.bitchat.android.wifiaware.WifiAwareController.initialize(this, enabled)
        } catch (_: Exception) {}

        // Wi-Fi Direct (P2P) controller - runs in parallel with BLE + Aware for high-bandwidth data
        try {
            val enabled = com.bitchat.android.ui.debug.DebugPreferenceManager.getWifiDirectEnabled(true)
            com.bitchat.android.wifidirect.WifiDirectController.initialize(this, enabled)
        } catch (_: Exception) {}

        // Geohash registries
        try {
            com.bitchat.android.nostr.GeohashAliasRegistry.initialize(this)
            com.bitchat.android.nostr.GeohashConversationRegistry.initialize(this)
        } catch (_: Exception) {}

        // Nostr background runtime
        try {
            com.bitchat.android.nostr.NostrBackgroundRuntime.initialize(this)
        } catch (_: Exception) {}

        // Mesh service preferences
        try {
            com.bitchat.android.service.MeshServicePreferences.init(this)
        } catch (_: Exception) {}

        // Proactively start the foreground service to keep mesh alive
        // Must be on main thread for Context.startService()
        withContext(Dispatchers.Main) {
            try {
                com.bitchat.android.service.MeshForegroundService.start(this@BitchatApplication)
            } catch (_: Exception) {}
        }

        // ── Dead Air Feature Init ────────────────────────────────────
        // Start media chunker buffer cleanup scheduler
        EventPulseMediaChunker.startCleanupScheduler(deferredInitScope)
    }

    override fun onTerminate() {
        EventPulseMediaChunker.stopCleanupScheduler()
        super.onTerminate()
    }
}
