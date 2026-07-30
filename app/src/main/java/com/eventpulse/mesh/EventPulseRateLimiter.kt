package com.eventpulse.mesh

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Feature C: Spam Prevention & Payload Bounds Checking
 *
 * - Caps outbound message length at 280 characters to prevent packet buffer overflows.
 * - Implements a basic local rate-limiting queue (maximum 1 message per 2 seconds per sender ID)
 *   to ignore duplicate or high-frequency flood packets before relaying them across the mesh.
 *
 * Thread-safe: uses ConcurrentHashMap for all mutable state.
 */
object EventPulseRateLimiter {

    private const val TAG = "EventPulseRateLimiter"

    /** Maximum allowed message body length in characters. */
    const val MAX_BODY_LENGTH = 280

    /** Minimum interval between messages from the same sender (milliseconds). */
    private const val MIN_INTERVAL_MS = 2000L

    /** Maximum entries before triggering automatic flush (prevent OOM on large mesh). */
    private const val MAX_CACHE_ENTRIES = 500

    /** Tracks last message timestamp per sender ID (peerID or nickname). */
    private val senderTimestamps = ConcurrentHashMap<String, Long>()

    /** Tracks last message content per sender ID for duplicate detection. */
    private val lastMessageContent = ConcurrentHashMap<String, String>()

    /**
     * Validate and truncate an outbound message body.
     * Returns the validated body string (truncated to 280 chars if needed).
     */
    fun validateOutboundMessage(body: String): String {
        return if (body.length > MAX_BODY_LENGTH) {
            val truncated = body.take(MAX_BODY_LENGTH)
            Log.d(TAG, "Truncated outbound message from ${body.length} to $MAX_BODY_LENGTH chars")
            truncated
        } else {
            body
        }
    }

    /**
     * Check if an incoming message from a given sender should be accepted or rate-limited.
     *
     * @param senderId The unique identifier of the sender (peerID).
     * @param content The message body content.
     * @return true if the message should be accepted/relayed, false if it should be dropped.
     */
    fun shouldAcceptIncoming(senderId: String, content: String): Boolean {
        val now = System.currentTimeMillis()

        // Auto-flush when cache gets large (prevent memory leak)
        if (senderTimestamps.size >= MAX_CACHE_ENTRIES) {
            flushStaleEntries(now)
        }

        // Check for duplicate content
        val lastContent = lastMessageContent[senderId]
        if (lastContent != null && lastContent == content) {
            Log.v(TAG, "Dropping duplicate message from $senderId")
            return false // Ignore duplicate messages
        }

        // Check rate limit
        val lastTimestamp = senderTimestamps[senderId]
        if (lastTimestamp != null && (now - lastTimestamp) < MIN_INTERVAL_MS) {
            Log.v(TAG, "Rate-limiting message from $senderId (${now - lastTimestamp}ms since last)")
            return false // Too frequent
        }

        // Update tracking
        senderTimestamps[senderId] = now
        lastMessageContent[senderId] = content
        return true
    }

    /**
     * Remove entries older than 60 seconds to prevent memory leaks on large mesh networks.
     * Called automatically when cache exceeds MAX_CACHE_ENTRIES.
     */
    private fun flushStaleEntries(now: Long) {
        val cutoff = now - 60_000L // 60 seconds
        val staleSenders = senderTimestamps.filter { it.value < cutoff }.keys
        staleSenders.forEach { senderId ->
            senderTimestamps.remove(senderId)
            lastMessageContent.remove(senderId)
        }
        Log.d(TAG, "Flushed ${staleSenders.size} stale rate-limit entries, ${senderTimestamps.size} remaining")
    }

    /**
     * Clear all rate-limiting state (e.g., on panic clear or identity reset).
     */
    fun clear() {
        senderTimestamps.clear()
        lastMessageContent.clear()
        Log.d(TAG, "Rate limiter state cleared")
    }
}
