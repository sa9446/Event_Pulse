package com.bitchat.android.wifidirect

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.mesh.FragmentingPacketSender
import com.bitchat.android.mesh.MeshCore
import com.bitchat.android.mesh.MeshDelegate
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.mesh.MeshTransport
import com.bitchat.android.mesh.PeerInfo
import com.bitchat.android.model.BitchatFilePacket
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.SpecialRecipients
import com.bitchat.android.service.TransportBridgeService
import com.bitchat.android.sync.GossipSyncManager
import com.bitchat.android.util.toHexString
import com.bitchat.android.wifiaware.IngressLinkPolicy
import com.bitchat.android.wifiaware.SyncedSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WifiDirectMeshService - a Wi-Fi Direct (P2P) mesh transport.
 *
 * Architecture mirrors WifiAwareMeshService:
 * - DNS-SD (Bonjour-style) service discovery exchanges peer IDs before any connection.
 * - Deterministic role election: the device with the lexicographically smaller peer ID becomes
 *   the P2P Group Owner (GO) and hosts a TCP server; the larger peer joins as a client.
 * - Once the group is formed, both sides use the framed SyncedSocket protocol over TCP and the
 *   shared MeshCore pipeline (fragmentation, Noise handshake, routing, gossip).
 *
 * This transport runs in parallel with BLE and Wi-Fi Aware. MediaSendingManager prefers it for
 * large payloads because P2P provides far higher throughput than BLE without any access point.
 */
class WifiDirectMeshService(private val context: Context) : MeshService, TransportBridgeService.TransportLayer {

    companion object {
        private const val TAG = "WifiDirectMeshService"
        private const val MAX_TTL: UByte = 7u
        private const val SERVICE_TYPE = "_bitchat._tcp"
        // Fixed TCP port used for the mesh data channel inside the P2P group.
        private const val DATA_PORT = 47321
        private const val ACCEPT_TIMEOUT_MS = 30_000
        private const val CLIENT_CONNECT_TIMEOUT_MS = 7_000
        private const val NETWORK_MAINTENANCE_MS = 10_000L
        private const val ROLE_RETRY_MS = 5_000L
    }

    // Core crypto/services (peer ID must match BLE/Aware: first 16 hex chars of identity fingerprint)
    private val encryptionService = EncryptionService(context)
    override val myPeerID: String = encryptionService.getIdentityFingerprint().take(16)

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val powerManager = com.bitchat.android.mesh.PowerManager.getInstance(context.applicationContext)

    private lateinit var meshCore: MeshCore
    private lateinit var fragmentingSender: FragmentingPacketSender
    private val connectionTracker = WifiDirectConnectionTracker()

    private val listenerExec = Executors.newCachedThreadPool()
    @Volatile private var isActive = false
    @Volatile private var recoveryInProgress = false

    // P2P state
    private val p2pManager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var p2pChannel: WifiP2pManager.Channel? = null
    @Volatile private var p2pReceiverRegistered = false
    @Volatile private var isGroupOwner = false
    @Volatile private var groupFormed = false
    @Volatile private var groupOwnerAddress: InetAddress? = null
    @Volatile private var serverSocket: ServerSocket? = null
    private val pendingServerConnections = AtomicBoolean(false)
    private val pendingClientConnections = AtomicBoolean(false)

    // peerID -> WifiP2pDevice learned via DNS-SD (used for role election + connect())
    private val discoveredDevices = ConcurrentHashMap<String, WifiP2pDevice>()
    // peerID -> last-seen time
    private val discoveredTimestamps = ConcurrentHashMap<String, Long>()
    private val ingressLinks = ConcurrentHashMap<
        String,
        IngressLinkPolicy.Link<SyncedSocket>
    >()
    private val handledPeers = ConcurrentHashMap.newKeySet<String>()

    // Delegate
    override var delegate: MeshDelegate? = null
        set(value) {
            field = value
            if (::meshCore.isInitialized) {
                meshCore.delegate = value
                meshCore.refreshPeerList()
            }
        }

    fun isRunning(): Boolean = isActive

