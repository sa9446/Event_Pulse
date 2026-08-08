package com.bitchat.android.ui

import android.util.Log
import com.bitchat.android.features.voice.VoiceRecorder
import com.bitchat.android.features.voice.VoiceTranscoder
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.model.BitchatFilePacket
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.mesh.PrivateMediaPreparation
import java.util.Date
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LegacyPrivateMediaConsentRequest(
    val requestId: String,
    val recipientNickname: String,
    val fileName: String,
    val warning: String
)

/**
 * Handles media file sending operations (voice notes, images, generic files)
 * Separated from ChatViewModel for better separation of concerns
 */
class MediaSendingManager(
    private val state: ChatState,
    private val messageManager: MessageManager,
    private val channelManager: ChannelManager,
    private val scope: CoroutineScope,
    private val mediaWorkDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val getMeshService: () -> MeshService
) {
    // Helper to get current mesh service (may change after panic clear)
    private val meshService: MeshService
        get() = getMeshService()
    companion object {
        private const val TAG = "MediaSendingManager"
        private const val MAX_FILE_SIZE = com.bitchat.android.util.AppConstants.Media.MAX_FILE_SIZE_BYTES
        private const val PENDING_PRIVATE_MEDIA_TIMEOUT_MS = 15_000L
        // Route-aware retry: when the transport chosen for the first hop rejects the send, keep
        // the first-send intent and re-run preparation a bounded number of times. The unified
        // mesh falls back to an alternate transport on each retry (see prepareFilePrivate).
        private const val MAX_PRIVATE_MEDIA_RETRIES = 2
        private const val PRIVATE_MEDIA_RETRY_DELAY_MS = 2_000L
    }

    // Track in-flight transfer progress: transferId -> messageId and reverse
    private val transferMessageMap = mutableMapOf<String, String>()
    private val messageTransferMap = mutableMapOf<String, String>()
    private val pendingConsentLock = Any()
    private val _legacyPrivateMediaConsent = MutableStateFlow<LegacyPrivateMediaConsentRequest?>(null)
    val legacyPrivateMediaConsent: StateFlow<LegacyPrivateMediaConsentRequest?> =
        _legacyPrivateMediaConsent.asStateFlow()

    private data class PendingPrivateMedia(
        val request: LegacyPrivateMediaConsentRequest,
        val conversationID: String,
        val recipientMeshPeerID: String,
        val filePacket: BitchatFilePacket,
        val filePath: String,
        val messageType: BitchatMessageType,
        val transferId: String,
        val existingMessageId: String? = null
    )

    private var pendingPrivateMedia: PendingPrivateMedia? = null

    private data class RetrySendContext(
        val message: BitchatMessage,
        val filePath: String,
        val peerIDOrNull: String?,
        val channelOrNull: String?
    )

    private data class PendingAutomaticPrivateMedia(
        val requestId: String,
        val conversationID: String,
        val recipientMeshPeerID: String,
        val filePacket: BitchatFilePacket,
        val filePath: String,
        val messageType: BitchatMessageType,
        val transferId: String,
        val allowLegacyFallback: Boolean,
        // When set, the transfer updates this existing message in place (one-tap retry of a
        // failed media bubble) instead of creating a new local echo.
        val existingMessageId: String? = null
    )

    private var pendingAutomaticPrivateMedia: PendingAutomaticPrivateMedia? = null
    private var evaluatingAutomaticRequestId: String? = null
    private var automaticRetryRequestedFor: String? = null
    private var pendingAutomaticTimeoutRequestId: String? = null
    // requestId -> rejected-preparation attempt counter (for the bounded route-aware retry)
    private val rejectedRetryAttempts = mutableMapOf<String, Int>()
    // requestId -> active timeout job (re-armed on every retry so the window slides)
    private val pendingTimeoutJobs = mutableMapOf<String, Job>()

    /**
     * Enforce the send-size cap with a user-visible failure posted to the
     * conversation the user is sending from. Returns true if the file is
     * oversized and the send was aborted.
     */
    private fun rejectIfOversized(
        file: java.io.File,
        toPeerIDOrNull: String?,
        channelOrNull: String?
    ): Boolean {
        val size = file.length()
        if (size <= MAX_FILE_SIZE) return false
        Log.e(TAG, "❌ File too large: $size bytes (max: $MAX_FILE_SIZE)")
        val sizeMb = String.format("%.1f", size / (1024.0 * 1024.0))
        val maxMb = String.format("%.1f", MAX_FILE_SIZE / (1024.0 * 1024.0))
        val text = "cannot send ${file.name}: file is too large (${sizeMb} MB, max $maxMb MB)"
        when {
            toPeerIDOrNull != null -> {
                val sys = BitchatMessage(
                    sender = "system",
                    content = text,
                    timestamp = Date(),
                    isRelay = false
                )
                messageManager.addPrivateMessageNoUnread(toPeerIDOrNull, sys)
            }
            channelOrNull != null -> {
                val sys = BitchatMessage(
                    sender = "system",
                    content = text,
                    timestamp = Date(),
                    isRelay = false
                )
                messageManager.addChannelMessage(channelOrNull, sys)
            }
            else -> messageManager.addSystemMessage(text)
        }
        return true
    }

    /**
     * Send a voice note (audio file)
     */
    fun sendVoiceNote(toPeerIDOrNull: String?, channelOrNull: String?, filePath: String) {
        scope.launch {
            sendVoiceNoteAsync(toPeerIDOrNull, channelOrNull, filePath)
        }
    }

    private suspend fun sendVoiceNoteAsync(
        toPeerIDOrNull: String?,
        channelOrNull: String?,
        filePath: String,
        existingMessageId: String? = null
    ) {
        try {
            val filePacket = withContext(mediaWorkDispatcher) {
                val file = java.io.File(filePath)
                if (!file.exists()) {
                    Log.e(TAG, "Voice note file does not exist")
                    return@withContext null
                }

                if (rejectIfOversized(file, toPeerIDOrNull, channelOrNull)) {
                    return@withContext null
                }

                // BLE-only private chats ride the slow radio, so the voice note is re-encoded to
                // the low-bitrate tier (20 kbps -> 12 kbps, ~40% smaller payload) before sending.
                // The original path is kept for the message and retries — a retry re-derives the
                // cached transcode. Channels/public broadcasts reach every transport and stay at
                // the standard tier. Transcode failures fall back to the original file.
                val bleOnly = toPeerIDOrNull != null && PrivateMediaRecipientResolver
                    .resolve(toPeerIDOrNull, meshService)
                    ?.meshPeerID
                    ?.let { meshService.isPeerBleOnly(it) } == true
                val sendPath = if (bleOnly) {
                    VoiceTranscoder.transcodeToLowerBitrate(filePath, VoiceRecorder.BLE_ONLY_VOICE_BITRATE)
                        ?: filePath
                } else {
                    filePath
                }
                val sendFile = java.io.File(sendPath)

                BitchatFilePacket(
                    fileName = file.name,
                    fileSize = sendFile.length(),
                    mimeType = "audio/mp4",
                    content = sendFile.readBytes()
                )
            } ?: return

            if (toPeerIDOrNull != null) {
                sendPrivateFile(toPeerIDOrNull, filePacket, filePath, BitchatMessageType.Audio, existingMessageId)
            } else {
                sendPublicFile(channelOrNull, filePacket, filePath, BitchatMessageType.Audio, existingMessageId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send voice note: ${e.message}")
        }
    }

    /**
     * Post a user-visible "cannot send video" notice to the conversation being sent from.
     */
    private fun rejectVideoSend(
        fileName: String,
        toPeerIDOrNull: String?,
        channelOrNull: String?
    ) {
        Log.e(TAG, "Video sending is not supported: $fileName")
        val text = "cannot send $fileName: video files are not supported over the mesh"
        when {
            toPeerIDOrNull != null -> {
                val sys = BitchatMessage(
                    sender = "system",
                    content = text,
                    timestamp = Date(),
                    isRelay = false,
                    isPrivate = true,
                    senderPeerID = toPeerIDOrNull
                )
                messageManager.addPrivateMessageNoUnread(toPeerIDOrNull, sys)
            }
            channelOrNull != null -> {
                val sys = BitchatMessage(
                    sender = "system",
                    content = text,
                    timestamp = Date(),
                    isRelay = false
                )
                messageManager.addChannelMessage(channelOrNull, sys)
            }
            else -> messageManager.addSystemMessage(text)
        }
    }

    /**
     * Send an image file
     */
    fun sendImageNote(toPeerIDOrNull: String?, channelOrNull: String?, filePath: String) {
        scope.launch {
            sendImageNoteAsync(toPeerIDOrNull, channelOrNull, filePath)
        }
    }

    private suspend fun sendImageNoteAsync(
        toPeerIDOrNull: String?,
        channelOrNull: String?,
        filePath: String,
        existingMessageId: String? = null
    ) {
        try {
            val filePacket = withContext(mediaWorkDispatcher) {
                val file = java.io.File(filePath)
                if (!file.exists()) {
                    Log.e(TAG, "Image file does not exist")
                    return@withContext null
                }

                if (rejectIfOversized(file, toPeerIDOrNull, channelOrNull)) {
                    return@withContext null
                }

                // Derive the real MIME type from the actual file so receivers save and label
                // the image correctly (downscaled captures are JPEG, gallery picks may not be).
                val mimeType = try {
                    com.bitchat.android.features.file.FileUtils.getMimeTypeFromExtension(file.name)
                } catch (_: Exception) {
                    "image/jpeg"
                }

                BitchatFilePacket(
                    fileName = file.name,
                    fileSize = file.length(),
                    mimeType = mimeType,
                    content = file.readBytes()
                )
            } ?: return

            if (toPeerIDOrNull != null) {
                sendPrivateFile(toPeerIDOrNull, filePacket, filePath, BitchatMessageType.Image, existingMessageId)
            } else {
                sendPublicFile(channelOrNull, filePacket, filePath, BitchatMessageType.Image, existingMessageId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Image send failed: ${e.message}", e)
        }
    }

    /**
     * Send a generic file
     */
    fun sendFileNote(toPeerIDOrNull: String?, channelOrNull: String?, filePath: String) {
        scope.launch {
            sendFileNoteAsync(toPeerIDOrNull, channelOrNull, filePath)
        }
    }

    private suspend fun sendFileNoteAsync(
        toPeerIDOrNull: String?,
        channelOrNull: String?,
        filePath: String,
        existingMessageId: String? = null
    ) {
        try {
            val filePacket = withContext(mediaWorkDispatcher) {
                val file = java.io.File(filePath)
                if (!file.exists()) {
                    Log.e(TAG, "File does not exist")
                    return@withContext null
                }

                if (rejectIfOversized(file, toPeerIDOrNull, channelOrNull)) {
                    return@withContext null
                }

                // Use the real MIME type based on extension; fallback to octet-stream
                val mimeType = try {
                    com.bitchat.android.features.file.FileUtils.getMimeTypeFromExtension(file.name)
                } catch (_: Exception) {
                    "application/octet-stream"
                }

                // Video files are not supported over the mesh; reject with a clear message
                if (com.bitchat.android.features.file.FileUtils.isVideoFile(file.name, mimeType)) {
                    rejectVideoSend(file.name, toPeerIDOrNull, channelOrNull)
                    return@withContext null
                }

                // Try to preserve the original file name if our copier prefixed it earlier
                val originalName = run {
                    val name = file.name
                    val base = name.substringBeforeLast('.')
                    val ext = name.substringAfterLast('.', "").let {
                        if (it.isNotBlank()) ".${it}" else ""
                    }
                    val stripped = Regex("^send_\\d+_(.+)$")
                        .matchEntire(base)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?: base
                    stripped + ext
                }

                BitchatFilePacket(
                    fileName = originalName,
                    fileSize = file.length(),
                    mimeType = mimeType,
                    content = file.readBytes()
                )
            } ?: return

            val messageType = when {
                filePacket.mimeType.lowercase().startsWith("image/") -> BitchatMessageType.Image
                filePacket.mimeType.lowercase().startsWith("audio/") -> BitchatMessageType.Audio
                else -> BitchatMessageType.File
            }

            if (toPeerIDOrNull != null) {
                sendPrivateFile(toPeerIDOrNull, filePacket, filePath, messageType, existingMessageId)
            } else {
                sendPublicFile(channelOrNull, filePacket, filePath, messageType, existingMessageId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "File send failed: ${e.message}", e)
        }
    }

    /**
     * Send a file privately (encrypted)
     */
    private suspend fun sendPrivateFile(
        toPeerID: String,
        filePacket: BitchatFilePacket,
        filePath: String,
        messageType: BitchatMessageType,
        existingMessageId: String? = null
    ) {
        val payload = withContext(mediaWorkDispatcher) { filePacket.encode() }
            ?: run {
                Log.e(TAG, "Failed to encode file packet for private send")
                return
            }

        val transferId = withContext(mediaWorkDispatcher) {
            sha256Hex(payload)
        }
        val recipient = PrivateMediaRecipientResolver.resolve(toPeerID, meshService)
            ?: run {
                addPrivateMediaSystemMessage(
                    toPeerID,
                    "Private media was not sent because this conversation has no active mesh route."
                )
                return
            }

        val pending = PendingAutomaticPrivateMedia(
            requestId = UUID.randomUUID().toString(),
            conversationID = recipient.conversationID,
            recipientMeshPeerID = recipient.meshPeerID,
            filePacket = filePacket,
            filePath = filePath,
            messageType = messageType,
            transferId = transferId,
            allowLegacyFallback = false,
            existingMessageId = existingMessageId
        )
        if (!reserveAutomaticPending(pending)) {
            addPrivateMediaSystemMessage(
                recipient.conversationID,
                "Private media was not sent because another secure media send is still pending."
            )
            return
        }
        evaluateAutomaticPending(pending)
    }

    /**
     * Consume consent exactly once, then re-run policy and final-packet
     * admission. A capability pin appearing while the dialog was open upgrades
     * this send to encrypted rather than forcing the legacy path.
     */
    fun approveLegacyPrivateMedia(requestId: String) {
        scope.launch {
            approveLegacyPrivateMediaAsync(requestId)
        }
    }

    private suspend fun approveLegacyPrivateMediaAsync(requestId: String) {
        val pending = consumePendingConsent(requestId) ?: return
        val automatic = PendingAutomaticPrivateMedia(
            requestId = UUID.randomUUID().toString(),
            conversationID = pending.conversationID,
            recipientMeshPeerID = pending.recipientMeshPeerID,
            filePacket = pending.filePacket,
            filePath = pending.filePath,
            messageType = pending.messageType,
            transferId = pending.transferId,
            allowLegacyFallback = true,
            existingMessageId = pending.existingMessageId
        )
        if (!reserveAutomaticPending(automatic)) {
            addPrivateMediaSystemMessage(
                pending.conversationID,
                "Private media was not sent because another secure media send is still pending."
            )
            return
        }
        evaluateAutomaticPending(automatic)
    }

    fun cancelLegacyPrivateMedia(requestId: String) {
        consumePendingConsent(requestId)
    }

    fun clearPendingPrivateMediaConsent() {
        synchronized(pendingConsentLock) {
            pendingPrivateMedia = null
            pendingAutomaticPrivateMedia = null
            evaluatingAutomaticRequestId = null
            automaticRetryRequestedFor = null
            pendingAutomaticTimeoutRequestId = null
            pendingTimeoutJobs.values.forEach { it.cancel() }
            pendingTimeoutJobs.clear()
            rejectedRetryAttempts.clear()
            _legacyPrivateMediaConsent.value = null
        }
    }

    /** Retry the exact first-send intent after peer-state proof or watchdog resolution. */
    fun retryPendingPrivateMedia(peerID: String) {
        scope.launch { retryPendingPrivateMediaOnScope(peerID) }
    }

    private suspend fun retryPendingPrivateMediaOnScope(peerID: String) {
        val pending = synchronized(pendingConsentLock) {
            pendingAutomaticPrivateMedia
                ?.takeIf { it.recipientMeshPeerID == peerID }
        } ?: return
        evaluateAutomaticPending(pending)
    }

    private suspend fun evaluateAutomaticPending(pending: PendingAutomaticPrivateMedia) {
        val acquired = synchronized(pendingConsentLock) {
            if (pendingAutomaticPrivateMedia?.requestId != pending.requestId) {
                return@synchronized false
            }
            if (evaluatingAutomaticRequestId == pending.requestId) {
                automaticRetryRequestedFor = pending.requestId
                return@synchronized false
            }
            evaluatingAutomaticRequestId = pending.requestId
            true
        }
        if (!acquired) return

        while (true) {
            val preparation = try {
                withContext(mediaWorkDispatcher) {
                    meshService.prepareFilePrivate(
                        recipientPeerID = pending.recipientMeshPeerID,
                        file = pending.filePacket,
                        transferId = pending.transferId,
                        allowLegacyFallback = pending.allowLegacyFallback
                    )
                }
            } catch (error: Exception) {
                PrivateMediaPreparation.Rejected(
                    error.message ?: "Secure private-media preparation failed"
                )
            }
            val stillCurrent = synchronized(pendingConsentLock) {
                pendingAutomaticPrivateMedia?.requestId == pending.requestId
            }
            if (stillCurrent) handlePrivatePreparation(preparation, pending)

            val rerun = synchronized(pendingConsentLock) {
                if (evaluatingAutomaticRequestId == pending.requestId) {
                    evaluatingAutomaticRequestId = null
                }
                val requested = automaticRetryRequestedFor == pending.requestId &&
                    pendingAutomaticPrivateMedia?.requestId == pending.requestId
                if (automaticRetryRequestedFor == pending.requestId) {
                    automaticRetryRequestedFor = null
                }
                if (requested) evaluatingAutomaticRequestId = pending.requestId
                requested
            }
            if (!rerun) return
        }
    }

    private suspend fun handlePrivatePreparation(
        preparation: PrivateMediaPreparation,
        pending: PendingAutomaticPrivateMedia
    ) {
        when (preparation) {
            is PrivateMediaPreparation.Ready -> {
                clearAutomaticPending(pending.requestId)
                commitPreparedPrivateFile(
                    preparation,
                    pending.conversationID,
                    pending.recipientMeshPeerID,
                    pending.filePath,
                    pending.messageType,
                    pending.transferId,
                    pending.existingMessageId
                )
            }

            is PrivateMediaPreparation.RequiresLegacyConsent -> {
                clearAutomaticPending(pending.requestId)
                if (pending.allowLegacyFallback) {
                    Log.w(TAG, "Legacy consent was consumed but policy still requested consent; send aborted")
                    addPrivateMediaSystemMessage(
                        pending.conversationID,
                        "Private media was not sent because its security policy changed."
                    )
                    return
                }
                val nickname = try {
                    meshService.getPeerNicknames()[pending.recipientMeshPeerID]
                } catch (_: Exception) {
                    null
                } ?: pending.recipientMeshPeerID.take(8)
                val request = LegacyPrivateMediaConsentRequest(
                    requestId = UUID.randomUUID().toString(),
                    recipientNickname = nickname,
                    fileName = pending.filePacket.fileName,
                    warning = preparation.warning
                )
                synchronized(pendingConsentLock) {
                    if (pendingPrivateMedia != null) {
                        Log.w(TAG, "A legacy private-media consent prompt is already pending")
                        return
                    }
                    pendingPrivateMedia = PendingPrivateMedia(
                        request,
                        pending.conversationID,
                        pending.recipientMeshPeerID,
                        pending.filePacket,
                        pending.filePath,
                        pending.messageType,
                        pending.transferId,
                        pending.existingMessageId
                    )
                    _legacyPrivateMediaConsent.value = request
                }
            }

            PrivateMediaPreparation.NeedsHandshake -> {
                ensureAutomaticPendingTimeout(pending)
                Log.d(TAG, "Private media needs a Noise handshake; retaining first-send intent")
                try {
                    meshService.initiateNoiseHandshake(pending.recipientMeshPeerID)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not initiate private-media Noise handshake: ${e.message}")
                }
            }

            PrivateMediaPreparation.AwaitingPeerState -> {
                ensureAutomaticPendingTimeout(pending)
                Log.d(TAG, "Private media is waiting for authenticated peer state; first-send intent retained")
            }

            is PrivateMediaPreparation.Rejected -> {
                // Policy/preflight rejections are terminal and surface immediately; only
                // transport/route failures qualify for the bounded route-aware retry.
                if (!isTransportRetryable(preparation.reason)) {
                    clearAutomaticPending(pending.requestId)
                    Log.w(TAG, "Private media not sent: ${preparation.reason}")
                    addPrivateMediaSystemMessage(
                        pending.conversationID,
                        "Private media was not sent: ${preparation.reason}"
                    )
                    return
                }
                val attempt = synchronized(pendingConsentLock) {
                    val n = (rejectedRetryAttempts[pending.requestId] ?: 0) + 1
                    rejectedRetryAttempts[pending.requestId] = n
                    n
                }
                if (attempt <= MAX_PRIVATE_MEDIA_RETRIES) {
                    // Route-aware retry: retain the first-send intent and re-run preparation
                    // after a short delay. By then the mesh may have re-selected a working
                    // transport (first hop moved, alternate radio connected).
                    ensureAutomaticPendingTimeout(pending)
                    Log.w(TAG, "Private media rejected (${preparation.reason}); retrying ($attempt/$MAX_PRIVATE_MEDIA_RETRIES)")
                    synchronized(pendingConsentLock) {
                        if (pendingAutomaticPrivateMedia?.requestId == pending.requestId) {
                            automaticRetryRequestedFor = pending.requestId
                        }
                    }
                    delay(PRIVATE_MEDIA_RETRY_DELAY_MS)
                    return
                }
                clearAutomaticPending(pending.requestId)
                Log.w(TAG, "Private media not sent: ${preparation.reason}")
                addPrivateMediaSystemMessage(
                    pending.conversationID,
                    "Private media was not sent: ${preparation.reason}"
                )
            }
        }
    }

    /**
     * True when a Rejected preparation reason points at the transport/route (a transient
     * condition worth a bounded retry) rather than a terminal policy/preflight rejection.
     */
    private fun isTransportRetryable(reason: String): Boolean {
        val lower = reason.lowercase()
        return lower.contains("transport") ||
            lower.contains("no local transport") ||
            lower.contains("unavailable") ||
            lower.contains("unreachable") ||
            lower.contains("no route") ||
            lower.contains("first hop") ||
            lower.contains("not connected") ||
            lower.contains("could not be reached")
    }

    private fun reserveAutomaticPending(pending: PendingAutomaticPrivateMedia): Boolean =
        synchronized(pendingConsentLock) {
            if (pendingAutomaticPrivateMedia != null) return@synchronized false
            pendingAutomaticPrivateMedia = pending
            true
        }

    private fun ensureAutomaticPendingTimeout(pending: PendingAutomaticPrivateMedia) {
        val shouldStart = synchronized(pendingConsentLock) {
            if (pendingAutomaticPrivateMedia?.requestId != pending.requestId) return@synchronized false
            pendingAutomaticTimeoutRequestId = pending.requestId
            true
        }
        if (!shouldStart) return
        val job = scope.launch {
            delay(PENDING_PRIVATE_MEDIA_TIMEOUT_MS)
            val expired = synchronized(pendingConsentLock) {
                if (pendingAutomaticPrivateMedia?.requestId != pending.requestId) false
                else {
                    pendingAutomaticPrivateMedia = null
                    if (automaticRetryRequestedFor == pending.requestId) {
                        automaticRetryRequestedFor = null
                    }
                    pendingTimeoutJobs.remove(pending.requestId)
                    if (pendingAutomaticTimeoutRequestId == pending.requestId) {
                        pendingAutomaticTimeoutRequestId = null
                    }
                    true
                }
            }
            if (expired) {
                rejectedRetryAttempts.remove(pending.requestId)
                addPrivateMediaSystemMessage(
                    pending.conversationID,
                    "Private media was not sent because secure session setup timed out."
                )
            }
        }
        // Re-arm: cancel any previous watchdog for this request so retries slide the window.
        synchronized(pendingConsentLock) {
            pendingTimeoutJobs[pending.requestId]?.cancel()
            pendingTimeoutJobs[pending.requestId] = job
        }
    }

    private fun clearAutomaticPending(requestId: String) {
        synchronized(pendingConsentLock) {
            if (pendingAutomaticPrivateMedia?.requestId == requestId) {
                pendingAutomaticPrivateMedia = null
            }
            if (automaticRetryRequestedFor == requestId) automaticRetryRequestedFor = null
            if (pendingAutomaticTimeoutRequestId == requestId) {
                pendingAutomaticTimeoutRequestId = null
            }
            pendingTimeoutJobs.remove(requestId)?.cancel()
            rejectedRetryAttempts.remove(requestId)
        }
    }

    private fun addPrivateMediaSystemMessage(peerID: String, text: String) {
        messageManager.addPrivateMessageNoUnread(
            peerID,
            BitchatMessage(
                sender = "system",
                content = text,
                timestamp = Date(),
                isRelay = false,
                isPrivate = true,
                senderPeerID = peerID
            )
        )
    }

    private fun consumePendingConsent(requestId: String): PendingPrivateMedia? {
        return synchronized(pendingConsentLock) {
            val pending = pendingPrivateMedia
            if (pending?.request?.requestId != requestId) return@synchronized null
            pendingPrivateMedia = null
            _legacyPrivateMediaConsent.value = null
            pending
        }
    }

    private suspend fun commitPreparedPrivateFile(
        preparation: PrivateMediaPreparation.Ready,
        conversationID: String,
        recipientMeshPeerID: String,
        filePath: String,
        messageType: BitchatMessageType,
        transferId: String,
        existingMessageId: String? = null
    ) {
        if (preparation.transfer.transferId != transferId) {
            Log.e(TAG, "Prepared private-media transfer ID changed; send aborted")
            return
        }

        // A one-tap retry reuses the failed bubble: the message already exists in the
        // conversation, so skip the durable echo and just re-arm its transfer tracking.
        val msgId = existingMessageId ?: UUID.randomUUID().toString().uppercase()
        if (existingMessageId == null) {
            val msg = BitchatMessage(
                id = msgId,
                sender = state.getNicknameValue() ?: "me",
                content = filePath,
                type = messageType,
                timestamp = Date(),
                isRelay = false,
                isPrivate = true,
                recipientNickname = try {
                    meshService.getPeerNicknames()[recipientMeshPeerID]
                } catch (_: Exception) {
                    null
                },
                senderPeerID = meshService.myPeerID
            )

            // Preparation already built and admitted the exact final packet. Map
            // progress before commit so the first asynchronous event cannot race us.
            if (!messageManager.addPrivateMessageDurably(conversationID, msg, forceRead = true)) {
                Log.e(TAG, "Prepared private-media message could not be persisted; send aborted")
                addPrivateMediaSystemMessage(
                    conversationID,
                    "Private media was not sent because the conversation could not be saved."
                )
                return
            }
        }
        synchronized(transferMessageMap) {
            transferMessageMap[transferId] = msgId
            messageTransferMap[msgId] = transferId
        }
        messageManager.updateMessageDeliveryStatus(
            msgId,
            com.bitchat.android.model.DeliveryStatus.PartiallyDelivered(0, 100)
        )

        if (!preparation.transfer.commit()) {
            synchronized(transferMessageMap) {
                transferMessageMap.remove(transferId)
                messageTransferMap.remove(msgId)
            }
            messageManager.updateMessageDeliveryStatus(
                msgId,
                com.bitchat.android.model.DeliveryStatus.Failed(
                    "Prepared transfer could not be committed"
                )
            )
            Log.w(TAG, "Prepared private-media commit failed; local echo marked failed")
            addPrivateMediaSystemMessage(
                conversationID,
                "Private media was not sent because the prepared transfer could not be committed."
            )
            return
        }
    }

    /**
     * Send a file publicly (broadcast or channel)
     */
    private suspend fun sendPublicFile(
        channelOrNull: String?,
        filePacket: BitchatFilePacket,
        filePath: String,
        messageType: BitchatMessageType,
        existingMessageId: String? = null
    ) {
        val payload = withContext(mediaWorkDispatcher) { filePacket.encode() }
            ?: run {
                Log.e(TAG, "Failed to encode file packet for broadcast send")
                return
            }

        val transferId = withContext(mediaWorkDispatcher) {
            sha256Hex(payload)
        }

        // A one-tap retry reuses the failed bubble: the message already exists in the
        // conversation, so skip the local echo and re-arm its transfer tracking under the
        // same id.
        val msgId = existingMessageId ?: java.util.UUID.randomUUID().toString().uppercase()
        if (existingMessageId == null) {
            val message = BitchatMessage(
                id = msgId, // Generate unique ID for each message
                sender = state.getNicknameValue() ?: meshService.myPeerID,
                content = filePath,
                type = messageType,
                timestamp = Date(),
                isRelay = false,
                senderPeerID = meshService.myPeerID,
                channel = channelOrNull
            )

            if (!channelOrNull.isNullOrBlank()) {
                channelManager.addChannelMessage(channelOrNull, message, meshService.myPeerID)
            } else {
                messageManager.addMessage(message)
            }
        }

        synchronized(transferMessageMap) {
            transferMessageMap[transferId] = msgId
            messageTransferMap[msgId] = transferId
        }

        // Seed progress so animations start immediately
        messageManager.updateMessageDeliveryStatus(
            msgId,
            com.bitchat.android.model.DeliveryStatus.PartiallyDelivered(0, 100)
        )

        withContext(mediaWorkDispatcher) {
            meshService.sendFileBroadcast(filePacket)
        }
    }

    /**
     * One-tap retry for a failed media bubble (image, voice note, or file). Re-sends the
     * original file into the same conversation, reusing the existing bubble instead of creating
     * a duplicate, and immediately clears the failed state so the ⚠ disappears.
     */
    fun retryMediaSend(messageId: String) {
        scope.launch {
            val context = resolveRetryContext(messageId) ?: return@launch
            val file = java.io.File(context.filePath)
            if (!file.exists()) {
                postRetryContextSystemMessage(
                    context,
                    "Cannot resend this message: the original file is no longer available on this device."
                )
                return@launch
            }
            // For private sends, fail fast with a clear message when there is definitely no
            // route, so the user is not left waiting on a send that can never start.
            if (context.peerIDOrNull != null) {
                val recipient = PrivateMediaRecipientResolver.resolve(context.peerIDOrNull, meshService)
                if (recipient == null) {
                    postRetryContextSystemMessage(
                        context,
                        "Cannot resend this message: this conversation has no active mesh route."
                    )
                    return@launch
                }
            }
            // Note: the bubble's delivery status is left untouched here. The send pipeline seeds
            // PartiallyDelivered at commit time (sendPublicFile / commitPreparedPrivateFile), so
            // any early-abort path (file gone, oversized, consent canceled, reservation conflict)
            // leaves the bubble truthfully Failed instead of stranding it in an in-flight limbo.
            when (context.message.type) {
                BitchatMessageType.Image -> sendImageNoteAsync(
                    context.peerIDOrNull,
                    context.channelOrNull,
                    context.filePath,
                    existingMessageId = messageId
                )
                BitchatMessageType.Audio -> sendVoiceNoteAsync(
                    context.peerIDOrNull,
                    context.channelOrNull,
                    context.filePath,
                    existingMessageId = messageId
                )
                else -> sendFileNoteAsync(
                    context.peerIDOrNull,
                    context.channelOrNull,
                    context.filePath,
                    existingMessageId = messageId
                )
            }
        }
    }

    /**
     * Locate a message by id and figure out which conversation it belongs to, so a retry can
     * resend into exactly the same place it originally went.
     */
    private fun resolveRetryContext(messageId: String): RetrySendContext? {
        // Private conversations: the map key is the canonical conversation ID and private
        // media messages live under it.
        state.getPrivateChatsValue().forEach { (conversationID, list) ->
            list.firstOrNull { it.id == messageId }?.let { msg ->
                return RetrySendContext(msg, msg.content.trim(), conversationID, null)
            }
        }
        // Channels: the map key is the channel tag.
        state.getChannelMessagesValue().forEach { (channel, list) ->
            list.firstOrNull { it.id == messageId }?.let { msg ->
                return RetrySendContext(msg, msg.content.trim(), null, channel)
            }
        }
        // Public timeline (may still carry a channel tag).
        state.getMessagesValue().firstOrNull { it.id == messageId }?.let { msg ->
            return RetrySendContext(msg, msg.content.trim(), null, msg.channel)
        }
        return null
    }

    private fun postRetryContextSystemMessage(context: RetrySendContext, text: String) {
        when {
            context.peerIDOrNull != null -> messageManager.addPrivateMessageNoUnread(
                context.peerIDOrNull,
                BitchatMessage(
                    sender = "system",
                    content = text,
                    timestamp = Date(),
                    isRelay = false
                )
            )
            context.channelOrNull != null -> messageManager.addChannelMessage(
                context.channelOrNull,
                BitchatMessage(
                    sender = "system",
                    content = text,
                    timestamp = Date(),
                    isRelay = false
                )
            )
            else -> messageManager.addSystemMessage(text)
        }
    }

    /**
     * Cancel a media transfer by message ID
     */
    fun cancelMediaSend(messageId: String) {
        val transferId = synchronized(transferMessageMap) { messageTransferMap[messageId] }
        if (transferId != null) {
            val cancelled = meshService.cancelFileTransfer(transferId)
            if (cancelled) {
                // Try to remove cached local file for this message (if any)
                runCatching { findMessagePathById(messageId)?.let { java.io.File(it).delete() } }

                // Remove the message from chat upon explicit cancel
                messageManager.removeMessageById(messageId)
                synchronized(transferMessageMap) {
                    transferMessageMap.remove(transferId)
                    messageTransferMap.remove(messageId)
                }
            }
        }
    }

    private fun findMessagePathById(messageId: String): String? {
        // Search main timeline
        state.getMessagesValue().firstOrNull { it.id == messageId }?.content?.let { return it }
        // Search private chats
        state.getPrivateChatsValue().values.forEach { list ->
            list.firstOrNull { it.id == messageId }?.content?.let { return it }
        }
        // Search channel messages
        state.getChannelMessagesValue().values.forEach { list ->
            list.firstOrNull { it.id == messageId }?.content?.let { return it }
        }
        return null
    }

    /**
     * Update progress for a transfer
     */
    fun updateTransferProgress(transferId: String, messageId: String) {
        synchronized(transferMessageMap) {
            transferMessageMap[transferId] = messageId
            messageTransferMap[messageId] = transferId
        }
    }

    /**
     * Handle transfer progress events
     */
    fun handleTransferProgressEvent(evt: com.bitchat.android.mesh.TransferProgressEvent) {
        val msgId = synchronized(transferMessageMap) { transferMessageMap[evt.transferId] }
        if (msgId != null) {
            if (evt.failed) {
                messageManager.updateMessageDeliveryStatus(
                    msgId,
                    com.bitchat.android.model.DeliveryStatus.Failed("transfer could not be sent")
                )
                synchronized(transferMessageMap) {
                    val msgIdRemoved = transferMessageMap.remove(evt.transferId)
                    if (msgIdRemoved != null) messageTransferMap.remove(msgIdRemoved)
                }
            } else if (evt.completed) {
                messageManager.updateMessageDeliveryStatus(
                    msgId,
                    com.bitchat.android.model.DeliveryStatus.Delivered(to = "mesh", at = java.util.Date())
                )
                synchronized(transferMessageMap) {
                    val msgIdRemoved = transferMessageMap.remove(evt.transferId)
                    if (msgIdRemoved != null) messageTransferMap.remove(msgIdRemoved)
                }
            } else {
                messageManager.updateMessageDeliveryStatus(
                    msgId,
                    com.bitchat.android.model.DeliveryStatus.PartiallyDelivered(evt.sent, evt.total)
                )
            }
        }
    }

    private fun sha256Hex(bytes: ByteArray): String = try {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(bytes)
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        bytes.size.toString(16)
    }
}
