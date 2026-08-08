package com.bitchat.android.noise

import com.bitchat.android.noise.southernstorm.protocol.CipherStatePair
import com.bitchat.android.noise.southernstorm.protocol.DHState
import com.bitchat.android.noise.southernstorm.protocol.DHStateHybrid
import com.bitchat.android.noise.southernstorm.protocol.HandshakeState
import com.bitchat.android.noise.southernstorm.protocol.Noise
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Arrays

/**
 * Exercises the Bouncy Castle ML-KEM-768 hybrid DH state and the full
 * Noise_XXhfs_25519+MLKEM768_ChaChaPoly_SHA256 handshake used for post-quantum sessions.
 */
class MLKEMDHStateTest {

    private val cleanup = mutableListOf<Any>()

    @After
    fun tearDown() {
        cleanup.forEach {
            when (it) {
                is com.bitchat.android.noise.southernstorm.protocol.Destroyable -> it.destroy()
                is AutoCloseable -> runCatching { it.close() }
            }
        }
        cleanup.clear()
    }

    private fun <T : Any> track(obj: T): T = obj.also { cleanup += it }

    @Test
    fun `ML-KEM-768 key lengths match the FIPS 203 wire sizes`() {
        val alice = track(Noise.createDH("MLKEM768") as DHStateHybrid)
        alice.generateKeyPair()

        assertEquals(1184, alice.getPublicKeyLength())
        assertEquals(1632, alice.getPrivateKeyLength())
        assertEquals(32, alice.getSharedKeyLength())

        // The stored decapsulation key must really be the expanded 1632-byte dk (not a
        // seed-only 64-byte encoding) or getPrivateKeyLength() would be a lie.
        val dk = ByteArray(alice.getPrivateKeyLength())
        alice.getPrivateKey(dk, 0)
        assertEquals(1632, dk.size)

        val bob = track(Noise.createDH("MLKEM768") as DHStateHybrid)
        bob.generateKeyPair(alice)
        assertEquals(1088, bob.getPublicKeyLength())
        assertEquals(32, bob.getPrivateKeyLength())
    }

    @Test
    fun `Alice and Bob derive identical 32-byte shared secrets`() {
        val alice = track(Noise.createDH("MLKEM768") as DHStateHybrid)
        alice.generateKeyPair()

        val bob = track(Noise.createDH("MLKEM768") as DHStateHybrid)
        bob.generateKeyPair(alice)

        val aliceSecret = ByteArray(32)
        val bobSecret = ByteArray(32)
        alice.calculate(aliceSecret, 0, bob)
        bob.calculate(bobSecret, 0, alice)

        assertArrayEquals("Both sides must agree on the ML-KEM shared secret", aliceSecret, bobSecret)
        assertNotEquals("Shared secret must not be all zeros", ByteArray(32), aliceSecret)

        // A third, unrelated Alice key must not agree with Bob.
        val stranger = track(Noise.createDH("MLKEM768") as DHStateHybrid)
        stranger.generateKeyPair()
        val strangerSecret = ByteArray(32)
        stranger.calculate(strangerSecret, 0, bob)
        assertTrue("Different decapsulation keys must yield different secrets",
            !Arrays.equals(strangerSecret, aliceSecret))
    }

    @Test
    fun `generateKeyPair with a null remote produces an Alice key`() {
        val alice = track(Noise.createDH("MLKEM768") as DHStateHybrid)
        alice.generateKeyPair(null)
        assertEquals(1184, alice.getPublicKeyLength())
        assertTrue(alice.hasPrivateKey())
    }

    @Test
    fun `full XXhfs hybrid handshake splits into working transport ciphers`() {
        val protocol = "Noise_XXhfs_25519+MLKEM768_ChaChaPoly_SHA256"

        val initiator = track(HandshakeState(protocol, HandshakeState.INITIATOR))
        val responder = track(HandshakeState(protocol, HandshakeState.RESPONDER))

        val initLocal = track(Noise.createDH("25519"))
        initLocal.generateKeyPair()
        val respLocal = track(Noise.createDH("25519"))
        respLocal.generateKeyPair()

        initiator.getLocalKeyPair().copyFrom(initLocal)
        responder.getLocalKeyPair().copyFrom(respLocal)
        initiator.start()
        responder.start()

        val payloadBuf = ByteArray(4096)

        // Message 1: initiator sends e + ML-KEM encapsulation key (~1216 bytes).
        val message1 = ByteArray(4096)
        val message1Len = initiator.writeMessage(message1, 0, null, 0, 0)
        assertTrue("PQ handshake message 1 must carry the ML-KEM encapsulation key",
            message1Len > 1000)
        responder.readMessage(message1, 0, message1Len, payloadBuf, 0)
        assertEquals(HandshakeState.WRITE_MESSAGE, responder.getAction())

        // Message 2: responder sends e, ML-KEM ciphertext, then static key.
        val message2 = ByteArray(4096)
        val message2Len = responder.writeMessage(message2, 0, null, 0, 0)
        assertTrue("PQ handshake message 2 must carry the ML-KEM ciphertext",
            message2Len > 1000)
        initiator.readMessage(message2, 0, message2Len, payloadBuf, 0)
        assertEquals(HandshakeState.WRITE_MESSAGE, initiator.getAction())

        // Message 3: initiator sends its static key.
        val message3 = ByteArray(4096)
        val message3Len = initiator.writeMessage(message3, 0, null, 0, 0)
        responder.readMessage(message3, 0, message3Len, payloadBuf, 0)
        assertEquals(HandshakeState.SPLIT, responder.getAction())

        val initPair = track(initiator.split())
        val respPair = track(responder.split())

        // Round-trip a transport message through the derived ChaCha20-Poly1305 keys.
        val plaintext = "hybrid transport works".toByteArray()
        val ciphertext = ByteArray(plaintext.size + 16)
        val ciphertextLen = initPair.getSender()
            .encryptWithAd(null, plaintext, 0, ciphertext, 0, plaintext.size)
        val decrypted = ByteArray(plaintext.size)
        val decryptedLen = respPair.getReceiver()
            .decryptWithAd(null, ciphertext, 0, decrypted, 0, ciphertextLen)
        assertArrayEquals(plaintext, decrypted.copyOf(decryptedLen))
    }
}
