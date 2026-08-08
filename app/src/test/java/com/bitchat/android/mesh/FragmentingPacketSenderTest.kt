package com.bitchat.android.mesh

import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.util.AppConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Random

@RunWith(RobolectricTestRunner::class)
class FragmentingPacketSenderTest {

    private val senderID = "1122334455667788"

    private fun packetWithPayload(bytes: Int): BitchatPacket {
        val payload = ByteArray(bytes)
        Random(42).nextBytes(payload)
        return BitchatPacket(
            version = 2u,
            type = MessageType.FILE_TRANSFER.value,
            senderID = MeshPacketUtils.hexStringToByteArray(senderID),
            recipientID = null,
            timestamp = System.currentTimeMillis().toULong(),
            payload = payload,
            signature = null,
            ttl = 7u
        )
    }

    @Test
    fun `oversized packet exceeding receiver fragment cap is rejected with fail event`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val sender = FragmentingPacketSender(scope, FragmentManager(), "test")
        // Clearly exceeds MAX_FRAGMENTS_PER_ID * MAX_FRAGMENT_SIZE (the sender-side ceiling)
        val cap = com.bitchat.android.util.AppConstants.Fragmentation.MAX_FRAGMENTS_PER_ID
        val packet = packetWithPayload(cap * com.bitchat.android.util.AppConstants.Fragmentation.MAX_FRAGMENT_SIZE + 1024)
        var sent = false

        val failed = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val collectJob = launch(Dispatchers.Default) {
            TransferProgressManager.events.collect { event ->
                if (event.failed) failed.add(event.transferId)
            }
        }
        kotlinx.coroutines.delay(100) // activate subscription before emitting

        val accepted = sender.send(RoutedPacket(packet, transferId = "oversize-test"), "test") { sent = true; true }
        assertFalse(accepted)
        assertFalse(sent)
        withTimeout(5_000) {
            while (!failed.contains("oversize-test")) {
                kotlinx.coroutines.delay(10)
            }
        }
        collectJob.cancel()
        Unit
    }

    @Test
    fun `packet within fragment cap is accepted`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val sender = FragmentingPacketSender(scope, FragmentManager(), "test", interFragmentDelayMs = 0L)
        val packet = packetWithPayload(10_000)
        var writes = 0

        val accepted = sender.send(RoutedPacket(packet, transferId = "fits-test"), "test") { writes += 1; true }
        assertTrue(accepted)
        withTimeout(5_000) {
            while (writes == 0) {
                kotlinx.coroutines.delay(10)
            }
        }
        assertTrue(writes > 0)
    }

    @Test
    fun `fragment count at cap boundary is not rejected`() {
        val manager = FragmentManager()
        val packet = packetWithPayload(AppConstants.Fragmentation.MAX_FRAGMENTS_PER_ID * 400)
        val fragments = manager.createFragments(packet, AppConstants.Fragmentation.MAX_FRAGMENTS_PER_ID)
        assertTrue(fragments.isNotEmpty())
        assertTrue(fragments.size <= AppConstants.Fragmentation.MAX_FRAGMENTS_PER_ID)
    }

    @Test
    fun `fragment send retries a transient failure and completes`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val manager = FragmentManager()
        val sender = FragmentingPacketSender(scope, manager, "test", interFragmentDelayMs = 0L)
        val packet = packetWithPayload(5_000)
        val fragmentCount = manager.createFragments(
            packet,
            AppConstants.Fragmentation.MAX_FRAGMENTS_PER_ID
        ).size
        assertTrue(fragmentCount > 1)

        // Every fragment's first write is rejected (transient GATT-style hiccup); the retry
        // path must resend it and the transfer must still complete without a failure event.
        val firstFailurePerPacket = java.util.concurrent.ConcurrentHashMap.newKeySet<BitchatPacket>()
        var calls = 0
        val completed = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val failed = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val collectJob = launch(Dispatchers.Default) {
            TransferProgressManager.events.collect { event ->
                if (event.transferId == "retry-transient") {
                    if (event.completed && !event.failed) completed.add(event.transferId)
                    if (event.failed) failed.add(event.transferId)
                }
            }
        }
        kotlinx.coroutines.delay(100) // activate subscription before emitting

        val accepted = sender.send(RoutedPacket(packet, transferId = "retry-transient"), "test") { fragment ->
            calls++
            if (firstFailurePerPacket.add(fragment.packet)) false else true
        }
        assertTrue(accepted)
        withTimeout(5_000) {
            while (!completed.contains("retry-transient")) {
                kotlinx.coroutines.delay(10)
            }
        }
        // One rejected write + one successful retry for every fragment.
        assertEquals(fragmentCount * 2, calls)
        assertTrue("Transient failures must not surface as a failed transfer", failed.isEmpty())
        collectJob.cancel()
        Unit
    }

    @Test
    fun `fragment send fails after exhausting max retries`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val sender = FragmentingPacketSender(scope, FragmentManager(), "test", interFragmentDelayMs = 0L)
        val packet = packetWithPayload(5_000)
        var calls = 0
        val failed = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val collectJob = launch(Dispatchers.Default) {
            TransferProgressManager.events.collect { event ->
                if (event.failed && event.transferId == "retry-exhausted") {
                    failed.add(event.transferId)
                }
            }
        }
        kotlinx.coroutines.delay(100) // activate subscription before emitting

        val accepted = sender.send(RoutedPacket(packet, transferId = "retry-exhausted"), "test") {
            calls++
            false // every write is rejected
        }
        assertTrue(accepted)
        withTimeout(5_000) {
            while (!failed.contains("retry-exhausted")) {
                kotlinx.coroutines.delay(10)
            }
        }
        // Initial write + MAX_FRAGMENT_SEND_RETRIES retries for the first fragment, then the
        // sender gives up and surfaces the failure to the UI.
        assertEquals(FragmentingPacketSender.MAX_FRAGMENT_SEND_RETRIES + 1, calls)
        collectJob.cancel()
        Unit
    }

    @Test
    fun `exception during fragment write is retried and send completes`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val manager = FragmentManager()
        val sender = FragmentingPacketSender(scope, manager, "test", interFragmentDelayMs = 0L)
        val packet = packetWithPayload(5_000)
        val fragmentCount = manager.createFragments(
            packet,
            AppConstants.Fragmentation.MAX_FRAGMENTS_PER_ID
        ).size
        assertTrue(fragmentCount > 1)

        // The first write of every fragment throws; the retry path must catch the exception,
        // resend, and still complete the transfer.
        val threwPerPacket = java.util.concurrent.ConcurrentHashMap.newKeySet<BitchatPacket>()
        var calls = 0
        val completed = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val collectJob = launch(Dispatchers.Default) {
            TransferProgressManager.events.collect { event ->
                if (event.transferId == "retry-exception" && event.completed && !event.failed) {
                    completed.add(event.transferId)
                }
            }
        }
        kotlinx.coroutines.delay(100) // activate subscription before emitting

        val accepted = sender.send(RoutedPacket(packet, transferId = "retry-exception"), "test") { fragment ->
            calls++
            if (threwPerPacket.add(fragment.packet)) {
                throw java.io.IOException("transient write failure")
            }
            true
        }
        assertTrue(accepted)
        withTimeout(5_000) {
            while (!completed.contains("retry-exception")) {
                kotlinx.coroutines.delay(10)
            }
        }
        // One throwing write + one successful retry for every fragment.
        assertEquals(fragmentCount * 2, calls)
        collectJob.cancel()
        Unit
    }
}