    init {
        com.bitchat.android.service.MeshServiceHolder.getOrCreate(context)
        val shared = com.bitchat.android.service.MeshServiceHolder.sharedGossipSyncManager
        encryptionService.onSessionEstablished = { peerID ->
            Log.d(TAG, "Wi-Fi Direct Noise session established with ${peerID.take(8)}")
            try {
                com.bitchat.android.services.MessageRouter
                    .tryGetInstance()
                    ?.onSessionEstablished(peerID)
            } catch (_: Exception) { }
        }
        meshCore = MeshCore(
            context = context.applicationContext,
            scope = serviceScope,
            transport = P2pTransport(),
            encryptionService = encryptionService,
            myPeerID = myPeerID,
            maxTtl = MAX_TTL,
            sharedGossipManager = shared,
            gossipConfigProvider = object : GossipSyncManager.ConfigProvider {
                override fun seenCapacity(): Int = 500
                override fun gcsMaxBytes(): Int = 400
                override fun gcsTargetFpr(): Double = 0.01
            },
            hooks = MeshCore.Hooks(
                onMessageReceived = { message -> handleMessageReceived(message) },
                onAnnounceProcessed = { routed, _ ->
                    publishControllerDebugSnapshot()
                    routed.peerID?.let { pid ->
                        try {
                            meshCore.gossipSyncManager.scheduleInitialSyncToPeer(pid, 1_000)
                        } catch (_: Exception) { }
                    }
                },
                announcementNicknameProvider = {
                    try { com.bitchat.android.services.NicknameProvider.getNickname(context, myPeerID) } catch (_: Exception) { null }
                },
                leavePayloadProvider = {
                    (delegate?.getNickname() ?: myPeerID).toByteArray(Charsets.UTF_8)
                }
            )
        )
        fragmentingSender = FragmentingPacketSender(serviceScope, meshCore.fragmentManager, TAG)
    }

