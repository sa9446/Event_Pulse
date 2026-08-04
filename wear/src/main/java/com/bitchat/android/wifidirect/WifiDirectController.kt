package com.bitchat.android.wifidirect

/**
 * Wear shim for the phone's WifiDirectController.
 *
 * Referenced only by the shared `DebugSettingsManager` debug-UI toggle. Wi-Fi Direct is out of
 * scope for the watch (Bluetooth mesh only), so this is a no-op.
 */
object WifiDirectController {
    fun setEnabled(value: Boolean) = Unit
}
