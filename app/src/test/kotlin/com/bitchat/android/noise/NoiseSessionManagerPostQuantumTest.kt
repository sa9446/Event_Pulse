package com.bitchat.android.noise

import com.bitchat.android.noise.southernstorm.protocol.Noise
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the hybrid ML-KEM (post-quantum) handshake negotiation in NoiseSessionManager,
 * including automatic responder fallback for classic X25519-only peers.
 */
class NoiseSessionManagerPostQuantumTest {

    private data class TestIdentity(
        val privateKey: ByteArray,
        val publicKey: ByteArray,
        val peerID: String
    )

    private val managers = mutableListOf<NoiseSessionManager>()

    @After
    fun tearDown() {
        managers.forEach(NoiseSessionManager::shutdown)
    }

    @Test
    fun `post-quantum peers establish an ML-KEM session and report it`() {
        val aliceId = identity()
        val bobId = identity()
        val alice = manager(aliceId, postQuantum = true)
        val bob = manager(bobId, postQuantum = true)

        completeHandshake(alice, aliceId.peerID, bob, bobId.peerID)

        assertTrue(alice.hasEstablishedSession(bobId.peerID))
        assertTrue(bob.hasEstablishedSession(aliceId.peerID))
        assertTrue("PQ-enabled initiator must report the session as post-quantum",
            alice.isSessionPostQuantum(bobId.peerID))
        assertTrue("PQ-enabled responder must report the session as post-quantum",
            bob.isSessionPostQuantum(aliceId.peerID))

        val plaintext = "pq transport".toByteArray()
        assertArrayEquals(
            plaintext,
            bob.decrypt(alice.encrypt(plaintext, bobId.peerID), aliceId.peerID)
        )
    }

    @Test
    fun `responder automatically falls back to classic for an X25519-only initiator`() {
        val classicId = identity()
        val responderId = identity()
        val classicInitiator = manager(classicId, postQuantum = false)
        val pqResponder = manager(responderId, postQuantum = true)

        completeHandshake(classicInitiator, classicId.peerID, pqResponder, responderId.peerID)

        assertTrue(classicInitiator.hasEstablishedSession(responderId.peerID))
        assertTrue(pqResponder.hasEstablishedSession(classicId.peerID))
        assertFalse("The PQ-enabled responder must have downgraded to the classic protocol",
            pqResponder.isSessionPostQuantum(classicId.peerID))
        assertFalse(classicInitiator.isSessionPostQuantum(responderId.peerID))

        val plaintext = "mixed fleet still works".toByteArray()
        assertArrayEquals(
            plaintext,
            pqResponder.decrypt(
                classicInitiator.encrypt(plaintext, responderId.peerID),
                classicId.peerID
            )
        )
    }

    @Test
    fun `classic-only devices pair fine when both are classic`() {
        val aliceId = identity()
        val bobId = identity()
        val alice = manager(aliceId, postQuantum = false)
        val bob = manager(bobId, postQuantum = false)

        completeHandshake(alice, aliceId.peerID, bob, bobId.peerID)

        assertFalse(alice.isSessionPostQuantum(bobId.peerID))
        val plaintext = "classic transport".toByteArray()
        assertArrayEquals(
            plaintext,
            bob.decrypt(alice.encrypt(plaintext, bobId.peerID), aliceId.peerID)
        )
    }

    @Test
    fun `PQ initiator falls back to classic once when the responder is classic-only`() {
        val initiatorId = identity()
        val responderId = identity()
        val initiator = manager(initiatorId, postQuantum = true)
        val responder = manager(responderId, postQuantum = false)
        val fallbackMessages = mutableListOf<Pair<String, ByteArray>>()
        initiator.onClassicFallbackInitiated = { pid, msg1 -> fallbackMessages.add(pid to msg1) }

        // The PQ initiator sends the 1216-byte hybrid message 1; a classic-only responder cannot
        // parse it and rejects the handshake (returns nothing, session destroyed).
        val pqMessage1 = initiator.initiateHandshake(responderId.peerID)!!
        assertTrue(pqMessage1.size > 1000)
        assertNull(
            runCatching { responder.processHandshakeMessage(initiatorId.peerID, pqMessage1) }
                .getOrNull()
        )

        // Force the handshake sweep past the 10s timeout: the manager should re-initiate once
        // with the classic protocol and hand the new message 1 to the transport callback.
        initiator.cleanupStaleHandshakes(System.currentTimeMillis() + 11_000L)

        val (fallbackPeerID, fallbackMessage1) = fallbackMessages.single()
        assertEquals(responderId.peerID, fallbackPeerID)
        assertEquals("Classic fallback message 1 is a bare ephemeral", 32, fallbackMessage1.size)

        // The classic fallback completes against the classic-only responder.
        val message2 = responder.processHandshakeMessage(initiatorId.peerID, fallbackMessage1)!!
        val message3 = initiator.processHandshakeMessage(responderId.peerID, message2)!!
        assertNull(responder.processHandshakeMessage(initiatorId.peerID, message3))

        assertTrue(initiator.hasEstablishedSession(responderId.peerID))
        assertFalse(initiator.isSessionPostQuantum(responderId.peerID))
    }