    private fun handleMessageReceived(message: BitchatMessage): Boolean {
        if (
            !com.bitchat.android.services.IncomingMessageAdmission
                .admitToAppState(message)
        ) return false

        if (delegate == null && message.isPrivate) {
            try {
                val senderPeerID = message.senderPeerID
                if (senderPeerID != null) {
                    val nick = try { meshCore.getPeerNickname(senderPeerID) } catch (_: Exception) { null } ?: senderPeerID
                    val preview = com.bitchat.android.ui.NotificationTextUtils.buildPrivateMessagePreview(message)
                    com.bitchat.android.ui.NotificationManager(
                        context.applicationContext,
                        androidx.core.app.NotificationManagerCompat.from(context.applicationContext)
                    ).also { nm ->
                        nm.setAppBackgroundState(true)
                        nm.showPrivateMessageNotification(senderPeerID, nick, preview)
                    }
                }
            } catch (_: Exception) { }
        }
        return true
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TransportLayer bridge (packets received from other transports get sent via P2P)
    // ─────────────────────────────────────────────────────────────────────────────

    override fun send(packet: RoutedPacket) {
        meshCore.sendFromBridge(packet)
    }

    override suspend fun sendAndReport(packet: RoutedPacket): Boolean {
        return meshCore.sendFromBridgeAndReport(packet)
    }

    override fun sendToPeer(peerID: String, packet: BitchatPacket): Boolean {
        return sendPacketToPeer(peerID, packet)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // MeshService lifecycle
    // ─────────────────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    override fun startServices() {
        if (isActive) return
        if (!WifiDirectController.enabled.value) {
            Log.i(TAG, "Wi-Fi Direct transport disabled by settings; not starting")
            return
        }
        val supportStatus = WifiDirectSupport.evaluate(context)
        if (!supportStatus.supported) {
            Log.i(TAG, "Wi-Fi Direct unsupported on this device; not starting (${supportStatus.reason})")
            return
        }
        val manager = p2pManager
        if (manager == null) {
            Log.w(TAG, "WifiP2pManager unavailable; not starting")
            return
        }
        if (recoveryInProgress) {
            Log.i(TAG, "Wi-Fi Direct recovery cleanup still in progress; deferring start")
            return
        }

        isActive = true
        p2pChannel = manager.initialize(context, Looper.getMainLooper(), null)
        registerP2pReceiver()

        // DNS-SD: advertise our peer ID so peers can learn who we are before connecting.
        val serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(
            myPeerID,
            SERVICE_TYPE,
            mapOf("role" to "bitchat", "port" to DATA_PORT.toString())
        )
        manager.addLocalService(p2pChannel, serviceInfo, p2pActionListener("add-local-service"))
        manager.addServiceRequest(
            p2pChannel,
            WifiP2pDnsSdServiceRequest.newInstance(),
            p2pActionListener("add-service-request")
        )
        manager.setDnsSdResponseListeners(
            p2pChannel,
            dnsSdServiceAvailableListener,
            dnsSdTxtRecordListener
        )
        manager.discoverPeers(p2pChannel, p2pActionListener("discover-peers"))

        // Register with cross-layer transport bridge
        TransportBridgeService.register("P2P", this)
        meshCore.startCore()
        com.bitchat.android.service.MeshServiceHolder.startSharedGossip("P2P")
        startPeriodicMaintenance()
        Log.i(TAG, "Wi-Fi Direct mesh started (peer ID: $myPeerID)")
    }

    override fun stopServices() {
        val wasActive = isActive
        isActive = false
        Log.i(TAG, "Stopping Wi-Fi Direct mesh")

        TransportBridgeService.unregister("P2P")
        com.bitchat.android.service.MeshServiceHolder.stopSharedGossip("P2P")
        try { com.bitchat.android.services.AppStateStore.clearTransportPeers("P2P") } catch (_: Exception) { }
        try { com.bitchat.android.services.AppStateStore.clearTransportDirectPeers("P2P") } catch (_: Exception) { }

        if (wasActive) {
            meshCore.sendLeaveAnnouncement()
        }

        serviceScope.launch {
            delay(200)
            meshCore.stopCore()
            connectionTracker.stop()
            closeServerSocket()
            removeP2pGroup()
            unregisterP2pReceiver()
            ingressLinks.clear()
            discoveredDevices.clear()
            discoveredTimestamps.clear()
            handledPeers.clear()
            meshCore.shutdown()
            try { listenerExec.shutdownNow() } catch (_: Exception) { }
            WifiDirectController.onServiceStopped(this@WifiDirectMeshService)
            serviceScope.cancel()
        }
    }

    private fun p2pActionListener(label: String): WifiP2pManager.ActionListener {
        return object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "P2P action succeeded: $label")
            }

