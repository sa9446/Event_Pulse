package com.bitchat.android.service

import android.content.Context
import android.content.SharedPreferences

object MeshServicePreferences {
    private const val PREFS_NAME = "bitchat_mesh_service_prefs"
    private const val KEY_AUTO_START = "auto_start_on_boot"
    private const val KEY_BACKGROUND_ENABLED = "background_enabled"
    private const val KEY_POST_QUANTUM_ENABLED = "post_quantum_enabled"
    private const val KEY_BLOCK_CLASSIC = "block_classic_handshakes"

    private var appContext: Context? = null
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Lazily (re)initialize from the stored application context if the service was
     * started before [init] ran. Accessors are safe to call from any entry point
     * (service start, boot receiver, UI) without a prior explicit init.
     */
    private fun ensureInitialized() {
        if (!::prefs.isInitialized) {
            appContext?.let { init(it) }
        }
    }

    fun isAutoStartEnabled(default: Boolean = true): Boolean {
        ensureInitialized()
        return prefs.getBoolean(KEY_AUTO_START, default)
    }

    fun setAutoStartEnabled(enabled: Boolean) {
        ensureInitialized()
        prefs.edit().putBoolean(KEY_AUTO_START, enabled).apply()
    }

    fun isBackgroundEnabled(default: Boolean = true): Boolean {
        ensureInitialized()
        return prefs.getBoolean(KEY_BACKGROUND_ENABLED, default)
    }

    fun setBackgroundEnabled(enabled: Boolean) {
        ensureInitialized()
        prefs.edit().putBoolean(KEY_BACKGROUND_ENABLED, enabled).apply()
    }

    /**
     * Whether new Noise handshakes use the hybrid post-quantum protocol
     * (Noise_XXhfs_25519+MLKEM768_ChaChaPoly_SHA256). Defaults to on; turning it off falls back
     * to the classic X25519 protocol for interoperability with older builds.
     */
    fun isPostQuantumEnabled(default: Boolean = true): Boolean {
        ensureInitialized()
        return prefs.getBoolean(KEY_POST_QUANTUM_ENABLED, default)
    }

    fun setPostQuantumEnabled(enabled: Boolean) {
        ensureInitialized()
        prefs.edit().putBoolean(KEY_POST_QUANTUM_ENABLED, enabled).apply()
    }

    /**
     * Whether classic X25519-only handshakes are refused outright while post-quantum is enabled.
     * Defaults to on so every negotiated session is genuinely post-quantum; turn it off to allow
     * legacy classic-only builds to connect (and be transparently shown as non-quantum in the UI).
     */
    fun isClassicHandshakeBlocked(default: Boolean = true): Boolean {
        ensureInitialized()
        return prefs.getBoolean(KEY_BLOCK_CLASSIC, default)
    }

    fun setClassicHandshakeBlocked(blocked: Boolean) {
        ensureInitialized()
        prefs.edit().putBoolean(KEY_BLOCK_CLASSIC, blocked).apply()
    }
}
