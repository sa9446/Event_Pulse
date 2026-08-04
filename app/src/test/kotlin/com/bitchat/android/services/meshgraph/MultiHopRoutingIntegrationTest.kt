package com.bitchat.android.services.meshgraph

import com.bitchat.android.mesh.MeshPacketUtils
import com.bitchat.android.mesh.PacketRelayManager
import com.bitchat.android.mesh.PacketRelayManagerDelegate
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.util.toHexString
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Integration-style coverage of the real multi-hop routing pipeline used for
 * A→B→C delivery: verified gossip announcements feed the shared MeshGraphService,
 * RoutePlanner computes the source route, the intermediate hops are packed into the
 * packet (mirroring MeshCore.applyRouteIfAvailable), and PacketRelayManager forwards
 * hop-by-hop along that route — never flooding.
 *
 * Uses the REAL MeshGraphService singleton + REAL RoutePlanner + REAL PacketRelayManager.
 */
class MultiHopRoutingIntegrationTest {

    private companion object {
        // 8-byte peer IDs (16 hex chars), as produced by the real app.
        const val PEER_A = "0000000000000001"
        const val PEER_B = "0000000000000002"
        const val PEER_C = "0000000000000003"
        const val PEER_D = "0000000000000004"
    }

    @Before
    fun setUp() {
        MeshGraphService.resetForTesting()
    }

    /** Mirrors MeshCore.applyRouteIfAvailable: intermediates = path minus src and dst. */
    private fun sourceRouteIntermediates(path: List<String>): List<ByteArray> =
        path.subList(1, path.size - 1).map { MeshPacketUtils.hexStringToByteArray(it) }

    private fun createPacket(
        route: List<ByteArray>?,
        recipient: String? = null
    ): BitchatPacket {
        return BitchatPacket(
            type = MessageType.MESSAGE.value,
            senderID = MeshPacketUtils.hexStringToByteArray(PEER_A),
            recipientID = recipient?.let { MeshPacketUtils.hexStringToByteArray(it) },
            timestamp = 1UL,
            payload = "hello mesh".toByteArray(),
            ttl = 5u,
            route = route
        )
    }

    /** Real delegate stub: records every sendToPeer/broadcast without dropping packets. */
    private class RecordingDelegate : PacketRelayManagerDelegate {
        val sentTo = mutableListOf<String>()
        var lastRouted: RoutedPacket? = null
        var broadcastCount = 0
        override fun getNetworkSize(): Int = 3
        override fun getBroadcastRecipient(): ByteArray = ByteArray(8)
        override fun broadcastPacket(routed: RoutedPacket) {
            broadcastCount++
        }
        override fun sendToPeer(peerID: String, routed: RoutedPacket): Boolean {
            sentTo.add(peerID)
            lastRouted = routed
            return true
        }
    }

    @Test
    fun `three node line delivers A to C through relay B without flooding`() = runTest {
        // Gossip: A<->B<->C, all two-way so the graph edges become confirmed.
        val graph = MeshGraphService.getInstance()
        graph.updateFromAnnouncement(PEER_A, "Alice", listOf(PEER_B), 1UL)
        graph.updateFromAnnouncement(PEER_B, "Bob", listOf(PEER_A, PEER_C), 1UL)
        graph.updateFromAnnouncement(PEER_C, "Charlie", listOf(PEER_B), 1UL)

        // Route planning: A→B→C.
        val path = RoutePlanner.shortestPath(PEER_A, PEER_C)
        assertNotNull(path)
        assertEquals(listOf(PEER_A, PEER_B, PEER_C), path)

        // Source-route construction (as done in MeshCore): only intermediate B is packed.
        val route = sourceRouteIntermediates(path!!)
        assertEquals(listOf(PEER_B), route.map { it.toHexString() })

        // Relay at B: receives A's packet addressed to C, route=[B]. Must forward to C.
        val relayB = RecordingDelegate()
        val managerB = PacketRelayManager(PEER_B).apply { delegate = relayB }
        managerB.handlePacketRelay(RoutedPacket(createPacket(route, PEER_C), PEER_A))

        assertEquals(listOf(PEER_C), relayB.sentTo)
        assertEquals(0, relayB.broadcastCount)
        // The route is preserved for the final hop.
        assertEquals(route.map { it.toHexString() }, relayB.lastRouted?.packet?.route?.map { it.toHexString() })
    }

    @Test
    fun `four node chain A-B-D-C is routed hop by hop`() = runTest {
        val graph = MeshGraphService.getInstance()
        graph.updateFromAnnouncement(PEER_A, "Alice", listOf(PEER_B), 1UL)
        graph.updateFromAnnouncement(PEER_B, "Bob", listOf(PEER_A, PEER_D), 1UL)
        graph.updateFromAnnouncement(PEER_D, "Dan", listOf(PEER_B, PEER_C), 1UL)
        graph.updateFromAnnouncement(PEER_C, "Charlie", listOf(PEER_D), 1UL)

        val path = RoutePlanner.shortestPath(PEER_A, PEER_C)
        assertNotNull(path)
        assertEquals(listOf(PEER_A, PEER_B, PEER_D, PEER_C), path)

        val route = sourceRouteIntermediates(path!!)
        assertEquals(listOf(PEER_B, PEER_D), route.map { it.toHexString() })

        // Hop 1: at B, route=[B,D] -> next hop is D.
        val relayB = RecordingDelegate()
        PacketRelayManager(PEER_B).apply { delegate = relayB }
            .handlePacketRelay(RoutedPacket(createPacket(route, PEER_C), PEER_A))
        assertEquals(listOf(PEER_D), relayB.sentTo)
        assertEquals(0, relayB.broadcastCount)

        // Hop 2: at D, route=[B,D] -> D is the last intermediate, next hop is recipient C.
        val relayD = RecordingDelegate()
        PacketRelayManager(PEER_D).apply { delegate = relayD }
            .handlePacketRelay(RoutedPacket(createPacket(route, PEER_C), PEER_A))
        assertEquals(listOf(PEER_C), relayD.sentTo)
        assertEquals(0, relayD.broadcastCount)
    }

    @Test
    fun `broken link removes the route so planner reports no path`() {
        val graph = MeshGraphService.getInstance()
        graph.updateFromAnnouncement(PEER_A, "Alice", listOf(PEER_B), 1UL)
        graph.updateFromAnnouncement(PEER_B, "Bob", listOf(PEER_A, PEER_C), 1UL)
        graph.updateFromAnnouncement(PEER_C, "Charlie", listOf(PEER_B), 1UL)

        assertNotNull(RoutePlanner.shortestPath(PEER_A, PEER_C))

        // B disappears from the mesh: the A-C route must be gone, not stale.
        graph.removePeer(PEER_B)

        assertNull(RoutePlanner.shortestPath(PEER_A, PEER_C))
        // A and C are now isolated (B is gone entirely).
        assertNull(RoutePlanner.shortestPath(PEER_A, PEER_B))
        assertTrue("graph must be empty after the hub disappears",
            graph.graphState.value.edges.isEmpty())
    }
}