            override fun onFailure(reason: Int) {
                Log.w(TAG, "P2P action failed: $label (reason=$reason)")
                // Clear the pending-role flags so the maintenance loop can retry. connect() can
                // legitimately fail when the GO hasn't finished createGroup() yet, and
                // createGroup() can return BUSY if we are already in (or joining) a group.
                when (label) {
                    "create-group" -> pendingServerConnections.set(false)
                    "connect" -> pendingClientConnections.set(false)
                }
                if (label == "create-group" && reason == WifiP2pManager.BUSY) {
                    // Another device already owns a group; we may be in it as a client.
                    Log.i(TAG, "create-group BUSY: likely joining an existing group as client")
                }
            }
        }
    }

    private fun registerP2pReceiver() {
        if (p2pReceiverRegistered) return
        try {
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            }
            context.registerReceiver(p2pReceiver, filter)
            p2pReceiverRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register P2P receiver: ${e.message}")
        }
    }

    private fun unregisterP2pReceiver() {
        if (!p2pReceiverRegistered) return
        try { context.unregisterReceiver(p2pReceiver) } catch (_: Exception) { }
        p2pReceiverRegistered = false
    }

    private val p2pReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (!isActive) return
            val action = intent?.action ?: return
            when (action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    requestPeers()
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    requestConnectionInfo()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestPeers() {
        val manager = p2pManager ?: return
        val channel = p2pChannel ?: return
        manager.requestPeers(channel) { peers: WifiP2pDeviceList ->
            if (!isActive) return@requestPeers
            val now = System.currentTimeMillis()
            peers.deviceList.forEach { device ->
                if (device.status == WifiP2pDevice.AVAILABLE) {
                    discoveredTimestamps[device.deviceAddress] = now
                }
            }
        }
    }

    private val dnsSdServiceAvailableListener = object : WifiP2pManager.DnsSdServiceResponseListener {
        override fun onDnsSdServiceAvailable(
            instanceName: String,
            registrationType: String,
            srcDevice: WifiP2pDevice
        ) {
            if (!isActive) return
            if (registrationType != SERVICE_TYPE) return
            val peerId = instanceName
            if (peerId.isBlank() || peerId == myPeerID) return
            discoveredDevices[peerId] = srcDevice
            discoveredTimestamps[peerId] = System.currentTimeMillis()
            Log.d(TAG, "Discovered P2P peer ${peerId.take(8)} via DNS-SD")
            publishControllerDebugSnapshot()
            evaluateRole(peerId)
        }
    }

    private val dnsSdTxtRecordListener = object : WifiP2pManager.DnsSdTxtRecordListener {
        override fun onDnsSdTxtRecordAvailable(
            fullDomainName: String,
            txtRecordMap: MutableMap<String, String>,
            srcDevice: WifiP2pDevice
        ) {
            // Instance name is the peer ID; TXT carries the fixed data port (informational).
        }
    }

    /**
     * Deterministic role election: the device with the smaller peer ID becomes the Group Owner.
     * The GO creates the group and hosts a TCP server; the larger peer joins and connects to it.
     */
    private fun evaluateRole(remotePeerId: String) {
        if (remotePeerId.isBlank() || remotePeerId == myPeerID) return
        if (handledPeers.contains(remotePeerId)) return
        if (groupFormed) {
            // Group already exists; make sure a socket is up for this peer once identity resolves.
            return
        }

        if (myPeerID < remotePeerId) {
            // We are the Group Owner for this relationship.
            if (pendingServerConnections.compareAndSet(false, true)) {
                createGroupAndServe()
            }
        } else {
            // The remote is the Group Owner; join their group as a client.
            val device = discoveredDevices[remotePeerId]
            if (device != null) {
                if (pendingClientConnections.compareAndSet(false, true)) {
                    connectAsClient(device)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun createGroupAndServe() {
        val manager = p2pManager ?: return
        val channel = p2pChannel ?: return
        Log.i(TAG, "Creating Wi-Fi Direct group (Group Owner role)")
        manager.createGroup(channel, p2pActionListener("create-group"))
        // Connection-changed receiver will detect groupFormed + isGroupOwner and open the server.
    }

    @SuppressLint("MissingPermission")
    private fun connectAsClient(device: WifiP2pDevice) {
        val manager = p2pManager ?: return
        val channel = p2pChannel ?: return
        Log.i(TAG, "Connecting to P2P Group Owner ${device.deviceAddress}")
        val config = WifiP2pConfig()
        config.deviceAddress = device.deviceAddress
        config.groupOwnerIntent = 0 // 0-15; lower = less likely to become GO (we want to be client)
        manager.connect(channel, config, p2pActionListener("connect"))
    }

    @SuppressLint("MissingPermission")
    private fun requestConnectionInfo() {
        val manager = p2pManager ?: return
        val channel = p2pChannel ?: return
        manager.requestConnectionInfo(channel) { info: WifiP2pInfo ->
            if (!isActive) return@requestConnectionInfo
            handleConnectionInfo(info)
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleConnectionInfo(info: WifiP2pInfo) {
        val formed = info.groupFormed
        val owner = info.isGroupOwner
        val goAddress = info.groupOwnerAddress

        if (!formed) {
            groupFormed = false
            isGroupOwner = false
            groupOwnerAddress = null
            return
        }

        groupFormed = true
        isGroupOwner = owner
        groupOwnerAddress = goAddress
        Log.i(TAG, "P2P group formed (groupOwner=$owner, addr=${goAddress?.hostAddress})")

        if (owner) {
            // We are GO: bind a TCP server on the P2P interface and accept peers.
            serviceScope.launch {
                try {
                    bindAndAccept()
                } catch (e: Exception) {
                    Log.e(TAG, "GO server failed: ${e.message}")
                }
            }
        } else {
            // We are a client: connect to the GO's TCP server.
            val address = goAddress
            if (address != null) {
                serviceScope.launch {
                    try {
                        connectToGroupOwner(address)
                    } catch (e: Exception) {
                        Log.e(TAG, "Client connect failed: ${e.message}")
                    }
                }
            }
        }
    }

    private fun bindAndAccept() {
        if (serverSocket != null) return
        val ss = ServerSocket()
        try {
            ss.reuseAddress = true
            ss.bind(java.net.InetSocketAddress(DATA_PORT))
            serverSocket = ss
            Log.i(TAG, "P2P GO listening on port $DATA_PORT")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind GO server socket", e)
            return
        }

        listenerExec.execute {
            while (isActive && !ss.isClosed) {
                try {
                    ss.soTimeout = ACCEPT_TIMEOUT_MS
                    val client = ss.accept()
                    client.keepAlive = true
                    val synced = SyncedSocket(client)
                    // First frame from the client is its peer ID.
                    val peerIdFrame = synced.read()
                    val peerId = peerIdFrame?.toString(Charsets.UTF_8)?.take(16)
                    if (peerId.isNullOrBlank() || peerId == myPeerID) {
                        synced.close()
                        continue
                    }
                    Log.i(TAG, "P2P accepted client ${peerId.take(8)}")
                    registerConnectedPeer(peerId, synced)
                } catch (e: java.net.SocketTimeoutException) {
                    continue
                } catch (e: IOException) {
                    if (!isActive) break
                    Log.w(TAG, "GO accept loop error: ${e.message}")
                    break
                }
            }
        }
    }

    private fun connectToGroupOwner(goAddress: InetAddress) {
        // The client role's pending flag was set by evaluateRole() before connect(); this method
        // runs after the group formed. Clear it now so a failed TCP attempt can be retried by
        // maintenance, and so we do not deadlock the client path on the flag we just set.
        pendingClientConnections.set(false)
        var lastError: IOException? = null
        for (attempt in 1..3) {
            if (!isActive) return
            try {
                Thread.sleep(if (attempt == 1) 500L else 1_000L)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            try {
                val sock = Socket()
                sock.tcpNoDelay = true
                sock.connect(java.net.InetSocketAddress(goAddress, DATA_PORT), CLIENT_CONNECT_TIMEOUT_MS)
                val synced = SyncedSocket(sock)
                // Announce our peer ID as the first frame.
                synced.write(myPeerID.toByteArray(Charsets.UTF_8))
                Log.i(TAG, "P2P client connected to GO ${goAddress.hostAddress}")
                // Peer identity will resolve via ANNOUNCE rebinding; use the GO's device as provisional.
                val goPeerId = resolvedGoPeerId()
                if (goPeerId != null) {
                    registerConnectedPeer(goPeerId, synced)
                } else {
                    listenerExec.execute { readProvisionalPeer(synced) }
                }
                return
            } catch (e: IOException) {
                lastError = e
                Log.w(TAG, "P2P client attempt $attempt/3 failed: ${e.message}")
            }
        }
        Log.w(TAG, "P2P client connect failed after retries: ${lastError?.message}")
    }

    private fun readProvisionalPeer(synced: SyncedSocket) {
        // Read until ANNOUNCE resolves the peer ID; the ingress link handles rebinding.
        val logicalPeerId = "p2p_${System.currentTimeMillis()}"
        val ingressLinkID = UUID.randomUUID().toString()
        val ingressLink = IngressLinkPolicy.Link(logicalPeerId, synced)
        ingressLinks[ingressLinkID] = ingressLink
        while (isActive) {
            val raw = synced.read() ?: break
            if (raw.isEmpty()) continue
            val pkt = BitchatPacket.fromBinaryData(raw) ?: continue
            val senderPeerHex = pkt.senderID?.toHexString()?.take(16) ?: continue
            meshCore.processIncoming(pkt, senderPeerHex, logicalPeerId, ingressLinkID)
        }
        ingressLinks.remove(ingressLinkID, ingressLink)
        synced.close()
    }

    private fun resolvedGoPeerId(): String? {
        // The GO is the smallest discovered peer ID (role election rule).
        return discoveredDevices.keys.filter { it != myPeerID }.minOrNull()
    }

    private fun registerConnectedPeer(peerId: String, synced: SyncedSocket) {
        connectionTracker.onClientConnected(peerId, synced)
        handledPeers.add(peerId)
        try { meshCore.addOrUpdatePeer(peerId, peerId) } catch (_: Exception) { }
        // Mark the peer as a DIRECT connection so this link is advertised in gossip neighbor
        // lists and the shared mesh graph can build source routes through Wi-Fi Direct.
        try { meshCore.setDirectConnection(peerId, true) } catch (_: Exception) { }
        // Kick off the Noise handshake for this logical peer (parity with Wi-Fi Aware).
        if (myPeerID < peerId) {
            meshCore.initiateNoiseHandshake(peerId)
        }
        publishControllerDebugSnapshot()
        listenerExec.execute { listenToPeer(synced, peerId) }
        serviceScope.launch {
            delay(150)
            sendBroadcastAnnounce()
        }
    }

    private fun listenToPeer(socket: SyncedSocket, peerId: String) {
        val ingressLinkID = UUID.randomUUID().toString()
        val ingressLink = IngressLinkPolicy.Link(peerId, socket)
        ingressLinks[ingressLinkID] = ingressLink
        while (isActive) {
            val raw = socket.read() ?: break
            if (raw.isEmpty()) continue
            val pkt = BitchatPacket.fromBinaryData(raw) ?: continue
            val senderPeerHex = pkt.senderID?.toHexString()?.take(16) ?: continue
            meshCore.processIncoming(pkt, senderPeerHex, peerId, ingressLinkID)
        }
        ingressLinks.remove(ingressLinkID, ingressLink)
        Log.i(TAG, "Disconnected from ${peerId.take(8)} (P2P socket closed)")
        connectionTracker.disconnect(peerId)
        // This P2P link is gone; drop it from the direct-connection set so gossip neighbor
        // lists and the mesh graph stop advertising a stale direct edge.
        try { meshCore.setDirectConnection(peerId, false) } catch (_: Exception) { }
        handledPeers.remove(peerId)
        socket.close()
    }

    private fun closeServerSocket() {
        serverSocket?.let {
            try { it.close() } catch (_: Exception) { }
        }
        serverSocket = null
    }

    @SuppressLint("MissingPermission")
    private fun removeP2pGroup() {
        val manager = p2pManager ?: return
        val channel = p2pChannel ?: return
        try { manager.removeGroup(channel, p2pActionListener("remove-group")) } catch (_: Exception) { }
    }

    private fun startPeriodicMaintenance() {
        serviceScope.launch {
            while (isActive) {
                try {
                    delay(NETWORK_MAINTENANCE_MS)
                    if (!isActive) break

                    // Keep-alive: empty frames keep sockets from timing out.
                    connectionTracker.forEachSocket { _, sock ->
                        try { sock.write(ByteArray(0)) } catch (_: IOException) { }
                    }

                    // Re-discover peers periodically so new devices can join.
                    try { p2pManager?.discoverPeers(p2pChannel, p2pActionListener("discover-peers")) } catch (_: Exception) { }

                    // Retry role election for discovered peers we have not connected to yet.
                    if (!groupFormed) {
                        val pending = discoveredDevices.keys.filter { it != myPeerID && it !in handledPeers }
                        if (pending.isNotEmpty() && !pendingServerConnections.get() && !pendingClientConnections.get()) {
                            pending.forEach { evaluateRole(it) }
                        }
                    }
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error in P2P maintenance: ${e.message}")
                }
            }
        }
    }

    private fun publishControllerDebugSnapshot() {
        try {
            WifiDirectController.publishDebugSnapshot(
                connected = getDeviceAddressToPeerMapping(),
                known = getPeerNicknames(),
                discovered = discoveredDevices.keys.filter { it != myPeerID }.toSet()
            )
        } catch (_: Exception) { }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // MeshService API
    // ─────────────────────────────────────────────────────────────────────────────

    override fun sendMessage(content: String, mentions: List<String>, channel: String?) {
        meshCore.sendMessage(content, mentions, channel)
    }

    override fun sendPrivateMessage(content: String, recipientPeerID: String, recipientNickname: String, messageID: String?) {
        meshCore.sendPrivateMessage(content, recipientPeerID, recipientNickname, messageID)
    }

    override fun sendReadReceipt(messageID: String, recipientPeerID: String, readerNickname: String) {
        meshCore.sendReadReceipt(messageID, recipientPeerID, readerNickname)
    }

    override fun sendVerifyChallenge(peerID: String, noiseKeyHex: String, nonceA: ByteArray) {
        meshCore.sendVerifyChallenge(peerID, noiseKeyHex, nonceA)
    }

    override fun sendVerifyResponse(peerID: String, noiseKeyHex: String, nonceA: ByteArray) {
        meshCore.sendVerifyResponse(peerID, noiseKeyHex, nonceA)
    }

    override fun sendFileBroadcast(file: BitchatFilePacket) {
        meshCore.sendFileBroadcast(file)
    }

    override fun sendFilePrivate(recipientPeerID: String, file: BitchatFilePacket) {
        meshCore.sendFilePrivate(recipientPeerID, file)
    }

    override fun prepareFilePrivate(
        recipientPeerID: String,
        file: BitchatFilePacket,
        transferId: String,
        allowLegacyFallback: Boolean
    ): com.bitchat.android.mesh.PrivateMediaPreparation = meshCore.prepareFilePrivate(
        recipientPeerID,
        file,
        transferId,
        allowLegacyFallback
    )

    override fun cancelFileTransfer(transferId: String): Boolean {
        return meshCore.cancelFileTransfer(transferId)
    }

    override fun sendBroadcastAnnounce() {
        meshCore.sendBroadcastAnnounce()
    }

    override fun sendAnnouncementToPeer(peerID: String) {
        meshCore.sendAnnouncementToPeer(peerID)
    }

    override fun getPeerNicknames(): Map<String, String> = meshCore.getPeerNicknames()

    override fun getPeerRSSI(): Map<String, Int> = meshCore.getPeerRSSI()

    override fun getActivePeerCount(): Int = meshCore.getActivePeerCount()

    override fun hasEstablishedSession(peerID: String) = meshCore.hasEstablishedSession(peerID)

    override fun getSessionState(peerID: String) = meshCore.getSessionState(peerID)

    override fun initiateNoiseHandshake(peerID: String) = meshCore.initiateNoiseHandshake(peerID)

    override fun getPeerFingerprint(peerID: String): String? = meshCore.getPeerFingerprint(peerID)

    override fun getPeerInfo(peerID: String): PeerInfo? = meshCore.getPeerInfo(peerID)

    override fun updatePeerInfo(
        peerID: String,
        nickname: String,
        noisePublicKey: ByteArray,
        signingPublicKey: ByteArray,
        isVerified: Boolean
    ): Boolean = meshCore.updatePeerInfo(peerID, nickname, noisePublicKey, signingPublicKey, isVerified)

    override fun getIdentityFingerprint(): String = meshCore.getIdentityFingerprint()

    override fun getStaticNoisePublicKey(): ByteArray? = meshCore.getStaticNoisePublicKey()

    override fun shouldShowEncryptionIcon(peerID: String) = meshCore.shouldShowEncryptionIcon(peerID)

    override fun getEncryptedPeers(): List<String> = meshCore.getEncryptedPeers()

    override fun getDeviceAddressForPeer(peerID: String): String? =
        meshCore.getDeviceAddressForPeer(peerID)

    override fun getDeviceAddressToPeerMapping(): Map<String, String> =
        meshCore.getDeviceAddressToPeerMapping()

    override fun printDeviceAddressesForPeers(): String =
        getDeviceAddressToPeerMapping().entries.joinToString("\n") { "${it.key} -> ${it.value}" }

    override fun getDebugStatus(): String {
        return meshCore.getDebugStatus(
            transportInfo = connectionTracker.getDebugInfo(),
            deviceMap = getDeviceAddressToPeerMapping(),
            extraLines = listOf("Peers: ${connectionTracker.peerSockets.keys}"),
            title = "Wi-Fi Direct Mesh Debug Status"
        )
    }

    override fun clearAllInternalData() {
        meshCore.clearAllInternalData()
    }

    override fun clearAllEncryptionData() {
        meshCore.clearAllEncryptionData()
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // P2P MeshTransport implementation
    // ─────────────────────────────────────────────────────────────────────────────

    private fun broadcastPacket(routed: RoutedPacket): Boolean {
        val packet = routed.packet
        if (packet.senderID.toHexString() == myPeerID && !packet.route.isNullOrEmpty()) {
            val firstHop = packet.route!![0].toHexString()
            if (sendRoutedPacketToPeer(firstHop, routed)) {
                return true
            }
        }

        val recipientId = packet.recipientID?.toHexString()
        if (recipientId != null && !packet.recipientID.contentEquals(SpecialRecipients.BROADCAST)) {
            if (sendRoutedPacketToPeer(recipientId, routed)) {
                return true
            }
        }

        return fragmentingSender.send(routed, "Wi-Fi Direct broadcast") { single ->
            broadcastSinglePacket(single)
        }
    }

    private fun sendPacketToPeer(peerID: String, packet: BitchatPacket): Boolean {
        return sendRoutedPacketToPeer(peerID, RoutedPacket(packet))
    }

    private fun sendRoutedPacketToPeer(peerID: String, routed: RoutedPacket): Boolean {
        if (connectionTracker.getSocketForPeer(peerID) == null) {
            return false
        }
        return fragmentingSender.send(routed, "Wi-Fi Direct peer ${peerID.take(8)}") { single ->
            sendSinglePacketToPeer(peerID, single.packet)
        }
    }

    private fun broadcastSinglePacket(routed: RoutedPacket): Boolean {
        val data = routed.packet.toBinaryData() ?: return false
        var accepted = false
        connectionTracker.forEachSocket { _, sock ->
            try {
                sock.write(data)
                accepted = true
            } catch (e: IOException) {
                Log.e(TAG, "TX: write failed: ${e.message}")
            }
        }
        return accepted
    }

    private fun sendSinglePacketToPeer(peerID: String, packet: BitchatPacket): Boolean {
        val data = packet.toBinaryData() ?: return false
        val sock = connectionTracker.getSocketForPeer(peerID)
        if (sock == null) {
            Log.d(TAG, "TX: no socket for ${peerID.take(8)}")
            return false
        }
        return try {
            sock.write(data)
            true
        } catch (e: IOException) {
            Log.e(TAG, "TX: write to ${peerID.take(8)} failed: ${e.message}")
            false
        }
    }

    private inner class P2pTransport : MeshTransport {
        override val id: String = "P2P"

        override fun broadcastPacket(routed: RoutedPacket): Boolean =
            this@WifiDirectMeshService.broadcastPacket(routed)

        override fun sendPacketToPeer(peerID: String, packet: BitchatPacket): Boolean {
            return this@WifiDirectMeshService.sendPacketToPeer(peerID, packet)
        }

        override fun sendPacketToLink(
            relayAddress: String,
            ingressLinkID: String,
            packet: BitchatPacket
        ): Boolean {
            val link = IngressLinkPolicy.resolve(
                ingressLinkID = ingressLinkID,
                relayAddress = relayAddress,
                links = ingressLinks,
                currentTransportForRelay = connectionTracker::getSocketForPeer
            ) ?: return false
            val data = packet.toBinaryData() ?: return false
            return try {
                link.transport.write(data)
                true
            } catch (e: IOException) {
                Log.e(TAG, "TX: exact-link write to ${relayAddress.take(8)} failed: ${e.message}")
                false
            }
        }

        override fun cancelTransfer(transferId: String): Boolean {
            return fragmentingSender.cancelTransfer(transferId)
        }

        override fun getDeviceAddressForPeer(peerID: String): String? {
            return connectionTracker.getSocketForPeer(peerID)?.inetAddress?.hostAddress
        }

        override fun getDeviceAddressToPeerMapping(): Map<String, String> {
            val map = mutableMapOf<String, String>()
            connectionTracker.peerSockets.forEach { (pid, sock) ->
                map[pid] = sock.inetAddress?.hostAddress ?: "unknown"
            }
            return map
        }

        override fun getTransportDebugInfo(): String {
            return connectionTracker.getDebugInfo()
        }
    }
}
