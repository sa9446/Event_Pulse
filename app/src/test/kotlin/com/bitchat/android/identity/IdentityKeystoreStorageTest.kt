package com.bitchat.android.identity

import android.content.Context
import java.util.Base64
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Verifies that raw Noise/Ed25519 private keys are stored as Keystore-wrapped envelopes
 * (never plain Base64 when a cipher is present), that legacy plain-Base64 values migrate
 * in place on first load, and that the no-cipher (test) path stays backward compatible.
 */
@RunWith(RobolectricTestRunner::class)
class IdentityKeystoreStorageTest {

    /**
     * Deterministic AES-GCM fake matching the real envelope format (version 2 || IV || ct).
     * [destroyKey] genuinely invalidates the wrapping key so the panic-erasure path is tested
     * honestly rather than by swapping in a different fake.
     */
    private class FakeCipher : IdentityKeystoreCipher {
        private val key = SecretKeySpec(ByteArray(32) { 7 }, "AES")
        @Volatile
        private var destroyed = false
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
            check(!destroyed) { "wrapping key erased" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(associatedData)
            val ciphertext = cipher.doFinal(plaintext)
            return byteArrayOf(2) + cipher.iv + ciphertext
        }
        override fun decrypt(envelope: ByteArray, associatedData: ByteArray): ByteArray {
            check(!destroyed) { "wrapping key erased" }
            val iv = envelope.copyOfRange(1, 13)
            val ciphertext = envelope.copyOfRange(13, envelope.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.updateAAD(associatedData)
            return cipher.doFinal(ciphertext)
        }
        override fun destroyKey() {
            destroyed = true
        }
    }

    private lateinit var prefs: android.content.SharedPreferences

    @Before
    fun setup() {
        prefs = RuntimeEnvironment.getApplication().getSharedPreferences(
            "identity-keystore-${UUID.randomUUID()}",
            Context.MODE_PRIVATE
        )
    }

    @After
    fun tearDown() {
        prefs.edit().clear().commit()
    }

    @Test
    fun `private keys are saved as keystore envelopes`() {
        val manager = SecureIdentityStateManager(prefs, testOnly = true, keyCipher = FakeCipher())
        val privateKey = ByteArray(32) { 0x42 }
        val publicKey = ByteArray(32) { 0x24 }

        manager.saveStaticKey(privateKey, publicKey)

        val stored = prefs.getString("static_private_key", null)
        assertNotNull(stored)
        assertTrue(
            "Stored private key must be a Keystore envelope, not plain Base64",
            stored!!.startsWith(IdentityKeystoreCipher.ENVELOPE_PREFIX)
        )

        val loaded = manager.loadStaticKey()
        assertArrayEquals(privateKey, loaded?.first)
        assertArrayEquals(publicKey, loaded?.second)
    }

    @Test
    fun `legacy plain base64 private keys migrate to envelopes on load`() {
        val manager = SecureIdentityStateManager(prefs, testOnly = true, keyCipher = FakeCipher())
        val privateKey = ByteArray(32) { 0x11 }
        val publicKey = ByteArray(32) { 0x22 }
        // Simulate a pre-upgrade install: raw Base64 (as the app used to store).
        prefs.edit()
            .putString("static_private_key", Base64.getEncoder().encodeToString(privateKey))
            .putString("static_public_key", Base64.getEncoder().encodeToString(publicKey))
            .commit()

        val loaded = manager.loadStaticKey()

        assertArrayEquals(privateKey, loaded?.first)
        val migrated = prefs.getString("static_private_key", null)
        assertTrue(
            "Legacy key must be re-wrapped as a Keystore envelope",
            migrated!!.startsWith(IdentityKeystoreCipher.ENVELOPE_PREFIX)
        )
        // Identity is stable across the migration.
        val afterMigration = SecureIdentityStateManager(prefs, testOnly = true, keyCipher = FakeCipher())
        assertArrayEquals(privateKey, afterMigration.loadStaticKey()?.first)
    }

    @Test
    fun `tampered envelopes fail closed and regenerate identity`() {
        val manager = SecureIdentityStateManager(prefs, testOnly = true, keyCipher = FakeCipher())
        val privateKey = ByteArray(32) { 0x33 }
        manager.saveStaticKey(privateKey, ByteArray(32) { 0x44 })

        // Flip a byte inside the ciphertext (not the version/IV).
        val stored = prefs.getString("static_private_key", null)!!
        val envelope = Base64.getDecoder()
            .decode(stored.removePrefix(IdentityKeystoreCipher.ENVELOPE_PREFIX))
        envelope[envelope.size - 1] = (envelope.last().toInt() xor 0xFF).toByte()
        prefs.edit()
            .putString(
                "static_private_key",
                IdentityKeystoreCipher.ENVELOPE_PREFIX +
                    Base64.getEncoder().encodeToString(envelope)
            )
            .commit()

        // A corrupt private key must not silently produce a wrong key — it returns null so the
        // caller regenerates a fresh identity rather than continue on a broken one.
        assertNull(manager.loadStaticKey())
    }

    @Test
    fun `envelopes are bound to their key slot via associated data`() {
        val manager = SecureIdentityStateManager(prefs, testOnly = true, keyCipher = FakeCipher())
        val staticPrivate = ByteArray(32) { 0x51 }
        manager.saveStaticKey(staticPrivate, ByteArray(32) { 0x52 })

        // The static-slot envelope must not decrypt as the signing slot.
        val stored = prefs.getString("static_private_key", null)!!
        prefs.edit().putString("signing_private_key", stored).commit()
        assertNull(manager.loadSigningKey())
    }

    @Test
    fun `no cipher keeps legacy base64 round trip working`() {
        val manager = SecureIdentityStateManager(prefs, testOnly = true)
        val privateKey = ByteArray(32) { 0x61 }
        val publicKey = ByteArray(32) { 0x62 }

        manager.saveSigningKey(privateKey, publicKey)

        val stored = prefs.getString("signing_private_key", null)
        assertFalse(stored!!.startsWith(IdentityKeystoreCipher.ENVELOPE_PREFIX))
        assertArrayEquals(privateKey, manager.loadSigningKey()?.first)
    }

    @Test
    fun `destroy key makes envelopes undecryptable`() {
        val cipher = FakeCipher()
        val manager = SecureIdentityStateManager(prefs, testOnly = true, keyCipher = cipher)
        manager.saveStaticKey(ByteArray(32) { 0x71 }, ByteArray(32) { 0x72 })
        assertNotNull(manager.loadStaticKey())

        // Panic path: erasing the wrapping key must make the stored envelope unreadable forever.
        cipher.destroyKey()
        val afterPanic = SecureIdentityStateManager(prefs, testOnly = true, keyCipher = cipher)
        assertNull(afterPanic.loadStaticKey())
    }
}
