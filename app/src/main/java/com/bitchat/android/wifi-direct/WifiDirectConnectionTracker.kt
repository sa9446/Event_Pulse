package com.bitchat.android.wifidirect

import android.util.Log
import com.bitchat.android.wifiaware.SyncedSocket
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks Wi-Fi Direct (P2P) connections. Simpler than the Wi-Fi Aware tracker because P2P has a
 * single group owner server socket instead of per-peer network requests.
 */
class WifiDirectConnectionTracker {
    companion object {
        private const val TAG = "WifiDirectConnectionTracker"
    }

    val peerSockets = ConcurrentHashMap<String, SyncedSocket>()

    fun isConnected(id: String): Boolean = getSocketForPeer(id) != null

    fun getConnectionCount(): Int = peerSockets.size

    fun onClientConnected(peerId: String, socket: SyncedSocket) {
        peerSockets[peerId]?.let {
            try { it.close() } catch (_: Exception) {}
        }
        peerSockets[peerId] = socket
        Log.d(TAG, "Connected to $peerId (${peerId.take(8)})")
    }

    fun getSocketForPeer(peerId: String): SyncedSocket? = peerSockets[peerId]

    fun disconnect(id: String) {
        peerSockets.remove(id)?.let {
            try { it.close() } catch (e: Exception) { Log.w(TAG, "Error closing socket for $id: ${e.message}") }
        }
    }

    fun stop() {
        peerSockets.keys.toList().forEach { disconnect(it) }
    }

    fun getDebugInfo(): String {
        return buildString {
            appendLine("P2P Connections: ${getConnectionCount()}")
            peerSockets.keys.forEach { pid ->
                appendLine("  - $pid")
            }
        }
    }

    /** Raw access for keep-alive loops. */
    fun forEachSocket(action: (String, SyncedSocket) -> Unit) {
        peerSockets.forEach { (id, sock) ->
            try { action(id, sock) } catch (_: Exception) {}
        }
    }
}
