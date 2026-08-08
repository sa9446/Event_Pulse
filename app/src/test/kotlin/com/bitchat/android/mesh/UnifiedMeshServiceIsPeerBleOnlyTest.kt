package com.bitchat.android.mesh

import com.bitchat.android.wifiaware.WifiAwareController
import com.bitchat.android.wifiaware.WifiAwareMeshService
import com.bitchat.android.wifidirect.WifiDirectController
import com.bitchat.android.wifidirect.WifiDirectMeshService
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Unit tests for [UnifiedMeshService.isPeerBleOnly] — the transport probe that decides
 * whether a private chat's images drop to the BLE-only compression tier (384px instead of
 * 512px). Getting this wrong sends the smaller payload to Wi-Fi peers (quality lost for
 * nothing) or the full-size payload over a slow BLE link (transfer failure risk).
 *
 * The Wi-Fi transports are injected through the @VisibleForTesting controller seams because
 * both controllers are Kotlin singletons whose live services would otherwise be null in a
 * test JVM (mockStatic cannot intercept instance calls on a Kotlin object INSTANCE).
 */
@RunWith(RobolectricTestRunner::class)
class UnifiedMeshServiceIsPeerBleOnlyTest {

    private companion object {
        const val PEER = "aaaabbbbccccdddd"
    }

    private lateinit var bluetooth: BluetoothMeshService
    private lateinit var unified: UnifiedMeshService

    @Before
    fun setUp() {
        bluetooth = mock<BluetoothMeshService>()
        unified = UnifiedMeshService(RuntimeEnvironment.getApplication(), bluetooth)
    }

    @After
    fun tearDown() {
        WifiAwareController.setServiceForTest(null)
        WifiDirectController.setServiceForTest(null)
    }

    @Test
    fun `peer connected only over BLE is reported as BLE-only`() {
        whenever(bluetooth.getPeerInfo(PEER)).thenReturn(peerInfo(connected = true, direct = false))

        assertTrue("BLE-only peer must be flagged for the 384px tier", unified.isPeerBleOnly(PEER))
    }

    @Test
    fun `peer reachable over Wi-Fi Aware is not BLE-only`() {
        val aware = mock<WifiAwareMeshService>()
        WifiAwareController.setServiceForTest(aware)
        whenever(bluetooth.getPeerInfo(PEER)).thenReturn(peerInfo())
        whenever(aware.getPeerInfo(PEER)).thenReturn(peerInfo())

        assertFalse("A Wi-Fi Aware link means full 512px resolution", unified.isPeerBleOnly(PEER))
    }

    @Test
    fun `peer reachable over Wi-Fi Direct is not BLE-only`() {
        val p2p = mock<WifiDirectMeshService>()
        WifiDirectController.setServiceForTest(p2p)
        whenever(bluetooth.getPeerInfo(PEER)).thenReturn(peerInfo())
        whenever(p2p.getPeerInfo(PEER)).thenReturn(peerInfo())

        assertFalse("A Wi-Fi Direct link means full 512px resolution", unified.isPeerBleOnly(PEER))
    }

    @Test
    fun `offline or nostr-only peer is not BLE-only`() {
        whenever(bluetooth.getPeerInfo(PEER)).thenReturn(null)

        assertFalse("An unreachable peer cannot be classified BLE-only", unified.isPeerBleOnly(PEER))
    }

    @Test
    fun `device without a Wi-Fi stack falls back to BLE-only for a direct peer`() {
        // No Wi-Fi services are injected, so both controllers report null — the same state as
        // a device whose Wi-Fi stack is absent — and the peer is a confirmed BLE neighbor.
        whenever(bluetooth.getPeerInfo(PEER)).thenReturn(peerInfo(connected = false, direct = true))

        assertTrue("BLE-direct peer on a Wi-Fi-less device must use the 384px tier", unified.isPeerBleOnly(PEER))
    }

    @Test
    fun `peer not present on an available Wi-Fi transport is still BLE-only`() {
        // The Wi-Fi stack is up (a service is published) but the peer is not on it — e.g. the
        // Aware radio started while this peer was only ever reached via BLE.
        val aware = mock<WifiAwareMeshService>()
        WifiAwareController.setServiceForTest(aware)
        whenever(bluetooth.getPeerInfo(PEER)).thenReturn(peerInfo(connected = true, direct = false))

        assertTrue(
            "A peer absent from the Wi-Fi transport must keep the BLE-only tier",
            unified.isPeerBleOnly(PEER)
        )
    }

    @Test
    fun `peer absent from the Wi-Fi Direct transport is still BLE-only`() {
        // Same as above on the P2P side: a published Wi-Fi Direct service whose peer table
        // does not contain the target cannot lift the peer off the BLE-only tier.
        val p2p = mock<WifiDirectMeshService>()
        WifiDirectController.setServiceForTest(p2p)
        whenever(bluetooth.getPeerInfo(PEER)).thenReturn(peerInfo(connected = true, direct = false))

        assertTrue(
            "A peer absent from Wi-Fi Direct must keep the BLE-only tier",
            unified.isPeerBleOnly(PEER)
        )
    }

    private fun peerInfo(connected: Boolean = true, direct: Boolean = true) = PeerInfo(
        id = PEER,
        nickname = "test peer",
        isConnected = connected,
        isDirectConnection = direct,
        noisePublicKey = ByteArray(32),
        signingPublicKey = ByteArray(32),
        isVerifiedNickname = false,
        lastSeen = System.currentTimeMillis()
    )
}
