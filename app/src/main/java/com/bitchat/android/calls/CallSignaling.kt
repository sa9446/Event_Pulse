package com.bitchat.android.calls

import com.bitchat.android.model.CallSignal
import com.bitchat.android.model.NoisePayloadType
import android.util.Log

/**
 * Codec helpers for real-time call traffic.
 *
 * Control signals travel as tiny JSON inside a [NoisePayload] of type CALL_* (encrypted,
 * reliable, single-fragment). Media frames travel as raw CALL_MEDIA packet payloads with a
 * two-byte header so audio and video can share one socket stream:
 *
 * ```
 * byte 0       : stream id  (STREAM_AUDIO / STREAM_VIDEO)
 * byte 1       : flags     (FLAG_KEYFRAME for video)
 * byte 2..N    : media payload
 * ```
 */
object CallSignaling {

    private const val TAG = "CallSignaling"

    const val STREAM_AUDIO: Int = 0x01
    const val STREAM_VIDEO: Int = 0x02

    const val FLAG_KEYFRAME: Int = 0x01

    /**
     * Hard ceiling for a single media frame. Keeps the encoded v2 CALL_MEDIA packet under the
     * 64 KB SyncedSocket frame limit (16-byte header + 8-byte sender + 8-byte recipient), so the
     * peer socket read loop never rejects it. Frames larger than this are dropped by the senders.
     */
    const val MAX_MEDIA_FRAME_PAYLOAD: Int = 60_000

    /** How long a dialing call rings before it is cancelled locally. */
    const val RING_TIMEOUT_MS: Long = 30_000

    /** How long an incoming call rings before it is rejected locally. */
    const val INCOMING_RING_TIMEOUT_MS: Long = 45_000

    // ── Control signals ─────────────────────────────────────────────────────

    /** Build the CALL_INVITE payload data (JSON) for [callId]. */
    fun encodeInvite(callId: String, callerNickname: String, video: Boolean): ByteArray =
        CallSignal(callId, callerNickname, video).toJson().toByteArray(Charsets.UTF_8)

    /** Build the CALL_ACCEPT / CALL_REJECT / CALL_END payload data (JSON) for [callId]. */
    fun encodeSimple(callId: String): ByteArray =
        CallSignal(callId).toJson().toByteArray(Charsets.UTF_8)

    /**
     * Parse a call signal payload. Returns the [CallSignal] plus the [NoisePayloadType] it rode
     * in on (INVITE/ACCEPT/REJECT/END). Returns null for malformed data.
     */
    fun decodeSignal(signalType: NoisePayloadType, payload: ByteArray): Pair<NoisePayloadType, CallSignal>? {
        val signal = CallSignal.fromJson(payload.toString(Charsets.UTF_8)) ?: return null
        val type = when (signalType) {
            NoisePayloadType.CALL_INVITE,
            NoisePayloadType.CALL_ACCEPT,
            NoisePayloadType.CALL_REJECT,
            NoisePayloadType.CALL_END -> signalType
            else -> return null
        }
        return type to signal
    }

    // ── Media frames ─────────────────────────────────────────────────────────

    /** Wrap [payload] in the two-byte media frame header. */
    fun wrapFrame(stream: Int, flags: Int, payload: ByteArray): ByteArray {
        if (payload.size > MAX_MEDIA_FRAME_PAYLOAD) {
            Log.w(TAG, "Dropping oversized media frame (${payload.size} > $MAX_MEDIA_FRAME_PAYLOAD)")
            return ByteArray(0)
        }
        return ByteArray(2 + payload.size).also { out ->
            out[0] = stream.toByte()
            out[1] = flags.toByte()
            payload.copyInto(out, destinationOffset = 2)
        }
    }

    /**
     * Split a raw CALL_MEDIA payload back into (stream, flags, mediaBytes).
     * Returns null when the frame is malformed or shorter than the two-byte header.
     */
    fun unwrapFrame(frame: ByteArray): Triple<Int, Int, ByteArray>? {
        if (frame.size < 2) return null
        val stream = frame[0].toInt() and 0xFF
        val flags = frame[1].toInt() and 0xFF
        return Triple(stream, flags, frame.copyOfRange(2, frame.size))
    }

    /** Convenience: build a complete audio media frame. */
    fun audioFrame(pcm: ByteArray): ByteArray = wrapFrame(STREAM_AUDIO, 0, pcm)

    /** Convenience: build a complete video media frame. */
    fun videoFrame(nalBytes: ByteArray, isKeyframe: Boolean): ByteArray =
        wrapFrame(STREAM_VIDEO, if (isKeyframe) FLAG_KEYFRAME else 0, nalBytes)
}
