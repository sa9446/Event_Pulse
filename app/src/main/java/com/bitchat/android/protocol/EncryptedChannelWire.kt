package com.bitchat.android.protocol

import java.util.Base64

/**
 * Wire format for encrypted (password-protected) channel messages.
 *
 * A message sent to a password-protected channel is transmitted as a normal MESSAGE payload whose
 * UTF-8 text is: `DAENC:<channel>:<base64(iv + AES-GCM ciphertext)>`.
 *
 * - The channel name travels inside the marker so the receiver selects the right channel key.
 * - Receivers that hold the channel key decrypt and display the plaintext.
 * - Receivers without the key display a locked placeholder instead of leaking ciphertext.
 *
 * Uses `java.util.Base64` (available on Android 8+ / API 26+, the app's minSdk) so this class stays
 * pure-JVM testable.
 */
object EncryptedChannelWire {

    /** Recognizable prefix that cannot collide with normal chat text. */
    const val MARKER = "DAENC:"

    /** Placeholder rendered when the local device does not hold the channel key. */
    const val PLACEHOLDER = "\uD83D\uDD12 Encrypted message"

    /**
     * Build the wire string for an encrypted channel message.
     * @param channel The channel name (e.g. "#room").
     * @param iv The AES-GCM IV (12 bytes).
     * @param ciphertext The AES-GCM ciphertext (includes the 128-bit auth tag).
     */
    fun encode(channel: String, iv: ByteArray, ciphertext: ByteArray): String {
        val combined = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
        return MARKER + channel + ":" +
            Base64.getEncoder().encodeToString(combined)
    }

    /**
     * Parse a wire payload into `(channel, iv + ciphertext)`, or null when the payload is not a
     * valid encrypted channel message.
     */
    fun parse(payload: String): Pair<String, ByteArray>? {
        if (!payload.startsWith(MARKER)) return null
        val rest = payload.removePrefix(MARKER)
        val colon = rest.indexOf(':')
        if (colon <= 0) return null
        val channel = rest.substring(0, colon)
        val b64 = rest.substring(colon + 1)
        val bytes = try {
            Base64.getDecoder().decode(b64)
        } catch (_: Exception) {
            return null
        }
        // 12-byte IV + minimum AES-GCM ciphertext + 16-byte tag
        if (bytes.size < 28) return null
        return channel to bytes
    }
}