    @Test
    fun `strict mode responder refuses a classic initiator`() {
        val classicId = identity()
        val strictId = identity()
        val classicInitiator = manager(classicId, postQuantum = false)
        val strictResponder = manager(identity = strictId, postQuantum = true, blockClassic = true)

        val classicMessage1 = classicInitiator.initiateHandshake(strictId.peerID)!!
        assertEquals("Classic message 1 is a bare ephemeral", 32, classicMessage1.size)
        assertNull(
            "Strict PQ responder must reject the classic handshake outright",
            runCatching { strictResponder.processHandshakeMessage(classicId.peerID, classicMessage1) }
                .getOrNull()
        )
        assertFalse(strictResponder.hasEstablishedSession(classicId.peerID))
        assertFalse(strictResponder.isSessionPostQuantum(classicId.peerID))
    }

    @Test
    fun `strict mode initiator never falls back to classic on timeout`() {
        val initiatorId = identity()
        val responderId = identity()
        val initiator = manager(identity = initiatorId, postQuantum = true, blockClassic = true)
        val responder = manager(responderId, postQuantum = false)
        val fallbackMessages = mutableListOf<Pair<String, ByteArray>>()
        initiator.onClassicFallbackInitiated = { pid, msg1 -> fallbackMessages.add(pid to msg1) }

        // The strict initiator sends the hybrid message 1; a classic-only responder cannot
        // parse it and rejects the handshake (returns nothing, session destroyed).
        val pqMessage1 = initiator.initiateHandshake(responderId.peerID)!!
        assertTrue(pqMessage1.size > 1000)
        assertNull(
            runCatching { responder.processHandshakeMessage(initiatorId.peerID, pqMessage1) }
                .getOrNull()
        )

        // Sweep past the 10s timeout: strict mode must NOT emit a classic fallback retry.
        initiator.cleanupStaleHandshakes(System.currentTimeMillis() + 11_000L)

        assertTrue("Strict PQ initiator must not downgrade to classic", fallbackMessages.isEmpty())
        assertFalse(initiator.hasEstablishedSession(responderId.peerID))
        assertFalse(initiator.isSessionPostQuantum(responderId.peerID))
    }

    @Test
    fun `message one size distinguishes the two protocols`() {
        val classic = manager(identity(), postQuantum = false)
        val pq = manager(identity(), postQuantum = true)
        val target = identity().peerID

        assertEquals(
            "Classic XX message 1 is a bare ephemeral",
            32,
            classic.initiateHandshake(target)!!.size
        )
        assertEquals(
            "Hybrid XXhfs message 1 appends the ML-KEM-768 encapsulation key",
            32 + 1184,
            pq.initiateHandshake(target)!!.size
        )
    }

    private fun completeHandshake(
        initiator: NoiseSessionManager,
        initiatorPeerID: String,
        responder: NoiseSessionManager,
        responderPeerID: String
    ) {
        val message1 = initiator.initiateHandshake(responderPeerID)!!
        val message2 = responder.processHandshakeMessage(initiatorPeerID, message1)!!
        val message3 = initiator.processHandshakeMessage(responderPeerID, message2)!!
        assertNull(responder.processHandshakeMessage(initiatorPeerID, message3))
    }

    private fun manager(
        identity: TestIdentity,
        postQuantum: Boolean,
        blockClassic: Boolean = false
    ): NoiseSessionManager =
        NoiseSessionManager(
            localStaticPrivateKey = identity.privateKey,
            localStaticPublicKey = identity.publicKey,
            localPeerID = identity.peerID,
            postQuantumProvider = { postQuantum },
            blockClassicProvider = { blockClassic }
        ).also { managers += it }

    private fun identity(): TestIdentity {
        val dh = Noise.createDH("25519")
        return try {
            dh.generateKeyPair()
            val privateKey = ByteArray(32)
            val publicKey = ByteArray(32)
            dh.getPrivateKey(privateKey, 0)
            dh.getPublicKey(publicKey, 0)
            TestIdentity(privateKey, publicKey, NoisePeerIdentity.derivePeerID(publicKey)!!)
        } finally {
            dh.destroy()
        }
    }
}
