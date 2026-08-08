package com.bitchat.android.features.voice

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File

/**
 * Re-encodes voice notes to a lower AAC bitrate for BLE-only private chats.
 *
 * Voice notes are recorded once at the default tier (see [VoiceRecorder]) before the target
 * conversation is known; like images, the BLE-only tier is applied at send time. Output is a
 * deterministic per-(input, bitrate) cache file next to the original, so a retry reuses the
 * same transcode instead of burning CPU or duplicating files again.
 *
 * Pipeline mirrors the established synchronous MediaExtractor/MediaCodec pattern used by
 * [AudioWaveformExtractor]: decode AAC -> PCM, re-encode PCM -> AAC-LC at the target bitrate,
 * mux into MP4. Output keeps MIME audio/mp4 so the receiver path is unchanged. Failures
 * return null and the caller falls back to the original file.
 */
object VoiceTranscoder {
    private const val TAG = "VoiceTranscoder"

    /**
     * Consecutive no-progress outer iterations before a transcode aborts. Malformed/truncated
     * input must fail fast (and fall back to the original file) instead of hanging the send
     * coroutine. Outer iterations are fast (timeout-0 dequeues), so this is reached only on a
     * genuinely dead pipeline.
     */
    private const val MAX_STALLED_ITERATIONS = 10_000

    /**
     * Cap for the encoder-input feed loop, which uses a 10 ms timeout per attempt. Waiting for
     * an encoder slot legitimately takes up to one AAC frame (~64 ms), so this must be generous;
     * ~1_000 attempts x 10 ms bounds a broken codec to roughly 10 s before falling back.
     */
    private const val MAX_FEED_STALLS = 1_000

