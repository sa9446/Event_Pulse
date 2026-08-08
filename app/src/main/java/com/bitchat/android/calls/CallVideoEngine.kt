package com.bitchat.android.calls

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Real-time H.264 video endpoint for calls.
 *
 * Local capture: the front camera renders straight into the encoder's input [Surface]
 * (CameraX [Preview] bound to a provider that hands out the encoder surface), so no YUV copies
 * are needed. Encoded Annex-B frames are emitted to the call manager.
 *
 * Remote playback: inbound Annex-B frames are queued to a **dedicated decode thread** (never the
 * packet-processing actor) and decoded to the caller-provided output [Surface] (a TextureView
 * surface owned by the UI). The decoder is configured with a csd-0 (SPS/PPS) extracted from the
 * stream once both the surface and parameter sets are available.
 *
 * All codecs, threads, executors and the camera binding are released on [stop]; the instance is
 * not reusable after a stop.
 */
class CallVideoEngine {

    companion object {
        private const val TAG = "CallVideoEngine"
        const val WIDTH = 640
        const val HEIGHT = 480
        const val FPS = 15
        const val BIT_RATE = 350_000
        private const val I_FRAME_INTERVAL_SEC = 1
        private const val PTS_PER_FRAME_US = 66_000L // 15 fps
        private const val MAX_PENDING_FRAMES = 90
        private const val DECODE_QUEUE_CAPACITY = 48
    }

    @Volatile private var running = false

    // Local (encode) side
    private var encoder: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var encoderThread: Thread? = null
    @Volatile private var sendFrame: ((ByteArray, Boolean) -> Unit)? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var surfaceProviderExecutor: java.util.concurrent.ExecutorService? = null

    // Remote (decode) side
    private var decoder: MediaCodec? = null
    private var decoderOutputSurface: Surface? = null
    private var decoderReady = false
    private val pendingFrames = ArrayDeque<ByteArray>()
    private val pendingLock = Any()
    private val decodeQueue = LinkedBlockingQueue<ByteArray>(DECODE_QUEUE_CAPACITY)
    private var decodeThread: Thread? = null
    private var ptsCounter = 0L

    val isRunning: Boolean get() = running

