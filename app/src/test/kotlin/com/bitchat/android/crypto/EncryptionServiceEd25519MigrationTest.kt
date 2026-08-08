package com.bitchat.android.crypto

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.identity.IdentityKeystoreCipher
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Verifies the EncryptionService Ed25519 key migration: a pre-upgrade plain-Base64 private key in
 * the secure prefs is re-wrapped as a Keystore envelope on first load, and the identity (public
 * key / signature) is unchanged across the migration.
 *
 * The real Android Keystore provider is unavailable under Robolectric, so the cipher is injected
 * via [EncryptionService.identityCipherFactory] to exercise the envelope path deterministically.
 */
@RunWith(RobolectricTestRunner::class)
class EncryptionServiceEd25519MigrationTest {

    /** AES-GCM fake matching the real envelope format (version 2 || IV || ciphertext). */
    private class FakeCipher : IdentityKeystoreCipher {
        private val key = SecretKeySpec(ByteArray(32) { 9 }, "AES")
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(associatedData)
            val ciphertext = cipher.doFinal(plaintext)
            return byteArrayOf(2) + cipher.iv + ciphertext
        }
        override fun decrypt(envelope: ByteArray, associatedData: ByteArray): ByteArray {
            val iv = envelope.copyOfRange(1, 13)
            val ciphertext = envelope.copyOfRange(13, envelope.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.updateAAD(associatedData)
            return cipher.doFinal(ciphertext)
        }
        override fun destroyKey() {}
    }

    @Before
    fun setUp() {
        EncryptionService.identityCipherFactory = { FakeCipher() }
    }

    @After
    fun tearDown() {
        EncryptionService.identityCipherFactory = null
    }

    @Test
    fun `legacy ed25519 key migrates to a keystore envelope on first load`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // Generate a legacy-style key pair exactly as the app used to.
        val keyGen = Ed25519KeyPairGenerator()
        keyGen.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val keyPair = keyGen.generateKeyPair()
        val privateSeed = (keyPair.private as Ed25519PrivateKeyParameters).encoded

        // Pre-seed the secure prefs with the legacy plain-Base64 value.
        val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val prefs = EncryptedSharedPreferences.create(
            context,
            "bitchat_crypto_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        prefs.edit()
            .putString("ed25519_signing_private_key", Base64.getEncoder().encodeToString(privateSeed))
            .commit()

        val service = EncryptionService(context)

        // The migrated key must still sign and verify (identity is stable across the migration).
        val message = "migration check".toByteArray()
        val signature = service.signData(message)
        assertNotNull(signature)
        assertTrue(
            service.verifyEd25519Signature(
                signature!!,
                message,
                service.getSigningPublicKey()!!
            )
        )

        // And it must now be stored as a Keystore envelope, not plain Base64. Re-open the prefs
        // fresh: EncryptedSharedPreferences caches decrypted values per instance, and the instance
        // created before the service ran still holds the pre-migration value.
        val freshPrefs = EncryptedSharedPreferences.create(
            context,
            "bitchat_crypto_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        val stored = freshPrefs.getString("ed25519_signing_private_key", null)
        assertNotNull(stored)
        assertTrue(
            "Legacy Ed25519 key must be re-wrapped as a Keystore envelope",
            stored!!.startsWith(IdentityKeystoreCipher.ENVELOPE_PREFIX)
        )
    }
}
