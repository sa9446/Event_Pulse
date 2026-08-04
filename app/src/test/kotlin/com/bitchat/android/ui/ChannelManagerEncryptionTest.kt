package com.bitchat.android.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.protocol.EncryptedChannelWire
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Verifies the encrypted-channel round trip:
 * - setChannelPassword derives a key from the password + channel name (PBKDF2)
 * - sendEncryptedChannelMessage emits a DAENC: wire payload
 * - a peer that knows the same password can decrypt it
 * - a peer with a different password cannot
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ChannelManagerEncryptionTest {

    private lateinit var context: Context
    private lateinit var scope: TestScope

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        scope = TestScope(UnconfinedTestDispatcher())
    }

    private fun newChannelManager(): ChannelManager {
        val state = ChatState(scope = scope)
        return ChannelManager(
            state = state,
            messageManager = MessageManager(state),
            dataManager = DataManager(context),
            coroutineScope = scope
        )
    }

    @Test
    fun `encrypted message round-trips between peers sharing the password`() {
        val sender = newChannelManager()
        val receiver = newChannelManager()
        sender.setChannelPassword("#secret-room", "hunter2")
        receiver.setChannelPassword("#secret-room", "hunter2")

        var wirePayload: ByteArray? = null
        var fallback = false
        sender.sendEncryptedChannelMessage(
            content = "meet at the north gate",
            mentions = emptyList(),
            channel = "#secret-room",
            senderNickname = "alice",
            myPeerID = "aaaa1111",
            onEncryptedPayload = { wirePayload = it },
            onFallback = { fallback = true }
        )

        assertTrue("encrypted payload should be produced", wirePayload != null)
        assertTrue("fallback should not be used when a key exists", !fallback)

        val wire = String(wirePayload!!, Charsets.UTF_8)
        val parsed = EncryptedChannelWire.parse(wire)
        assertNotNull("wire payload must parse", parsed)
        val (channel, ciphertext) = parsed!!

        assertEquals("#secret-room", channel)
        val decrypted = receiver.decryptChannelMessage(ciphertext, channel)
        assertEquals("meet at the north gate", decrypted)
    }

    @Test
    fun `peer without the password cannot decrypt the payload`() {
        val sender = newChannelManager()
        val receiver = newChannelManager()
        sender.setChannelPassword("#secret-room", "hunter2")
        receiver.setChannelPassword("#secret-room", "wrong-password")

        var wirePayload: ByteArray? = null
        sender.sendEncryptedChannelMessage(
            content = "classified material",
            mentions = emptyList(),
            channel = "#secret-room",
            senderNickname = "alice",
            myPeerID = "aaaa1111",
            onEncryptedPayload = { wirePayload = it },
            onFallback = {}
        )

        val parsed = EncryptedChannelWire.parse(String(wirePayload!!, Charsets.UTF_8))
        assertNotNull(parsed)
        val (channel, ciphertext) = parsed!!
        // Wrong derived key => GCM tag verification fails => null
        assertNull(receiver.decryptChannelMessage(ciphertext, channel))
    }

    @Test
    fun `wire format is not confused with plain text messages`() {
        val plain = "just a normal message"
        assertNull(EncryptedChannelWire.parse(plain))
        assertNull(EncryptedChannelWire.parse("DAENC:"))
        assertNull(EncryptedChannelWire.parse("DAENC:#room:"))
        assertNull(EncryptedChannelWire.parse("DAENC:#room:not-base64!!"))
    }

    @Test
    fun `fallback fires when no channel key is available`() {
        val manager = newChannelManager()
        var wirePayload: ByteArray? = null
        var fallback = false
        manager.sendEncryptedChannelMessage(
            content = "no password set",
            mentions = emptyList(),
            channel = "#open-room",
            senderNickname = "alice",
            myPeerID = "aaaa1111",
            onEncryptedPayload = { wirePayload = it },
            onFallback = { fallback = true }
        )

        assertTrue("must fall back instead of silently dropping", fallback)
        assertNull("no encrypted payload without a key", wirePayload)
    }
}
