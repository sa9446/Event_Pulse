package com.eventpulse.mesh

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.nio.charset.StandardCharsets

/**
 * Feature B+E: Extended Structured Packet Payload
 *
 * Supports text, media chunks, retraction signals, and SOS alerts.
 * Max 512 bytes when serialized to UTF-8 JSON.
 *
 * PERF: Uses a shared singleton Gson instance (avoids repeated GsonBuilder allocation).
 * PERF: isEventPulsePayload uses fast string prefix check (max 512 bytes, allocation is minimal).
 *
 * Packet types:
 *   - "TEXT"         Standard chat message
 *   - "MEDIA_CHUNK"  Fragment of a media file (chunk_index / total_chunks)
 *   - "RETRACT"      Withdraw a previously sent message by msg_id
 *   - "SOS"          Emergency broadcast (bypasses filters)
 */
data class EventPulsePayload(
    val msg_id: String = "",
    val sender: String = "",
    val channel: String = "general",
    val type: String = "TEXT",
    val target_msg_id: String = "",
    val file_id: String = "",
    val chunk_index: Int = 0,
    val total_chunks: Int = 1,
    val mime_type: String = "",
    val timestamp: Long = System.currentTimeMillis() / 1000L,
    val body: String = "",
    val data: String = ""
) {
    companion object {
        private const val TAG = "EventPulsePayload"
        private const val MAX_PAYLOAD_BYTES = 512

        /** Shared Gson singleton — created once, reused for all serialization. */
        @Volatile
        private var _gson: Gson? = null
        private val gson: Gson get() {
            val instance = _gson
            if (instance != null) return instance
            return synchronized(this) {
                _gson?.let { return it }
                GsonBuilder().create().also { _gson = it }
            }
        }

        /** Counter-based uniqueness within same millisecond for msg_id. */
        private val msgIdLock = Any()
        private var lastMsgIdTs = 0L
        private var msgIdCounter = 0

        fun generateMessageId(senderId: String): String {
            val ts = System.currentTimeMillis() / 1000L
            val hash = senderId.take(8).ifEmpty { "anon" }
            synchronized(msgIdLock) {
                if (ts == lastMsgIdTs) {
                    msgIdCounter++
                } else {
                    lastMsgIdTs = ts
                    msgIdCounter = 0
                }
                return "usr_${hash}_${ts}_${msgIdCounter}"
            }
        }

        fun toByteArray(payload: EventPulsePayload): ByteArray? {
            val json = gson.toJson(payload)
            val bytes = json.toByteArray(StandardCharsets.UTF_8)
            if (bytes.size <= MAX_PAYLOAD_BYTES) return bytes
            // Truncate body if overflow
            val overflow = bytes.size - MAX_PAYLOAD_BYTES
            val truncatedBody = payload.body.take((payload.body.length - overflow - 3).coerceAtLeast(0)) + "..."
            val retryPayload = payload.copy(body = truncatedBody, data = "")
            val retryBytes = gson.toJson(retryPayload).toByteArray(StandardCharsets.UTF_8)
            return if (retryBytes.size > MAX_PAYLOAD_BYTES) null else retryBytes
        }

        fun fromByteArray(data: ByteArray): EventPulsePayload? {
            return try {
                gson.fromJson(String(data, StandardCharsets.UTF_8), EventPulsePayload::class.java)
            } catch (e: Exception) { null }
        }

        /**
         * Fast check: payload starts with "{" and contains "msg_id".
         * Max 512 bytes so string allocation is negligible.
         */
        fun isEventPulsePayload(data: ByteArray): Boolean {
            if (data.isEmpty()) return false
            if ((data[0].toInt() and 0xFF) != '{'.code) return false
            return try {
                String(data, StandardCharsets.UTF_8).contains("\"msg_id\"")
            } catch (e: Exception) { false }
        }
    }
}
