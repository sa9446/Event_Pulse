package com.eventpulse.mesh

import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Feature A: Offline Message & Media Retraction Engine ("Mesh Recall")
 *
 * Manages the lifecycle of message retraction across the mesh:
 * - Tracks sent message IDs for the local user
 * - Verifies incoming RETRACT packets (sender must match original author)
 * - Deletes associated media files from local storage
 * - Cascades RETRACT packets to nearby BLE peers
 * - Provides a "retracted" badge for UI display
 */
object EventPulseRetractionManager {

    private const val TAG = "RetractionManager"

    /** Tracks sent message IDs -> the associated file path (if any) for later deletion. */
    private val sentMessageFiles = ConcurrentHashMap<String, String>()

    /** Tracks received message IDs that have been retracted (to avoid re-displaying). */
    private val retractedMessageIds = ConcurrentHashMap<String, RetractionRecord>()

    /** Maps sender peer IDs to their known author IDs for verification. */
    private val authorPeerMap = ConcurrentHashMap<String, String>()

    data class RetractionRecord(
        val msgId: String,
        val authorId: String,
        val retractedAt: Long = System.currentTimeMillis()
    )

    /** Callback when a local message should be removed from the UI. */
    var onLocalMessageRetracted: ((String) -> Unit)? = null

    /** Callback when a remote retraction is received and verified. */
    var onRemoteRetractionVerified: ((String, String) -> Unit)? = null

    // ─── Local Message Tracking ────────────────────────────────────────

    /**
     * Register a message that was sent by the local user.
     * @param msgId The unique message ID.
     * @param filePath Optional file path for media messages (for deletion on retract).
     */
    fun registerSentMessage(msgId: String, filePath: String? = null) {
        sentMessageFiles[msgId] = filePath ?: ""
    }

    /**
     * Register a peer's author ID for retraction verification.
     */
    fun registerAuthorPeer(peerId: String, authorId: String) {
        authorPeerMap[peerId] = authorId
    }

    // ─── Retraction Verification ───────────────────────────────────────

    /**
     * Verify an incoming RETRACT packet.
     * @param targetMsgId The msg_id being retracted.
     * @param retractSenderPeerID The peer ID that sent the RETRACT.
     * @param originalSenderPeerID The claimed original sender of the message.
     * @return true if the retraction is valid and should be processed.
     */
    fun verifyRetraction(
        targetMsgId: String,
        retractSenderPeerID: String,
        originalSenderPeerID: String?
    ): Boolean {
        // The retracting peer must match the original sender
        if (originalSenderPeerID == null) return false
        return retractSenderPeerID == originalSenderPeerID
    }

    /**
     * Process a verified RETRACT.
     * - Marks the message as retracted
     * - Deletes associated media files
     * - Returns true if the message was found and retracted
     */
    fun processRetraction(msgId: String, authorId: String): Boolean {
        if (retractedMessageIds.containsKey(msgId)) return false // Already retracted

        retractedMessageIds[msgId] = RetractionRecord(
            msgId = msgId,
            authorId = authorId
        )

        // Delete associated media file if any
        val filePath = sentMessageFiles[msgId]
        if (!filePath.isNullOrEmpty()) {
            try {
                val file = File(filePath)
                if (file.exists()) {
                    file.delete()
                    Log.d(TAG, "Deleted media file for retracted message $msgId")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete media file for $msgId: ${e.message}")
            }
        }

        Log.d(TAG, "Message $msgId retracted by $authorId")
        return true
    }

    /**
     * Check if a message has been retracted.
     */
    fun isMessageRetracted(msgId: String): Boolean {
        return retractedMessageIds.containsKey(msgId)
    }

    /**
     * Get the retraction record for a message (for UI badge display).
     */
    fun getRetractionRecord(msgId: String): RetractionRecord? {
        return retractedMessageIds[msgId]
    }

    /**
     * Build a RETRACT payload for broadcasting a retraction request.
     */
    fun buildRetractPayload(
        senderName: String,
        targetMsgId: String,
        channel: String = "general"
    ): EventPulsePayload {
        return EventPulsePayload(
            msg_id = EventPulsePayload.generateMessageId(senderName),
            sender = senderName,
            channel = channel,
            type = "RETRACT",
            target_msg_id = targetMsgId,
            timestamp = System.currentTimeMillis() / 1000L
        )
    }

    /**
     * Update a message's file path post-send (for media messages).
     */
    fun updateMessageFilePath(msgId: String, filePath: String) {
        sentMessageFiles[msgId] = filePath
    }

    /** Clear all retraction state (e.g., on panic). */
    fun clear() {
        sentMessageFiles.clear()
        retractedMessageIds.clear()
        authorPeerMap.clear()
    }
}
