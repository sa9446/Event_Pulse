package com.bitchat.android.calls

import com.bitchat.android.model.CallSignal
import com.bitchat.android.model.NoisePayloadType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the real-time call signaling payload codec ([CallSignaling]) and the
 * JSON [CallSignal] envelope. Pure logic — no Android framework calls, so it runs as a
 * plain JVM test.
 */
class CallSignalingTest {

    @Test
    fun `invite round trips with nickname and video flag`() {
        val payload = CallSignaling.encodeInvite("call-123", "Alice", video = true)
        val decoded = CallSignaling.decodeSignal(NoisePayloadType.CALL_INVITE, payload)
        assertNotNull(decoded)
        val (type, signal) = decoded!!
        assertEquals(NoisePayloadType.CALL_INVITE, type)
        assertEquals("call-123", signal.callId)
        assertEquals("Alice", signal.nickname)
        assertTrue(signal.video)
    }

    @Test
    fun `simple signals round trip for accept reject and end`() {
        for (type in listOf(
            NoisePayloadType.CALL_ACCEPT,
            NoisePayloadType.CALL_REJECT,
            NoisePayloadType.CALL_END
        )) {
            val payload = CallSignaling.encodeSimple("call-abc")
            val (decodedType, signal) = CallSignaling.decodeSignal(type, payload)!!
            assertEquals(type, decodedType)
            assertEquals("call-abc", signal.callId)
        }
    }

    @Test
    fun `decode rejects signals from non-call types`() {
        val payload = CallSignaling.encodeSimple("call-abc")
        assertNull(CallSignaling.decodeSignal(NoisePayloadType.PRIVATE_MESSAGE, payload))
        assertNull(CallSignaling.decodeSignal(NoisePayloadType.FILE_TRANSFER, payload))
    }

    @Test
    fun `decode rejects malformed json and blank call ids`() {
        assertNull(CallSignaling.decodeSignal(NoisePayloadType.CALL_END, "not json".toByteArray()))
        assertNull(
            CallSignaling.decodeSignal(
                NoisePayloadType.CALL_END,
                CallSignal(callId = "").toJson().toByteArray()
            )
        )
    }

    @Test
    fun `audio frame round trips`() {
        val pcm = ByteArray(640) { it.toByte() }
        val frame = CallSignaling.audioFrame(pcm)
        val (stream, flags, media) = CallSignaling.unwrapFrame(frame)!!
        assertEquals(CallSignaling.STREAM_AUDIO, stream)
        assertEquals(0, flags)
        assertTrue(media.contentEquals(pcm))
    }

    @Test
    fun `video frame round trips with keyframe flag`() {
        val nal = byteArrayOf(0, 0, 0, 1, 0x65, 1, 2, 3) // IDR prefix
        val frame = CallSignaling.videoFrame(nal, isKeyframe = true)
        val (stream, flags, media) = CallSignaling.unwrapFrame(frame)!!
        assertEquals(CallSignaling.STREAM_VIDEO, stream)
        assertEquals(CallSignaling.FLAG_KEYFRAME, flags)
        assertTrue(media.contentEquals(nal))
    }

    @Test
    fun `video frame without keyframe flag`() {
        val nal = byteArrayOf(0, 0, 0, 1, 0x41, 9, 8, 7)
        val frame = CallSignaling.videoFrame(nal, isKeyframe = false)
        val (_, flags, media) = CallSignaling.unwrapFrame(frame)!!
        assertEquals(0, flags)
        assertTrue(media.contentEquals(nal))
    }

    @Test
    fun `unwrap rejects frames shorter than the two-byte header`() {
        assertNull(CallSignaling.unwrapFrame(ByteArray(0)))
        assertNull(CallSignaling.unwrapFrame(byteArrayOf(0x01)))
    }

    @Test
    fun `oversized media frames are dropped`() {
        val huge = ByteArray(CallSignaling.MAX_MEDIA_FRAME_PAYLOAD + 1)
        assertTrue(CallSignaling.wrapFrame(CallSignaling.STREAM_VIDEO, 0, huge).isEmpty())
    }

    @Test
    fun `max frame payload fits the wire constraint`() {
        // The wrapped payload must stay under the SyncedSocket 64 KB frame limit once the
        // v2 BitchatPacket header (16 B header + 8 B sender + 8 B recipient) is added.
        val payload = ByteArray(CallSignaling.MAX_MEDIA_FRAME_PAYLOAD)
        val frame = CallSignaling.wrapFrame(CallSignaling.STREAM_AUDIO, 0, payload)
        assertTrue(frame.size + 32 <= 64 * 1024)
    }
}
