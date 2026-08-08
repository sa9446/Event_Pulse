package com.bitchat.android.mesh

import com.bitchat.android.model.BitchatFilePacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Random

/**
 * Proves file content survives the exact wire path used for voice notes, images,
 * videos and documents:
 *
 *   BitchatFilePacket.encode() -> FILE_TRANSFER BitchatPacket (v2)
 *     -> FragmentManager.createFragments() -> handleFragment() reassembly
 *     -> BitchatPacket.fromBinaryData() -> BitchatFilePacket.decode()
 *
 * A regression here would manifest on devices as "only the file metadata arrives".
 */
@RunWith(RobolectricTestRunner::class)
class FileWireRoundTripTest {

    private lateinit var fragmentManager: FragmentManager
    private val myPeerID = "1122334455667788"
    private val random = Random(42)

    @Before
    fun setup() {
        fragmentManager = FragmentManager()
    }

    @Test
    fun `small file survives full wire round trip`() {
        val original = filePacket(
            name = "note.m4a",
            mime = "audio/mp4",
            size = 512,
            fill = { index -> (index % 256).toByte() }
        )
        assertRoundTrip(original)
    }

    @Test
    fun `large file survives fragmented wire round trip`() {
        // ~50 KB of pseudo-random content forces many fragments (well under Android's
        // MAX_FRAGMENTS_PER_ID = 10_000 reassembly cap; upstream iOS peers cap at 256, a
        // peer-side limit); any truncation in chunking/reassembly would surface here.
        val content = ByteArray(50_000) { random.nextInt(256).toByte() }
        val original = BitchatFilePacket(
            fileName = "photo.webp",
            fileSize = content.size.toLong(),
            mimeType = "image/webp",
            content = content
        )
        assertRoundTrip(original)
    }

    @Test
    fun `packets beyond receiver fragment cap are rejected rather than truncated`() {
        // A payload requiring more than MAX_FRAGMENTS_PER_ID (10_000) fragments must be
        // rejected up front (sender) and on reassembly (receiver) - never partially delivered.
        // ~10 MB / ~462 bytes per fragment ≈ 21 600 fragments, far beyond the cap.
        val content = ByteArray(10_000_000) { random.nextInt(256).toByte() }
        val packet = BitchatPacket(
            version = 2u,
            type = MessageType.FILE_TRANSFER.value,
            senderID = hexBytes(myPeerID),
            recipientID = SpecialRecipients.BROADCAST,
            timestamp = System.currentTimeMillis().toULong(),
            payload = BitchatFilePacket(
                "big.bin",
                content.size.toLong(),
                "application/octet-stream",
                content
            ).encode()!!,
            signature = null,
            ttl = 7u
        )

        val capped = fragmentManager.createFragments(
            packet,
            com.bitchat.android.util.AppConstants.Fragmentation.MAX_FRAGMENTS_PER_ID
        )
        assertTrue("Sender must reject payloads exceeding the fragment cap", capped.isEmpty())

        // Also verify the receiver rejects an over-cap fragment set instead of accepting
        // a partial stream that would never reassemble.
        val uncapped = fragmentManager.createFragments(packet)
        val receiver = FragmentManager()
        var reassembled: BitchatPacket? = null
        for (fragment in uncapped) {
            val out = receiver.handleFragment(fragment)
            if (out != null) reassembled = out
        }
        assertTrue("Receiver must never reassemble an over-cap fragment set", reassembled == null)
    }

    private fun assertRoundTrip(original: BitchatFilePacket) {
        val tlv = original.encode()
        assertNotNull("BitchatFilePacket encode must succeed", tlv)

        val wirePacket = BitchatPacket(
            version = 2u, // FILE_TRANSFER uses v2 for 4-byte payload length
            type = MessageType.FILE_TRANSFER.value,
            senderID = hexBytes(myPeerID),
            recipientID = SpecialRecipients.BROADCAST,
            timestamp = System.currentTimeMillis().toULong(),
            payload = tlv!!,
            signature = null,
            ttl = 7u
        )

        val fragments = fragmentManager.createFragments(wirePacket)
        assertTrue("Expected fragmentation for large payload", fragments.isNotEmpty())

        // Reassemble with a fresh FragmentManager, exactly like the receive path.
        val receiver = FragmentManager()
        var reassembled: BitchatPacket? = null
        fragments.forEach { fragment ->
            assertEquals("Fragment packets must be FRAGMENT type", MessageType.FRAGMENT.value, fragment.type)
            val out = receiver.handleFragment(fragment)
            if (out != null) reassembled = out
        }
        assertNotNull("Fragment reassembly must produce the original packet", reassembled)
        val reconstructed = reassembled ?: error("fragment reassembly returned null")
        assertEquals("Reassembled packet must be FILE_TRANSFER", MessageType.FILE_TRANSFER.value, reconstructed.type)
        assertArrayEquals("Reassembled TLV payload must match original", tlv, reconstructed.payload)

        val decoded = BitchatFilePacket.decode(reconstructed.payload)
        assertNotNull("Reassembled payload must decode as a file packet", decoded)
        val result = decoded ?: error("decoded file packet was null")
        assertEquals(original.fileName, result.fileName)
        assertEquals(original.mimeType, result.mimeType)
        assertEquals(original.fileSize, result.fileSize)
        assertEquals(original.content.size, result.content.size)
        assertArrayEquals("File content must survive the wire byte-for-byte", original.content, result.content)
    }

    @Test
    fun `each fragment fits within BLE MTU budget`() {
        val content = ByteArray(50_000) { random.nextInt(256).toByte() }
        val packet = BitchatPacket(
            version = 2u,
            type = MessageType.FILE_TRANSFER.value,
            senderID = hexBytes(myPeerID),
            recipientID = SpecialRecipients.BROADCAST,
            timestamp = System.currentTimeMillis().toULong(),
            payload = BitchatFilePacket("a.bin", content.size.toLong(), "application/octet-stream", content).encode()!!,
            signature = null,
            ttl = 7u
        )
        val fragments = fragmentManager.createFragments(packet)
        assertTrue(fragments.size > 1)
        fragments.forEach { fragment ->
            val size = fragment.toBinaryData(padding = false)?.size ?: 0
            assertTrue("Fragment encoded size $size must fit a 512+ MTU", size <= 517 - 3)
        }
    }

    private fun filePacket(
        name: String,
        mime: String,
        size: Int,
        fill: (Int) -> Byte
    ): BitchatFilePacket {
        val content = ByteArray(size) { fill(it) }
        return BitchatFilePacket(
            fileName = name,
            fileSize = content.size.toLong(),
            mimeType = mime,
            content = content
        )
    }

    private fun hexBytes(hex: String): ByteArray {
        val result = ByteArray(hex.length / 2)
        for (i in result.indices) {
            result[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return result
    }
}
