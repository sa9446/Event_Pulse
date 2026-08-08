package com.bitchat.android.identity

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts raw private key material (the Noise X25519 static key and the Ed25519 signing seed)
 * with an AES-256-GCM key that never leaves Android Keystore.
 *
 * The Noise stack requires raw 32-byte X25519/Ed25519 seeds (and ML-KEM has no Keystore support
 * at all), so the keys cannot live *in* Keystore directly. Wrapping them in a Keystore-encrypted
 * envelope is the standard design for protocol keys: at rest they are ciphertext only, and the
 * wrapping key is non-exportable (StrongBox-backed where the device has a secure element), so a
 * rooted device cannot decrypt the stored keys offline.
 *
 * Every envelope is bound to its key slot through AES-GCM associated data, so a ciphertext copied
 * from one slot cannot be accepted in another. The key is intentionally NOT cached in the JVM —
 * each operation re-fetches it from Keystore so a panic [destroyKey] on one instance invalidates
 * the wrapping key for every holder process-wide.
 */
internal interface IdentityKeystoreCipher {
    companion object {
        /** Prefix marking a stored value as a Keystore-wrapped envelope (vs legacy plain Base64). */
        const val ENVELOPE_PREFIX = "v2:"
    }

    fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray
    fun decrypt(envelope: ByteArray, associatedData: ByteArray): ByteArray
    fun destroyKey()
}

internal class AndroidIdentityKeystoreCipher(
    private val keyAlias: String = DEFAULT_KEY_ALIAS
) : IdentityKeystoreCipher {

    companion object {
        internal const val DEFAULT_KEY_ALIAS = "bitchat_identity_keys_v1"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val ENVELOPE_VERSION: Byte = 2
        private const val GCM_TAG_BITS = 128
        private const val IV_BYTES = 12
        private const val TAG = "IdentityKeystoreCipher"
    }

    private val keyLock = Any()

    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(associatedData)
        val ciphertext = cipher.doFinal(plaintext)
        check(cipher.iv.size == IV_BYTES) { "Unexpected AES-GCM IV length" }
        return byteArrayOf(ENVELOPE_VERSION) + cipher.iv + ciphertext
    }

    override fun decrypt(envelope: ByteArray, associatedData: ByteArray): ByteArray {
        require(envelope.size > 1 + IV_BYTES) { "Identity envelope is truncated" }
        require(envelope[0] == ENVELOPE_VERSION) { "Unsupported identity envelope version" }
        val iv = envelope.copyOfRange(1, 1 + IV_BYTES)
        val ciphertext = envelope.copyOfRange(1 + IV_BYTES, envelope.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(associatedData)
        return cipher.doFinal(ciphertext)
    }

    override fun destroyKey() {
        synchronized(keyLock) {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            if (keyStore.containsAlias(keyAlias)) {
                keyStore.deleteEntry(keyAlias)
            }
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        synchronized(keyLock) {
            val reloaded = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            (reloaded.getKey(keyAlias, null) as? SecretKey)?.let { return it }
            // Prefer a StrongBox secure element when present; fall back to the regular (still
            // non-exportable) Keystore otherwise — including devices and test runtimes where
            // StrongBox is unavailable.
            return try {
                generateKey(strongBox = true)
            } catch (e: Exception) {
                Log.w(TAG, "StrongBox unavailable (${e.message}); using standard Keystore for identity keys")
                generateKey(strongBox = false)
            }
        }
    }

    private fun generateKey(strongBox: Boolean): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .apply { if (strongBox) setIsStrongBoxBacked(true) }
                .build()
        )
        return generator.generateKey()
    }
}