    /**
     * Start the front camera + H.264 encoder. Each encoded frame is delivered to [onFrame] as
     * (annexBBytes, isKeyframe). Idempotent.
     */
    fun startLocalVideo(context: Context, lifecycleOwner: LifecycleOwner, onFrame: (ByteArray, Boolean) -> Unit) {
        if (running) return
        running = true
        sendFrame = onFrame

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SEC)
        }
        val codec = try {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        } catch (e: Exception) {
            Log.e(TAG, "AVC encoder unavailable: ${e.message}")
            running = false
            return
        }
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = codec.createInputSurface()
            codec.start()
            encoder = codec
            encoderSurface = inputSurface
        } catch (e: Exception) {
            Log.e(TAG, "Encoder configure failed: ${e.message}")
            runCatching { codec.release() }
            running = false
            return
        }

        encoderThread = Thread({ encoderLoop() }, "call-video-encode").apply {
            isDaemon = true
            start()
        }

        bindCamera(context, lifecycleOwner)
    }

    private fun bindCamera(context: Context, lifecycleOwner: LifecycleOwner) {
        val inputSurface = encoderSurface ?: return
        val providerExecutor = Executors.newSingleThreadExecutor()
        surfaceProviderExecutor = providerExecutor
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                val preview = Preview.Builder().build()
                // Camera renders directly into the encoder's input surface.
                preview.surfaceProvider = SurfaceRequestSurfaceProvider(inputSurface, providerExecutor)
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview)
                Log.i(TAG, "Camera bound to encoder surface")
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** Provides the encoder input surface to CameraX when the camera requests a surface. */
    private class SurfaceRequestSurfaceProvider(
        private val inputSurface: Surface,
        private val resultExecutor: java.util.concurrent.ExecutorService
    ) : Preview.SurfaceProvider {
        override fun onSurfaceRequested(request: SurfaceRequest) {
            request.provideSurface(inputSurface, resultExecutor) { result ->
                if (result.resultCode != SurfaceRequest.Result.RESULT_SURFACE_USED_SUCCESSFULLY) {
                    Log.w(TAG, "Camera surface request not used (code=${result.resultCode})")
                }
            }
        }
    }

    private fun encoderLoop() {
        val codec = encoder ?: return
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index = try {
                codec.dequeueOutputBuffer(info, 10_000)
            } catch (e: Exception) {
                Log.w(TAG, "Encoder dequeue failed: ${e.message}")
                break
            }
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // SPS/PPS are embedded in-band in Annex-B output; no action needed.
                }
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index) ?: continue
                    val bytes = ByteArray(info.size)
                    buffer.get(bytes)
                    val isKeyframe = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                    codec.releaseOutputBuffer(index, false)
                    if (bytes.isNotEmpty()) {
                        try {
                            sendFrame?.invoke(bytes, isKeyframe)
                        } catch (e: Exception) {
                            Log.w(TAG, "Video frame send failed: ${e.message}")
                        }
                    }
                }
                else -> {
                    if (!running) break
                }
            }
        }
        Log.i(TAG, "Encoder loop ended")
    }

    // ── Remote (decode) side ─────────────────────────────────────────────────

    /**
     * Provide the surface the remote video should render to. Configures and starts the decoder
     * (using SPS/PPS held from earlier frames) and flushes any queued frames. The UI calls this
     * once its TextureView surface is available.
     */
    fun setRemoteSurface(surface: Surface) {
        decoderOutputSurface = surface
        if (running && !decoderReady) {
            configureDecoderIfPossible()
        }
    }

    /**
     * Queue an inbound Annex-B frame for decode. Frames received before the decoder is ready are
     * held briefly (bounded) so the first keyframe + SPS/PPS survive until the UI surface lands.
     * Called from the packet-processing thread — never blocks it (a bounded queue + a dedicated
     * decode thread do the actual codec work).
     */
    fun onRemoteFrame(nalBytes: ByteArray) {
        if (!running) return
        if (!decoderReady) {
            synchronized(pendingLock) {
                if (pendingFrames.size >= MAX_PENDING_FRAMES) pendingFrames.removeFirst()
                pendingFrames.addLast(nalBytes)
            }
            configureDecoderIfPossible()
            return
        }
        // Bounded queue: if the decoder is saturated, drop the oldest frame rather than
        // blocking the packet pipeline or growing memory without bound.
        if (!decodeQueue.offer(nalBytes)) {
            decodeQueue.poll()
            decodeQueue.offer(nalBytes)
        }
    }

    private fun configureDecoderIfPossible() {
        if (decoderReady || !running) return
        val surface = decoderOutputSurface ?: return
        synchronized(pendingLock) {
            if (decoderReady) return
            val csd = extractCsd0(pendingFrames) ?: return
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT)
            format.setByteBuffer("csd-0", csd)
            val codec = try {
                MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            } catch (e: Exception) {
                Log.e(TAG, "AVC decoder unavailable: ${e.message}")
                return
            }
            try {
                codec.configure(format, surface, null, 0)
                codec.start()
            } catch (e: Exception) {
                Log.e(TAG, "Decoder configure failed: ${e.message}")
                runCatching { codec.release() }
                return
            }
            decoder = codec
            decoderReady = true
            Log.i(TAG, "Decoder started with csd-0 (${csd.remaining()} bytes)")
            // Flush the pending queue through the now-ready decoder.
            val queued = pendingFrames.toList()
            pendingFrames.clear()
            for (frame in queued) {
                if (!decodeQueue.offer(frame)) break
            }
            ensureDecodeThread()
        }
    }

    private fun ensureDecodeThread() {
        if (decodeThread != null) return
        decodeThread = Thread({ decodeLoop() }, "call-video-decode").apply {
            isDaemon = true
            start()
        }
    }

    private fun decodeLoop() {
        while (running) {
            val frame = try {
                decodeQueue.poll(200, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                break
            } ?: continue
            feedDecoder(frame)
        }
        Log.i(TAG, "Decode loop ended")
    }

    /** Extract SPS+PPS (NAL types 7 & 8) from Annex-B frames into a csd-0 buffer. */
    private fun extractCsd0(frames: Iterable<ByteArray>): ByteBuffer? {
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        outer@ for (frame in frames) {
            var offset = 0
            while (offset + 4 <= frame.size) {
                val start = findStartCode(frame, offset)
                if (start < 0) break
                // NAL header byte sits AFTER the 4-byte 00 00 00 01 start code.
                val nalHeader = start + 4
                if (nalHeader >= frame.size) break
                val nalType = frame[nalHeader].toInt() and 0x1F
                val next = findStartCode(frame, nalHeader)
                val end = if (next < 0) frame.size else next
                if (end > nalHeader) {
                    val nal = frame.copyOfRange(nalHeader, end)
                    when (nalType) {
                        7 -> sps = nal
                        8 -> pps = nal
                    }
                    if (sps != null && pps != null) break@outer
                }
                offset = if (next < 0) frame.size else next
            }
        }
        if (sps == null || pps == null) return null
        val buffer = ByteBuffer.allocate(4 + sps.size + 4 + pps.size)
        buffer.put(byteArrayOf(0, 0, 0, 1))
        buffer.put(sps)
        buffer.put(byteArrayOf(0, 0, 0, 1))
        buffer.put(pps)
        buffer.flip()
        return buffer
    }

    /** Returns the index of the next 4-byte 00 00 00 01 start code at or after [from], or -1. */
    private fun findStartCode(frame: ByteArray, from: Int): Int {
        var i = from
        while (i + 3 < frame.size) {
            if (frame[i] == 0.toByte() && frame[i + 1] == 0.toByte() &&
                frame[i + 2] == 0.toByte() && frame[i + 3] == 1.toByte()
            ) {
                return i
            }
            i++
        }
        return -1
    }

    private fun feedDecoder(nalBytes: ByteArray) {
        val codec = decoder ?: return
        try {
            val inIndex = codec.dequeueInputBuffer(10_000)
            if (inIndex >= 0) {
                val buffer = codec.getInputBuffer(inIndex) ?: return
                buffer.clear()
                buffer.put(nalBytes)
                val flags = if (looksLikeKeyframe(nalBytes)) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                codec.queueInputBuffer(inIndex, 0, nalBytes.size, ptsCounter, flags)
                ptsCounter += PTS_PER_FRAME_US
            }
        } catch (e: Exception) {
            Log.w(TAG, "Decoder input failed: ${e.message}")
        }
        drainDecoder()
    }

    private fun drainDecoder() {
        val codec = decoder ?: return
        val info = MediaCodec.BufferInfo()
        var drained = 0
        while (drained < 4) {
            val index = try {
                codec.dequeueOutputBuffer(info, 0)
            } catch (e: Exception) {
                break
            }
            when {
                index >= 0 -> {
                    codec.releaseOutputBuffer(index, true) // render to surface
                    drained++
                }
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                else -> { /* format changed / output buffers changed — ignore */ }
            }
        }
    }

    private fun looksLikeKeyframe(nalBytes: ByteArray): Boolean {
        // Scan for an IDR NAL (type 5) with 3- or 4-byte start codes. Best-effort; decoders
        // accept missing flags.
        var i = 0
        while (i + 3 < nalBytes.size) {
            if (nalBytes[i] == 0.toByte() && nalBytes[i + 1] == 0.toByte() &&
                (nalBytes[i + 2].toInt() and 0xFF) == 1 && (nalBytes[i + 3].toInt() and 0x1F) == 5
            ) {
                return true
            }
            i++
        }
        var j = 0
        while (j + 4 < nalBytes.size) {
            if (nalBytes[j] == 0.toByte() && nalBytes[j + 1] == 0.toByte() &&
                nalBytes[j + 2] == 0.toByte() && (nalBytes[j + 3].toInt() and 0xFF) == 1 &&
                (nalBytes[j + 4].toInt() and 0x1F) == 5
            ) {
                return true
            }
            j++
        }
        return false
    }

    /** Stop capture/encode/decode, unbind the camera and release every codec, thread and executor. */
    fun stop() {
        if (!running) return
        running = false
        encoderThread?.interrupt()
        encoderThread = null
        decodeThread?.interrupt()
        decodeThread = null
        try { decoder?.stop() } catch (_: Exception) { }
        try { decoder?.release() } catch (_: Exception) { }
        decoder = null
        decoderReady = false
        try { encoder?.stop() } catch (_: Exception) { }
        try { encoder?.release() } catch (_: Exception) { }
        encoder = null
        encoderSurface?.let { s ->
            runCatching { s.release() }
        }
        encoderSurface = null
        // CameraX stays bound to the activity lifecycle unless unbound explicitly; do it so the
        // camera is released and the encoder surface no longer receives frames after a call.
        cameraProvider?.let { provider ->
            runCatching { provider.unbindAll() }
        }
        cameraProvider = null
        surfaceProviderExecutor?.let { e ->
            runCatching { e.shutdownNow() }
        }
        surfaceProviderExecutor = null
        decodeQueue.clear()
        synchronized(pendingLock) { pendingFrames.clear() }
        sendFrame = null
        Log.i(TAG, "Video engine stopped")
    }
}