    /**
     * Returns the path to a lower-bitrate copy of [inputPath], transcoding on first use and
     * caching the result (keyed by input path + [targetBitrate]). Returns null on failure.
     */
    fun transcodeToLowerBitrate(inputPath: String, targetBitrate: Int): String? {
        val input = File(inputPath)
        if (!input.exists() || targetBitrate <= 0) return null
        val output = tierPathFor(inputPath, targetBitrate)
        if (output.exists() && output.length() > 0L) return output.absolutePath

        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerTrack = -1
        var muxerStarted = false
        return try {
            extractor = MediaExtractor().apply { setDataSource(inputPath) }
            val trackIndex = (0 until extractor.trackCount).firstOrNull { idx ->
                (extractor.getTrackFormat(idx).getString(MediaFormat.KEY_MIME) ?: "")
                    .startsWith("audio/")
            } ?: return null
            extractor.selectTrack(trackIndex)
            val srcFormat = extractor.getTrackFormat(trackIndex)
            val srcMime = srcFormat.getString(MediaFormat.KEY_MIME) ?: return null
            val sampleRate = srcFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = srcFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            decoder = MediaCodec.createDecoderByType(srcMime)
            decoder.configure(srcFormat, null, null, 0)
            decoder.start()

            val encFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            }
            encoder = MediaCodec.createEncoderByType("audio/mp4a-latm")
            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val decInfo = MediaCodec.BufferInfo()
            val encInfo = MediaCodec.BufferInfo()
            var sawInputEOS = false
            var sawOutputEOS = false

            /**
             * Encoder -> muxer. Starts the muxer on the first output buffer (codec-config OR
             * sample — AAC encoders normally emit config first, but the contract does not
             * guarantee it, and a data-first encoder must not leave an empty output file that
             * would otherwise be sent as a successful voice note). Returns the number of
             * buffers drained so the caller can track progress.
             */
            fun drainEncoder(): Int {
                var drained = 0
                var index = encoder!!.dequeueOutputBuffer(encInfo, 0)
                while (index >= 0) {
                    drained++
                    val buf = encoder!!.getOutputBuffer(index)
                    val isConfig = encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    val isEos = encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (!muxerStarted && (!isEos || encInfo.size > 0)) {
                        muxerTrack = muxer!!.addTrack(encoder!!.outputFormat)
                        muxer!!.start()
                        muxerStarted = true
                    }
                    // The codec-config buffer is not written to the muxer; MediaMuxer derives
                    // it from the track format added above.
                    if (muxerStarted && encInfo.size > 0 && buf != null && !isConfig) {
                        buf.position(encInfo.offset)
                        buf.limit(encInfo.offset + encInfo.size)
                        muxer!!.writeSampleData(muxerTrack, buf, encInfo)
                    }
                    if (isEos) sawOutputEOS = true
                    encoder!!.releaseOutputBuffer(index, false)
                    index = encoder!!.dequeueOutputBuffer(encInfo, 0)
                }
                return drained
            }

            var stalledIterations = 0
            while (!sawOutputEOS) {
                var madeProgress = false

                // Feed the decoder from the source file (only until EOS is queued). A transient
                // null input buffer for a valid index must NOT be treated as end-of-stream —
                // that would truncate the note; skip the feed and retry next round instead.
                if (!sawInputEOS) {
                    val inIndex = decoder!!.dequeueInputBuffer(0)
                    if (inIndex >= 0) {
                        val inBuf = decoder!!.getInputBuffer(inIndex)
                        if (inBuf != null) {
                            val size = extractor.readSampleData(inBuf, 0)
                            if (size < 0) {
                                decoder!!.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                sawInputEOS = true
                            } else {
                                decoder!!.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                            madeProgress = true
                        }
                    }
                }

                // Decoder -> encoder. A decoder EOS buffer must never be dropped when the
                // encoder input is full, or the encoder never sees EOS and the loop hangs.
                var decIndex = decoder!!.dequeueOutputBuffer(decInfo, 0)
                while (decIndex >= 0) {
                    madeProgress = true
                    val decBuf = decoder!!.getOutputBuffer(decIndex)
                    var fed = false
                    var feedStalls = 0
                    while (!fed) {
                        // Positive timeout: a full encoder input legitimately waits up to one
                        // AAC frame, and the timeout bounds the spin while drainEncoder frees slots.
                        val encInIndex = encoder!!.dequeueInputBuffer(10_000)
                        if (encInIndex >= 0) {
                            val encBuf = encoder!!.getInputBuffer(encInIndex)
                            if (encBuf != null && decBuf != null && decInfo.size > 0) {
                                encBuf.clear()
                                decBuf.position(decInfo.offset)
                                decBuf.limit(decInfo.offset + decInfo.size)
                                encBuf.put(decBuf)
                                encoder!!.queueInputBuffer(encInIndex, 0, decInfo.size, decInfo.presentationTimeUs, decInfo.flags)
                            } else {
                                // Zero-length buffer (EOS marker): forward flags/timestamp only.
                                encoder!!.queueInputBuffer(encInIndex, 0, 0, decInfo.presentationTimeUs, decInfo.flags)
                            }
                            fed = true
                        } else {
                            drainEncoder()
                            if (sawOutputEOS) break
                            if (++feedStalls > MAX_FEED_STALLS) {
                                throw IllegalStateException("transcode stalled while feeding encoder")
                            }
                        }
                    }
                    decoder!!.releaseOutputBuffer(decIndex, false)
                    decIndex = decoder!!.dequeueOutputBuffer(decInfo, 0)
                }

                madeProgress = drainEncoder() > 0 || madeProgress

                if (!madeProgress && ++stalledIterations > MAX_STALLED_ITERATIONS) {
                    throw IllegalStateException("transcode stalled on $inputPath")
                }
            }
            output.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "Transcode failed ($inputPath @ ${targetBitrate}b/s): ${e.message}")
            output.delete()
            null
        } finally {
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor?.release() }
        }
    }

    /** Deterministic cache path: same directory, name suffixed with the tier bitrate. */
    private fun tierPathFor(inputPath: String, bitrate: Int): File {
        val src = File(inputPath)
        val base = src.name.substringBeforeLast('.')
        return File(src.parentFile ?: File("."), "${base}_ble${bitrate / 1000}.m4a")
    }
}
