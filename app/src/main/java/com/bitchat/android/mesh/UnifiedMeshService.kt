package com.bitchat.android.mesh

import android.content.Context
import android.util.Log
import com.bitchat.android.favorites.FavoriteControlMessage
import com.bitchat.android.model.BitchatFilePacket
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.noise.NoiseSession
import com.bitchat.android.services.meshgraph.RouteMetrics
import com.bitchat.android.wifiaware.WifiAwareController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Feature-facing mesh service that hides local transport selection from the rest of the app.
 *
 * Wi-Fi is the primary transport: Wi-Fi Aware and Wi-Fi Direct carry all traffic (messages,
 * media, handshakes) whenever a peer is reachable on either. BLE stays enabled as a secondary
 * fallback for discovery and for devices without Wi-Fi. Addressed Noise traffic is routed over
 * whichever local transport already has the peer/session, falling back to a connected transport
 * handshake.
 */
class UnifiedMeshService(
    private val context: Context,
    private val bluetooth: BluetoothMeshService
) : MeshService, BluetoothMeshDelegate {

    companion object {
        private const val TAG = "UnifiedMeshService"
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val powerManager = PowerManager.getInstance(context.applicationContext)
    private var announcementJob: Job? = null

    override val myPeerID: String
        get() = bluetooth.myPeerID

    override var delegate: MeshDelegate? = null
        set(value) {
            field = value
            refreshDelegates()
        }

    fun refreshDelegates() {
        try { bluetooth.delegate = if (delegate != null) this else null } catch (_: Exception) { }
        try { wifiService()?.delegate = if (delegate != null) this else null } catch (_: Exception) { }
        try { p2pService()?.delegate = if (delegate != null) this else null } catch (_: Exception) { }
    }

    override fun startServices() {
        // Wi-Fi is the primary transport: start Wi-Fi Aware + Direct first so discovery and
        // handshakes prefer them; BLE remains enabled as a secondary fallback.
        try { WifiAwareController.startIfPossible() } catch (e: Exception) {
            Log.w(TAG, "Failed to start Wi-Fi Aware transport: ${e.message}")
        }
        try { com.bitchat.android.wifidirect.WifiDirectController.startIfPossible() } catch (e: Exception) {
            Log.w(TAG, "Failed to start Wi-Fi Direct transport: ${e.message}")
        }
        if (isBleEnabled()) {
            try { bluetooth.startServices() } catch (e: Exception) {
                Log.w(TAG, "Failed to start BLE transport: ${e.message}")
            }
        } else {
            try { bluetooth.setBleTransportEnabled(false) } catch (_: Exception) { }
        }
        startAnnouncementScheduler()
        refreshDelegates()
    }

    override fun stopServices() {
        announcementJob?.cancel()
        announcementJob = null
        try { bluetooth.stopServices() } catch (_: Exception) { }
        try { WifiAwareController.stop() } catch (_: Exception) { }
        try { com.bitchat.android.wifidirect.WifiDirectController.stop() } catch (_: Exception) { }
    }

    private fun startAnnouncementScheduler() {
        if (announcementJob?.isActive == true) return
        announcementJob = serviceScope.launch {
            powerManager.profile
                .map { profile ->
                    profile.meshAnnouncementIntervalMs to profile.hasDirectPeers
                }
                .distinctUntilChanged()
                .collectLatest { (intervalMs, hasRecipients) ->
                    if (!hasRecipients) return@collectLatest
                    // Connection-specific paths already send an immediate announce. Begin the
                    // periodic cadence after the configured interval to avoid a transition burst.
                    while (isActive) {
                        delay(intervalMs)
                        if (powerManager.profile.value.hasDirectPeers) sendBroadcastAnnounce()
                    }
                }
            }
    }

    override fun sendMessage(content: String, mentions: List<String>, channel: String?) {
        // Broadcasts go out on every active radio (Wi-Fi Aware, Wi-Fi Direct, then BLE) so a
        // message is never stranded on a transport with zero peers — same dual-radio pattern as
        // sendBroadcastAnnounce. Peers on multiple radios dedupe by message ID downstream.
        try { wifiService()?.sendMessage(content, mentions, channel) } catch (_: Exception) { }
        try { p2pService()?.sendMessage(content, mentions, channel) } catch (_: Exception) { }
        if (isBleEnabled()) {
            try { bluetooth.sendMessage(content, mentions, channel) } catch (_: Exception) { }
        }
    }

    override fun sendPrivateMessage(
        content: String,
        recipientPeerID: String,
        recipientNickname: String,
        messageID: String?
    ) {
        when {
            isWifiReady(recipientPeerID) -> wifiService()?.sendPrivateMessage(content, recipientPeerID, recipientNickname, messageID)
            isP2pReady(recipientPeerID) -> p2pService()?.sendPrivateMessage(content, recipientPeerID, recipientNickname, messageID)
            isBleReady(recipientPeerID) -> bluetooth.sendPrivateMessage(content, recipientPeerID, recipientNickname, messageID)
            isWifiConnected(recipientPeerID) -> wifiService()?.sendPrivateMessage(content, recipientPeerID, recipientNickname, messageID)
            isP2pConnected(recipientPeerID) -> p2pService()?.sendPrivateMessage(content, recipientPeerID, recipientNickname, messageID)
            isBleConnected(recipientPeerID) || (isBleEnabled() && !isAnyWifiConnected(recipientPeerID)) ->
                bluetooth.sendPrivateMessage(content, recipientPeerID, recipientNickname, messageID)
            else -> {
                // Multi-hop target (A→B→C): route through the transport that owns the direct
                // link to the first hop so the source-route packet actually leaves this device.
                val viaFirstHop = firstHopTransportFor(recipientPeerID)
                if (viaFirstHop != null) {
                    viaFirstHop.sendPrivateMessage(content, recipientPeerID, recipientNickname, messageID)
                } else {
                    if (isBleEnabled()) {
                        try { bluetooth.sendPrivateMessage(content, recipientPeerID, recipientNickname, messageID) } catch (_: Exception) { }
                    }
                    try { wifiService()?.sendPrivateMessage(content, recipientPeerID, recipientNickname, messageID) } catch (_: Exception) { }
                    try { p2pService()?.sendPrivateMessage(content, recipientPeerID, recipientNickname, messageID) } catch (_: Exception) { }
                }
            }
        }
    }

    override fun sendReadReceipt(messageID: String, recipientPeerID: String, readerNickname: String) {
        when {
            isWifiReady(recipientPeerID) -> wifiService()?.sendReadReceipt(messageID, recipientPeerID, readerNickname)
            isP2pReady(recipientPeerID) -> p2pService()?.sendReadReceipt(messageID, recipientPeerID, readerNickname)
            isBleReady(recipientPeerID) -> bluetooth.sendReadReceipt(messageID, recipientPeerID, readerNickname)
            else -> {
                // 2-hop peer: deliver the receipt along the same source route as the message.
                val viaFirstHop = firstHopTransportFor(recipientPeerID)
                if (viaFirstHop != null) {
                    viaFirstHop.sendReadReceipt(messageID, recipientPeerID, readerNickname)
                } else if (isBleEnabled()) {
                    try { bluetooth.sendReadReceipt(messageID, recipientPeerID, readerNickname) } catch (_: Exception) { }
                }
            }
        }
    }

    override fun sendFavoriteNotification(peerID: String, isFavorite: Boolean) {
        val myNpub = try {
            com.bitchat.android.nostr.NostrIdentityBridge.getCurrentNostrIdentity(context)?.npub
        } catch (_: Exception) {
            null
        }
        val content = FavoriteControlMessage.encode(isFavorite, myNpub)
        val nickname = getPeerNicknames()[peerID] ?: peerID
        if (hasEstablishedSession(peerID)) {
            sendPrivateMessage(content, peerID, nickname, java.util.UUID.randomUUID().toString())
        }
    }

    override fun sendVerifyChallenge(peerID: String, noiseKeyHex: String, nonceA: ByteArray) {
        when {
            isWifiReady(peerID) -> wifiService()?.sendVerifyChallenge(peerID, noiseKeyHex, nonceA)
            isP2pReady(peerID) -> p2pService()?.sendVerifyChallenge(peerID, noiseKeyHex, nonceA)
            isBleReady(peerID) -> bluetooth.sendVerifyChallenge(peerID, noiseKeyHex, nonceA)
            else -> {
                val viaFirstHop = firstHopTransportFor(peerID)
                if (viaFirstHop != null) {
                    viaFirstHop.sendVerifyChallenge(peerID, noiseKeyHex, nonceA)
                } else if (isBleEnabled()) {
                    try { bluetooth.sendVerifyChallenge(peerID, noiseKeyHex, nonceA) } catch (_: Exception) { }
                }
            }
        }
    }

    override fun sendVerifyResponse(peerID: String, noiseKeyHex: String, nonceA: ByteArray) {
        when {
            isWifiReady(peerID) -> wifiService()?.sendVerifyResponse(peerID, noiseKeyHex, nonceA)
            isP2pReady(peerID) -> p2pService()?.sendVerifyResponse(peerID, noiseKeyHex, nonceA)
            isBleReady(peerID) -> bluetooth.sendVerifyResponse(peerID, noiseKeyHex, nonceA)
            else -> {
                val viaFirstHop = firstHopTransportFor(peerID)
                if (viaFirstHop != null) {
                    viaFirstHop.sendVerifyResponse(peerID, noiseKeyHex, nonceA)
                } else if (isBleEnabled()) {
                    try { bluetooth.sendVerifyResponse(peerID, noiseKeyHex, nonceA) } catch (_: Exception) { }
                }
            }
        }
    }

    override fun sendFileBroadcast(file: BitchatFilePacket) {
        // Media prefers the high-bandwidth Wi-Fi paths when any Wi-Fi peers are present;
        // BLE remains the fallback (and the only option when Wi-Fi is off).
        if (hasWifiPeers()) {
            val sentWifi = (wifiService()?.sendFileBroadcast(file) != null) ||
                (p2pService()?.sendFileBroadcast(file) != null)
            if (sentWifi) return
        }
        if (isBleEnabled()) {
            try { bluetooth.sendFileBroadcast(file) } catch (_: Exception) { }
        } else {
            try { wifiService()?.sendFileBroadcast(file) } catch (_: Exception) { }
            try { p2pService()?.sendFileBroadcast(file) } catch (_: Exception) { }
        }
    }

    override fun sendFilePrivate(recipientPeerID: String, file: BitchatFilePacket) {
        // Prefer whichever Wi-Fi transport already has the peer (much faster than BLE for
        // media), then BLE, then a multi-hop source-route path, then any Wi-Fi as a last resort.
        when {
            isWifiReady(recipientPeerID) -> wifiService()?.sendFilePrivate(recipientPeerID, file)
            isP2pReady(recipientPeerID) -> p2pService()?.sendFilePrivate(recipientPeerID, file)
            isBleReady(recipientPeerID) -> bluetooth.sendFilePrivate(recipientPeerID, file)
            isWifiConnected(recipientPeerID) -> wifiService()?.sendFilePrivate(recipientPeerID, file)
            isP2pConnected(recipientPeerID) -> p2pService()?.sendFilePrivate(recipientPeerID, file)
            isBleConnected(recipientPeerID) || (isBleEnabled() && !isAnyWifiConnected(recipientPeerID)) ->
                bluetooth.sendFilePrivate(recipientPeerID, file)
            else -> {
                // Multi-hop target: prefer the transport that owns the first hop so the
                // source-routed fragments travel A→B→C instead of dying at this node.
                val viaFirstHop = firstHopTransportFor(recipientPeerID)
                if (viaFirstHop != null) {
                    viaFirstHop.sendFilePrivate(recipientPeerID, file)
                } else {
                    if (isBleEnabled()) {
                        try { bluetooth.sendFilePrivate(recipientPeerID, file) } catch (_: Exception) { }
                    }
                    try { wifiService()?.sendFilePrivate(recipientPeerID, file) } catch (_: Exception) { }
                    try { p2pService()?.sendFilePrivate(recipientPeerID, file) } catch (_: Exception) { }
                }
            }
        }
    }

    override fun prepareFilePrivate(
        recipientPeerID: String,
        file: BitchatFilePacket,
        transferId: String,
        allowLegacyFallback: Boolean
    ): PrivateMediaPreparation {
        // Route-aware candidate ordering: preferred transports first, multi-hop (first-hop
        // owning) transport next, then any Wi-Fi as a last resort. A Rejected result from one
        // candidate falls through to the next, so a dead first-hop transport cannot strand a
        // media send — the caller (MediaSendingManager) retries the same intent and this path
        // re-selects whatever transport is alive.
        //
        // Candidates are lambdas because BluetoothMeshService is NOT a MeshService (it exposes
        // its own prepareFilePrivate), so each transport is wrapped in a thunk and invoked at
        // most once per attempt.
        val seenServices = mutableSetOf<MeshService>()
        var bleUsed = false
        fun via(svc: MeshService?): (() -> PrivateMediaPreparation)? = svc?.let { s ->
            if (seenServices.add(s)) {
                { s.prepareFilePrivate(recipientPeerID, file, transferId, allowLegacyFallback) }
            } else null
        }
        fun viaBle(): (() -> PrivateMediaPreparation)? {
            if (bleUsed) return null
            bleUsed = true
            return { bluetooth.prepareFilePrivate(recipientPeerID, file, transferId, allowLegacyFallback) }
        }

        val candidates = buildList {
            if (isWifiReady(recipientPeerID)) via(wifiService())?.let { add(it) }
            if (isP2pReady(recipientPeerID)) via(p2pService())?.let { add(it) }
            if (isBleReady(recipientPeerID)) viaBle()?.let { add(it) }
            if (isWifiConnected(recipientPeerID)) via(wifiService())?.let { add(it) }
            if (isP2pConnected(recipientPeerID)) via(p2pService())?.let { add(it) }
            if (isBleConnected(recipientPeerID) || (isBleEnabled() && !isAnyWifiConnected(recipientPeerID))) {
                viaBle()?.let { add(it) }
            }
            // Multi-hop target: the transport that owns the direct link to the first hop.
            via(firstHopTransportFor(recipientPeerID))?.let { add(it) }
            // BLE may own the first hop of a source route (BluetoothMeshService is not a
            // MeshService, so firstHopTransportFor returns null for it — handled here). This
            // candidate is only added when the first hop is actually BLE-direct, so an idle
            // BLE stack (NeedsHandshake/AwaitingPeerState) cannot block the Wi-Fi relay fallback.
            val firstHop = firstHopPeerIDFor(recipientPeerID)
            if (firstHop != null && isBleDirect(firstHop)) viaBle()?.let { add(it) }
            // Last-resort Wi-Fi paths (may be the only radio that can reach a relay).
            via(wifiService())?.let { add(it) }
            via(p2pService())?.let { add(it) }
        }

        var lastRejected: PrivateMediaPreparation.Rejected? = null
        for (candidate in candidates) {
            val result = try {
                candidate()
            } catch (e: Exception) {
                PrivateMediaPreparation.Rejected(e.message ?: "transport failure")
            }
            if (result is PrivateMediaPreparation.Rejected) {
                lastRejected = result
                Log.w(TAG, "prepareFilePrivate candidate rejected (${result.reason}); trying next transport")
                continue
            }
            return result
        }
        return lastRejected
            ?: PrivateMediaPreparation.Rejected("No local transport is available for this peer")
    }

    override fun cancelFileTransfer(transferId: String): Boolean {
        val bleCancelled = try { bluetooth.cancelFileTransfer(transferId) } catch (_: Exception) { false }
        val wifiCancelled = try { wifiService()?.cancelFileTransfer(transferId) == true } catch (_: Exception) { false }
        return bleCancelled || wifiCancelled
    }

    override fun sendBroadcastAnnounce() {
        // Wi-Fi first (primary transport), then BLE as the secondary radio.
        try { wifiService()?.sendBroadcastAnnounce() } catch (_: Exception) { }
        if (isBleEnabled()) {
            try { bluetooth.sendBroadcastAnnounce() } catch (_: Exception) { }
        }
    }

    override fun sendAnnouncementToPeer(peerID: String) {
        when {
            isWifiConnected(peerID) -> wifiService()?.sendAnnouncementToPeer(peerID)
            isP2pConnected(peerID) -> p2pService()?.sendAnnouncementToPeer(peerID)
            isBleConnected(peerID) || (isBleEnabled() && !isAnyWifiConnected(peerID)) -> bluetooth.sendAnnouncementToPeer(peerID)
            else -> {
                val viaFirstHop = firstHopTransportFor(peerID)
                when {
                    viaFirstHop != null -> viaFirstHop.sendAnnouncementToPeer(peerID)
                    isBleEnabled() -> try { bluetooth.sendAnnouncementToPeer(peerID) } catch (_: Exception) { }
                    else -> wifiService()?.sendAnnouncementToPeer(peerID)
                }
            }
        }
    }

    override fun getPeerNicknames(): Map<String, String> {
        val merged = linkedMapOf<String, String>()
        try { merged.putAll(p2pService()?.getPeerNicknames().orEmpty()) } catch (_: Exception) { }
        try { merged.putAll(wifiService()?.getPeerNicknames().orEmpty()) } catch (_: Exception) { }
        try { merged.putAll(bluetooth.getPeerNicknames()) } catch (_: Exception) { }
        return merged
    }

    override fun getPeerRSSI(): Map<String, Int> {
        val merged = linkedMapOf<String, Int>()
        try { merged.putAll(p2pService()?.getPeerRSSI().orEmpty()) } catch (_: Exception) { }
        try { merged.putAll(wifiService()?.getPeerRSSI().orEmpty()) } catch (_: Exception) { }
        try { merged.putAll(bluetooth.getPeerRSSI()) } catch (_: Exception) { }
        return merged
    }

    override fun getActivePeerCount(): Int {
        return mergedPeerIDs().filter { it != myPeerID }.distinct().size
    }

    override fun hasEstablishedSession(peerID: String): Boolean {
        return isBleReady(peerID) || isWifiReady(peerID) || isP2pReady(peerID)
    }

    override fun getSessionState(peerID: String): NoiseSession.NoiseSessionState {
        val bleState = try { bluetooth.getSessionState(peerID) } catch (_: Exception) { NoiseSession.NoiseSessionState.Uninitialized }
        val wifiState = try { wifiService()?.getSessionState(peerID) } catch (_: Exception) { null }
        val p2pState = try { p2pService()?.getSessionState(peerID) } catch (_: Exception) { null }
        return when {
            bleState is NoiseSession.NoiseSessionState.Established -> bleState
            wifiState is NoiseSession.NoiseSessionState.Established -> wifiState
            p2pState is NoiseSession.NoiseSessionState.Established -> p2pState
            bleState is NoiseSession.NoiseSessionState.Handshaking -> bleState
            wifiState is NoiseSession.NoiseSessionState.Handshaking -> wifiState
            p2pState is NoiseSession.NoiseSessionState.Handshaking -> p2pState
            bleState !is NoiseSession.NoiseSessionState.Uninitialized -> bleState
            wifiState != null -> wifiState
            p2pState != null -> p2pState
            else -> bleState
        }
    }

    override fun isSessionPostQuantum(peerID: String): Boolean {
        val blePQ = try { bluetooth.isSessionPostQuantum(peerID) } catch (_: Exception) { false }
        val wifiPQ = try { wifiService()?.isSessionPostQuantum(peerID) ?: false } catch (_: Exception) { false }
        val p2pPQ = try { p2pService()?.isSessionPostQuantum(peerID) ?: false } catch (_: Exception) { false }
        return blePQ || wifiPQ || p2pPQ
    }

    override fun initiateNoiseHandshake(peerID: String) {
        when {
            isWifiConnected(peerID) -> wifiService()?.initiateNoiseHandshake(peerID)
            isP2pConnected(peerID) -> p2pService()?.initiateNoiseHandshake(peerID)
            isBleConnected(peerID) -> bluetooth.initiateNoiseHandshake(peerID)
            else -> {
                // 2-hop peer: kick the handshake on the transport that can reach the first hop
                // so the source-routed handshake actually leaves this device.
                val viaFirstHop = firstHopTransportFor(peerID)
                when {
                    viaFirstHop != null -> viaFirstHop.initiateNoiseHandshake(peerID)
                    isBleEnabled() -> bluetooth.initiateNoiseHandshake(peerID)
                    else -> {
                        try { wifiService()?.initiateNoiseHandshake(peerID) } catch (_: Exception) { }
                        try { p2pService()?.initiateNoiseHandshake(peerID) } catch (_: Exception) { }
                    }
                }
            }
        }
    }

    override fun getPeerFingerprint(peerID: String): String? {
        return try { bluetooth.getPeerFingerprint(peerID) } catch (_: Exception) { null }
            ?: try { wifiService()?.getPeerFingerprint(peerID) } catch (_: Exception) { null }
            ?: try { p2pService()?.getPeerFingerprint(peerID) } catch (_: Exception) { null }
    }

    override fun getPeerInfo(peerID: String): PeerInfo? {
        val ble = try { bluetooth.getPeerInfo(peerID) } catch (_: Exception) { null }
        val wifi = try { wifiService()?.getPeerInfo(peerID) } catch (_: Exception) { null }
        val p2p = try { p2pService()?.getPeerInfo(peerID) } catch (_: Exception) { null }
        return when {
            ble?.isConnected == true && hasEstablishedSessionOnBluetooth(peerID) -> ble
            wifi?.isConnected == true && wifiService()?.hasEstablishedSession(peerID) == true -> wifi
            p2p?.isConnected == true && p2pService()?.hasEstablishedSession(peerID) == true -> p2p
            ble?.isConnected == true -> ble
            wifi?.isConnected == true -> wifi
            p2p?.isConnected == true -> p2p
            else -> ble ?: wifi ?: p2p
        }
    }

    override fun isPeerBleOnly(peerID: String): Boolean {
        // Wi-Fi Aware and Wi-Fi Direct are the high-bandwidth transports; without either, a
        // connected peer's media rides the slow BLE radio, so callers compress more aggressively.
        if (isAnyWifiConnected(peerID)) return false
        return isBleConnected(peerID) || isBleDirect(peerID)
    }

    override fun updatePeerInfo(
        peerID: String,
        nickname: String,
        noisePublicKey: ByteArray,
        signingPublicKey: ByteArray,
        isVerified: Boolean
    ): Boolean {
        val bleUpdated = try {
            bluetooth.updatePeerInfo(peerID, nickname, noisePublicKey, signingPublicKey, isVerified)
        } catch (_: Exception) {
            false
        }
        val wifiUpdated = try {
            wifiService()?.updatePeerInfo(peerID, nickname, noisePublicKey, signingPublicKey, isVerified) == true
        } catch (_: Exception) {
            false
        }
        return bleUpdated || wifiUpdated
    }

    override fun getIdentityFingerprint(): String = bluetooth.getIdentityFingerprint()

    override fun getStaticNoisePublicKey(): ByteArray? {
        return bluetooth.getStaticNoisePublicKey() ?: wifiService()?.getStaticNoisePublicKey()
    }

    override fun shouldShowEncryptionIcon(peerID: String): Boolean {
        return hasEstablishedSession(peerID)
    }

    override fun getEncryptedPeers(): List<String> {
        val encrypted = linkedSetOf<String>()
        try { encrypted.addAll(bluetooth.getEncryptedPeers()) } catch (_: Exception) { }
        try { encrypted.addAll(wifiService()?.getEncryptedPeers().orEmpty()) } catch (_: Exception) { }
        try { encrypted.addAll(p2pService()?.getEncryptedPeers().orEmpty()) } catch (_: Exception) { }
        mergedPeerIDs().filterTo(encrypted) { hasEstablishedSession(it) }
        return encrypted.toList()
    }

    override fun getDeviceAddressForPeer(peerID: String): String? {
        return try { bluetooth.getDeviceAddressForPeer(peerID) } catch (_: Exception) { null }
            ?: try { wifiService()?.getDeviceAddressForPeer(peerID) } catch (_: Exception) { null }
    }

    override fun getDeviceAddressToPeerMapping(): Map<String, String> {
        val merged = linkedMapOf<String, String>()
        try { merged.putAll(p2pService()?.getDeviceAddressToPeerMapping().orEmpty()) } catch (_: Exception) { }
        try { merged.putAll(wifiService()?.getDeviceAddressToPeerMapping().orEmpty()) } catch (_: Exception) { }
        try { merged.putAll(bluetooth.getDeviceAddressToPeerMapping()) } catch (_: Exception) { }
        return merged
    }

    override fun printDeviceAddressesForPeers(): String {
        return buildString {
            appendLine(bluetooth.printDeviceAddressesForPeers())
            wifiService()?.let {
                appendLine()
                appendLine(it.printDeviceAddressesForPeers())
            }
            p2pService()?.let {
                appendLine()
                appendLine(it.printDeviceAddressesForPeers())
            }
        }
    }

    override fun getDebugStatus(): String {
        return buildString {
            appendLine("=== Unified Mesh Service Debug Status ===")
            appendLine("My Peer ID: $myPeerID")
            appendLine("Merged Peers: ${mergedPeerIDs().joinToString(", ")}")
            appendLine()
            appendLine(RouteMetrics.debugSummary())
            appendLine()
            appendLine(bluetooth.getDebugStatus())
            wifiService()?.let {
                appendLine()
                appendLine(it.getDebugStatus())
            }
            p2pService()?.let {
                appendLine()
                appendLine(it.getDebugStatus())
            }
        }
    }

    override fun clearAllInternalData() {
        try { bluetooth.clearAllInternalData() } catch (_: Exception) { }
        try { wifiService()?.clearAllInternalData() } catch (_: Exception) { }
        try { p2pService()?.clearAllInternalData() } catch (_: Exception) { }
    }

    override fun clearAllEncryptionData() {
        try { bluetooth.clearAllEncryptionData() } catch (_: Exception) { }
        try { wifiService()?.clearAllEncryptionData() } catch (_: Exception) { }
        try { p2pService()?.clearAllEncryptionData() } catch (_: Exception) { }
    }

    override fun didReceiveMessage(message: BitchatMessage) {
        delegate?.didReceiveMessage(message)
    }

    override fun didUpdatePeerList(peers: List<String>) {
        delegate?.didUpdatePeerList(mergedPeerIDs().ifEmpty { peers.distinct() })
    }

    override fun didReceiveChannelLeave(channel: String, fromPeer: String) {
        delegate?.didReceiveChannelLeave(channel, fromPeer)
    }

    override fun didReceiveDeliveryAck(messageID: String, recipientPeerID: String) {
        delegate?.didReceiveDeliveryAck(messageID, recipientPeerID)
    }

    override fun didReceiveReadReceipt(messageID: String, recipientPeerID: String) {
        delegate?.didReceiveReadReceipt(messageID, recipientPeerID)
    }

    override fun didReceiveVerifyChallenge(peerID: String, payload: ByteArray, timestampMs: Long) {
        delegate?.didReceiveVerifyChallenge(peerID, payload, timestampMs)
    }

    override fun didReceiveVerifyResponse(peerID: String, payload: ByteArray, timestampMs: Long) {
        delegate?.didReceiveVerifyResponse(peerID, payload, timestampMs)
    }

    override fun didResolvePrivateMediaPolicy(peerID: String) {
        delegate?.didResolvePrivateMediaPolicy(peerID)
    }

    override fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? {
        return delegate?.decryptChannelMessage(encryptedContent, channel)
    }

    override fun getNickname(): String? = delegate?.getNickname()

    override fun isFavorite(peerID: String): Boolean = delegate?.isFavorite(peerID) ?: false

    private fun mergedPeerIDs(): List<String> {
        val ids = linkedSetOf<String>()
        try { ids.addAll(com.bitchat.android.services.AppStateStore.peers.value) } catch (_: Exception) { }
        try { ids.addAll(bluetooth.getPeerNicknames().keys) } catch (_: Exception) { }
        try { ids.addAll(wifiService()?.getPeerNicknames()?.keys.orEmpty()) } catch (_: Exception) { }
        try { ids.addAll(p2pService()?.getPeerNicknames()?.keys.orEmpty()) } catch (_: Exception) { }
        return ids.toList()
    }

    private fun wifiService(): MeshService? {
        return try {
            WifiAwareController.getService()?.also { service ->
                if (delegate != null && service.delegate !== this) {
                    service.delegate = this
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun p2pService(): MeshService? {
        return try {
            com.bitchat.android.wifidirect.WifiDirectController.getService()?.also { service ->
                if (delegate != null && service.delegate !== this) {
                    service.delegate = this
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun isBleEnabled(): Boolean {
        return try {
            com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().bleEnabled.value
        } catch (_: Exception) {
            try { com.bitchat.android.ui.debug.DebugPreferenceManager.getBleEnabled(true) } catch (_: Exception) { true }
        }
    }


    private fun isBleConnected(peerID: String): Boolean {
        return try { bluetooth.getPeerInfo(peerID)?.isConnected == true } catch (_: Exception) { false }
    }

    private fun isWifiConnected(peerID: String): Boolean {
        return try { wifiService()?.getPeerInfo(peerID)?.isConnected == true } catch (_: Exception) { false }
    }

    private fun isP2pConnected(peerID: String): Boolean {
        return try { p2pService()?.getPeerInfo(peerID)?.isConnected == true } catch (_: Exception) { false }
    }

    // Direct-link variants: the peer must be a confirmed direct neighbor on the transport, not
    // merely known via a relayed announcement. Used for first-hop (source-route) selection.
    private fun isBleDirect(peerID: String): Boolean {
        return try { bluetooth.getPeerInfo(peerID)?.isDirectConnection == true } catch (_: Exception) { false }
    }

    private fun isWifiDirect(peerID: String): Boolean {
        return try { wifiService()?.getPeerInfo(peerID)?.isDirectConnection == true } catch (_: Exception) { false }
    }

    private fun isP2pDirect(peerID: String): Boolean {
        return try { p2pService()?.getPeerInfo(peerID)?.isDirectConnection == true } catch (_: Exception) { false }
    }

    private fun isAnyWifiConnected(peerID: String): Boolean {
        return isWifiConnected(peerID) || isP2pConnected(peerID)
    }

    private fun hasWifiPeers(): Boolean {
        return try {
            (wifiService()?.getActivePeerCount() ?: 0) > 0 ||
                (p2pService()?.getActivePeerCount() ?: 0) > 0
        } catch (_: Exception) {
            false
        }
    }

    private fun isBleReady(peerID: String): Boolean {
        return isBleConnected(peerID) && hasEstablishedSessionOnBluetooth(peerID)
    }

    /**
     * Picks the local transport that owns the DIRECT link to the first hop toward
     * [recipientPeerID] (per the shared mesh graph). For a multi-hop target A→B→C this returns
     * the transport that is directly connected to B, so the source-routed packet (with route
     * [B]) actually leaves this device. Returns null when no usable path is known.
     *
     * Directness matters here: relayed announcements can mark a peer `isConnected` without a
     * direct link, but a source route's first hop MUST be a directly reachable neighbor.
     */
    private fun firstHopTransportFor(recipientPeerID: String): MeshService? {
        val firstHop = firstHopPeerIDFor(recipientPeerID) ?: return null
        return when {
            // BLE owns its first hop through BluetoothMeshService, which is not a MeshService
            // (casting it would throw ClassCastException). Callers fall back to `bluetooth`
            // directly when this returns null.
            isBleDirect(firstHop) -> null
            isWifiDirect(firstHop) -> wifiService()
            isP2pDirect(firstHop) -> p2pService()
            else -> null
        }
    }

    /** First hop toward [recipientPeerID] per the shared mesh graph (or null when unknown). */
    private fun firstHopPeerIDFor(recipientPeerID: String): String? {
        val path = try {
            com.bitchat.android.services.meshgraph.RoutePlanner.shortestPath(myPeerID, recipientPeerID)
        } catch (_: Exception) {
            null
        } ?: return null
        return if (path.size < 2) null else path[1]
    }

    private fun isWifiReady(peerID: String): Boolean {
        return try {
            val wifi = wifiService()
            wifi?.getPeerInfo(peerID)?.isConnected == true && wifi.hasEstablishedSession(peerID)
        } catch (_: Exception) {
            false
        }
    }

    private fun isP2pReady(peerID: String): Boolean {
        return try {
            val p2p = p2pService()
            p2p?.getPeerInfo(peerID)?.isConnected == true && p2p.hasEstablishedSession(peerID)
        } catch (_: Exception) {
            false
        }
    }

    private fun hasEstablishedSessionOnBluetooth(peerID: String): Boolean {
        return try { bluetooth.hasEstablishedSession(peerID) } catch (_: Exception) { false }
    }
}
