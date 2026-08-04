package com.bitchat.android.wifidirect

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build

/**
 * Centralized Wi-Fi Direct (P2P) capability checks.
 *
 * "Supported" is stable device/API capability. "Available" is runtime state — Wi-Fi P2P can
 * be temporarily unavailable while the radio is owned by another feature (hotspot, NAN, etc.).
 */
object WifiDirectSupport {
    data class Status(
        val supported: Boolean,
        val available: Boolean,
        val reason: String? = null
    )

    fun evaluate(context: Context): Status {
        val appContext = context.applicationContext

        val hasFeature = try {
            appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
        } catch (_: Exception) {
            false
        }
        if (!hasFeature) {
            return Status(
                supported = false,
                available = false,
                reason = "device does not advertise Wi-Fi Direct support"
            )
        }

        // Wi-Fi Direct has existed since API 14; P2P peers API is stable on all supported versions.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            return Status(
                supported = false,
                available = false,
                reason = "Wi-Fi Direct requires Android 4.4+"
            )
        }

        val manager = getManager(appContext)
            ?: return Status(
                supported = false,
                available = false,
                reason = "WifiP2pManager unavailable"
            )

        // P2PManager has no isAvailable() until API 34; treat a non-null manager as available and
        // let the transport surface radio contention through createGroup()/discoverPeers() errors.
        return Status(
            supported = true,
            available = true,
            reason = null
        )
    }

    fun isSupported(context: Context): Boolean = evaluate(context).supported

    fun getManager(context: Context): WifiP2pManager? {
        return try {
            context.applicationContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        } catch (_: Exception) {
            null
        }
    }
}
