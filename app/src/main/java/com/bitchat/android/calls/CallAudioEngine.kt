package com.bitchat.android.calls

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log

/**
 * Minimal real-time audio endpoint for calls.
 *
 * - Capture: [AudioRecord] at 16 kHz mono PCM16, read in 20 ms (640-byte) chunks and handed to
 *   the call manager for transport. Raw PCM keeps the pipeline codec-free and trivially robust;
 *   32 KB/s each way is nothing for a Wi-Fi Direct / Wi-Fi Aware link.
 * - Playback: [AudioTrack] in streaming mode; inbound chunks are written straight in. If the
 *   transport jitters, the track simply underruns briefly — acceptable for a first real-time
 *   pass and far safer than dropping audio on a heuristic.
 *
 * Thread-safety: capture runs on its own daemon thread; [feed] may be called from any thread
 * (AudioTrack.write is internally synchronized).
 */
class CallAudioEngine {

    companion object {
        private const val TAG = "CallAudioEngine"
        const val SAMPLE_RATE = 16_000
        const val CHUNK_BYTES = 640 // 20 ms of 16 kHz mono PCM16
        const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    @Volatile private var running = false
    @Volatile private var captureThread: Thread? = null
    @Volatile private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private val trackLock = Any()
    private var sendChunk: ((ByteArray) -> Unit)? = null

    /** When true, captured chunks are dropped (microphone muted). */
    @Volatile var muted: Boolean = false

    val isRunning: Boolean get() = running

    /**
     * Start capturing and delivering 20 ms PCM chunks to [onChunk]. Idempotent.
     */
    fun start(onChunk: (ByteArray) -> Unit) {
        if (running) return
        running = true
        sendChunk = onChunk
        captureThread = Thread({ captureLoop() }, "call-audio-capture").apply {
            isDaemon = true
            start()
        }
    }

    private fun captureLoop() {
        val minBuffer = try {
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
        } catch (e: Exception) {
            0
        }
        val bufferSize = maxOf(minBuffer, CHUNK_BYTES * 5)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_IN,
                ENCODING,
                bufferSize
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord init failed: ${e.message}")
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized")
            record.release()
            return
        }
        audioRecord = record
        try {
            record.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "startRecording failed: ${e.message}")
            runCatching { record.release() }
            audioRecord = null
            return
        }
        Log.i(TAG, "Capture started (16 kHz mono PCM)")
        val chunk = ByteArray(CHUNK_BYTES)
        while (running) {
            val n = try {
                record.read(chunk, 0, chunk.size)
            } catch (e: Exception) {
                Log.w(TAG, "Capture read failed: ${e.message}")
                break
            }
            if (n <= 0) continue
            if (!muted) {
                val pcm = if (n == chunk.size) chunk else chunk.copyOf(n)
                try {
                    sendChunk?.invoke(pcm)
                } catch (e: Exception) {
                    Log.w(TAG, "Chunk send failed: ${e.message}")
                }
            }
        }
        try { record.stop() } catch (_: Exception) { }
        record.release()
        audioRecord = null
        Log.i(TAG, "Capture stopped")
    }

    /**
     * Queue an inbound PCM chunk for playback. Lazily creates the [AudioTrack] on first use so
     * an audio-only caller that never receives audio pays no setup cost.
     */
    fun feed(pcm: ByteArray) {
        if (!running || pcm.isEmpty()) return
        ensureTrack()?.let { track ->
            try {
                track.write(pcm, 0, pcm.size)
            } catch (e: Exception) {
                Log.w(TAG, "Playback write failed: ${e.message}")
            }
        }
    }

    private fun ensureTrack(): AudioTrack? {
        synchronized(trackLock) {
            audioTrack?.let { return it }
            val minBuffer = try {
                AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING)
            } catch (e: Exception) {
                CHUNK_BYTES * 20
            }
            val track = try {
                AudioTrack(
                    AudioManager.STREAM_VOICE_CALL,
                    SAMPLE_RATE,
                    CHANNEL_OUT,
                    ENCODING,
                    maxOf(minBuffer, CHUNK_BYTES * 20),
                    AudioTrack.MODE_STREAM
                )
            } catch (e: Exception) {
                Log.e(TAG, "AudioTrack init failed: ${e.message}")
                return null
            }
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                track.release()
                return null
            }
            track.play()
            audioTrack = track
            return track
        }
    }

    /** Stop capture + playback and release all resources. Idempotent. */
    fun stop() {
        if (!running) return
        running = false
        captureThread?.interrupt()
        captureThread = null
        synchronized(trackLock) {
            audioTrack?.let {
                try { it.pause() } catch (_: Exception) { }
                try { it.flush() } catch (_: Exception) { }
                try { it.release() } catch (_: Exception) { }
            }
            audioTrack = null
        }
        sendChunk = null
        Log.i(TAG, "Engine stopped")
    }
}
