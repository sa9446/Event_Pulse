package com.bitchat.android.mesh

import com.bitchat.android.model.BitchatFilePacket
import com.bitchat.android.model.NoisePayloadType

/**
 * Transport-agnostic mesh service API for UI and routing layers.
 */
interface MeshService {
    val myPeerID: String
    var delegate: MeshDelegate?

    fun startServices()
    fun stopServices()

    fun sendMessage(content: String, mentions: List<String> = emptyList(), channel: String? = null)
    fun sendPrivateMessage(content: String, recipientPeerID: String, recipientNickname: String, messageID: String? = null)
    fun sendReadReceipt(messageID: String, recipientPeerID: String, readerNickname: String)
    fun sendDeliveryAck(messageID: String, recipientPeerID: String) {}
    fun sendFavoriteNotification(peerID: String, isFavorite: Boolean) {}
    fun sendVerifyChallenge(peerID: String, noiseKeyHex: String, nonceA: ByteArray)
    fun sendVerifyResponse(peerID: String, noiseKeyHex: String, nonceA: ByteArray)

    /**
     * Send a real-time call control signal (CALL_INVITE/ACCEPT/REJECT/END) as an encrypted
     * Noise payload. Defaults to a no-op; transports that can reach the peer override it.
     */
    fun sendCallSignal(peerID: String, signalType: NoisePayloadType, payload: ByteArray) {}

    /**
     * Send one real-time call media frame to [peerID]. Returns true when the frame was written
     * to a transport socket. Defaults to false (unsupported — e.g. BLE-only transports).
     */
    fun sendCallMedia(peerID: String, frame: ByteArray): Boolean = false

    /**
     * True when real-time calling is viable for [peerID] (a high-bandwidth Wi-Fi link exists).
     * Defaults to false; the unified mesh service is the authority.
     */
    fun isPeerCallCapable(peerID: String): Boolean = false

    fun sendFileBroadcast(file: BitchatFilePacket)
    fun sendFilePrivate(recipientPeerID: String, file: BitchatFilePacket)
    fun prepareFilePrivate(
        recipientPeerID: String,
        file: BitchatFilePacket,
        transferId: String,
        allowLegacyFallback: Boolean
    ): PrivateMediaPreparation
    fun cancelFileTransfer(transferId: String): Boolean

    fun sendBroadcastAnnounce()
    fun sendAnnouncementToPeer(peerID: String)

    fun getPeerNicknames(): Map<String, String>
    fun getPeerRSSI(): Map<String, Int>
    fun getActivePeerCount(): Int
    fun hasEstablishedSession(peerID: String): Boolean
    fun getSessionState(peerID: String): com.bitchat.android.noise.NoiseSession.NoiseSessionState
    fun isSessionPostQuantum(peerID: String): Boolean
    fun initiateNoiseHandshake(peerID: String)
    fun getPeerFingerprint(peerID: String): String?
    fun getPeerInfo(peerID: String): PeerInfo?

    /**
     * True when [peerID] is currently reachable only over BLE (no Wi-Fi Aware or Wi-Fi Direct
     * link). Used to pick a more aggressive image-compression tier for slow BLE-only transfers.
     * Defaults to false; the unified mesh service is the authority on transport selection.
     */
    fun isPeerBleOnly(peerID: String): Boolean = false

    fun updatePeerInfo(
        peerID: String,
        nickname: String,
        noisePublicKey: ByteArray,
        signingPublicKey: ByteArray,
        isVerified: Boolean
    ): Boolean
    fun getIdentityFingerprint(): String
    fun getStaticNoisePublicKey(): ByteArray?
    fun shouldShowEncryptionIcon(peerID: String): Boolean
    fun getEncryptedPeers(): List<String>

    fun getDeviceAddressForPeer(peerID: String): String?
    fun getDeviceAddressToPeerMapping(): Map<String, String>
    fun printDeviceAddressesForPeers(): String
    fun getDebugStatus(): String

    fun clearAllInternalData()
    fun clearAllEncryptionData()
}
