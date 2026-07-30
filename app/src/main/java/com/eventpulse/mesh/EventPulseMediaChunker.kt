package com.eventpulse.mesh

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Feature C: Compressed Offline Media & Document Chunking Engine
 *
 * Splits media/docs into max-512-byte fragments with sequence headers
 * and reassembles on receiving devices. Includes auto-compression:
 * - Images: downscaled + WebP with bitmap pool to reduce GC pressure
 * - Audio: already AAC from VoiceRecorder
 * - Documents: raw chunked
 *
 * PERF: Bitmap LruCache avoids re-decoding recently compressed images.
 * PERF: Concurrent chunk sending via sendChunksParallel().
 * PERF: Stale reassembly buffers auto-evicted after 60 seconds.
 */
object EventPulseMediaChunker {

    private const val TAG = "MediaChunker"
    private const val CHUNK_SIZE = 480
    private const val BUFFER_TIMEOUT_MS = 60_000L

    /** In-progress reassembly buffers */
    private val reassemblyBuffers = ConcurrentHashMap<String, ReassemblyBuffer>()
    private val bufferTimestamps = ConcurrentHashMap<String, Long>()

    /** Bitmap cache: avoid re-decoding recently compressed images (4MB max). */
    private val bitmapCache = LruCache<String, Bitmap>(4 * 1024 * 1024) // 4KB key, 4MB total

    private var cleanupJob: Job? = null

    data class ReassemblyBuffer(
        val fileId: String,
        val totalChunks: Int,
        val chunks: MutableMap<Int, ByteArray> = mutableMapOf(),
        val mimeType: String = "",
        val senderPeerId: String = ""
    )

    data class ChunkResult(
        val chunks: List<EventPulsePayload>,
        val fileId: String,
        val totalChunks: Int,
        val mimeType: String
    )

    // ─── Cleanup Scheduler ─────────────────────────────────────────────

    fun startCleanupScheduler(scope: CoroutineScope) {
        cleanupJob?.cancel()
        cleanupJob = scope.launch(Dispatchers.Default) {
            while (true) {
                delay(30_000L)
                evictStaleBuffers()
            }
        }
    }

    fun stopCleanupScheduler() {
        cleanupJob?.cancel()
        cleanupJob = null
    }

    private fun evictStaleBuffers() {
        val cutoff = System.currentTimeMillis() - BUFFER_TIMEOUT_MS
        val staleIds = bufferTimestamps.filter { it.value < cutoff }.keys
        staleIds.forEach { fileId ->
            reassemblyBuffers.remove(fileId)
            bufferTimestamps.remove(fileId)
        }
        if (staleIds.isNotEmpty()) {
            Log.d(TAG, "Evicted ${staleIds.size} stale buffers, ${reassemblyBuffers.size} remaining")
        }
    }

    fun evictBuffer(fileId: String) {
        reassemblyBuffers.remove(fileId)
        bufferTimestamps.remove(fileId)
    }

    fun activeBufferCount(): Int = reassemblyBuffers.size

    // ─── Chunking ──────────────────────────────────────────────────────

    fun chunkBytes(
        data: ByteArray,
        fileId: String,
        mimeType: String,
        sender: String,
        channel: String = "general"
    ): ChunkResult {
        val totalChunks = ((data.size + CHUNK_SIZE - 1) / CHUNK_SIZE).coerceAtLeast(1)
        val chunks = mutableListOf<EventPulsePayload>()

        for (i in 0 until totalChunks) {
            val start = i * CHUNK_SIZE
            val end = minOf(start + CHUNK_SIZE, data.size)
            val chunkData = data.copyOfRange(start, end)
            val base64Data = android.util.Base64.encodeToString(chunkData, android.util.Base64.NO_WRAP)

            val payload = EventPulsePayload(
                msg_id = EventPulsePayload.generateMessageId(sender),
                sender = sender,
                channel = channel,
                type = "MEDIA_CHUNK",
                file_id = fileId,
                chunk_index = i,
                total_chunks = totalChunks,
                mime_type = mimeType,
                timestamp = System.currentTimeMillis() / 1000L,
                data = base64Data
            )
            chunks.add(payload)
        }

        return ChunkResult(chunks = chunks, fileId = fileId, totalChunks = totalChunks, mimeType = mimeType)
    }

    /**
     * Send chunks in parallel batches using coroutines.
     * Each chunk callback fires independently; chunks within a batch are concurrent.
     */
    suspend fun sendChunksParallel(
        result: ChunkResult,
        scope: CoroutineScope,
        sendOne: suspend (EventPulsePayload) -> Unit,
        batchSize: Int = 4
    ) {
        result.chunks.chunked(batchSize).forEach { batch ->
            batch.map { chunk ->
                scope.async(Dispatchers.IO) { sendOne(chunk) }
            }.awaitAll()
        }
    }

    /**
     * Compress image using cached bitmap when available.
     * Uses LruCache to avoid re-decoding same image within short timeframe.
     */
    fun compressImage(inputPath: String, maxDim: Int = 512, quality: Int = 80): ByteArray? {
        return try {
            // Check cache first
            val cached = bitmapCache.get(inputPath)
            if (cached != null && !cached.isRecycled) {
                val stream = ByteArrayOutputStream()
                cached.compress(Bitmap.CompressFormat.WEBP, quality, stream)
                return stream.toByteArray()
            }

            val bitmap = BitmapFactory.decodeFile(inputPath) ?: return null
            val w = bitmap.width
            val h = bitmap.height

            // Fast path: if already small enough, compress directly
            if (maxOf(w, h) <= maxDim) {
                val stream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.WEBP, quality, stream)
                bitmapCache.put(inputPath, bitmap)
                return stream.toByteArray()
            }

            val scale = (maxOf(w, h).toFloat() / maxDim.toFloat()).coerceAtLeast(1f)
            val newW = (w / scale).toInt().coerceAtLeast(1)
            val newH = (h / scale).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(bitmap, newW, newH, true)
            val stream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.WEBP, quality, stream)
            val bytes = stream.toByteArray()

            // Cache the scaled bitmap (not the original)
            bitmapCache.put(inputPath, scaled)
            if (scaled !== bitmap) bitmap.recycle()
            bytes
        } catch (e: Exception) {
            Log.w(TAG, "Image compression failed: ${e.message}")
            null
        }
    }

    /** Clear the bitmap cache to free memory. */
    fun evictBitmapCache() {
        bitmapCache.evictAll()
    }

    fun readFileToBytes(context: Context, uri: Uri): ByteArray? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read file: ${e.message}")
            null
        }
    }

    // ─── Reassembly ────────────────────────────────────────────────────

    fun acceptChunk(payload: EventPulsePayload, context: Context, senderPeerId: String): String? {
        val fileId = payload.file_id
        if (fileId.isEmpty()) return null

        val buffer = reassemblyBuffers.getOrPut(fileId) {
            ReassemblyBuffer(fileId = fileId, totalChunks = payload.total_chunks, mimeType = payload.mime_type, senderPeerId = senderPeerId)
        }
        bufferTimestamps[fileId] = System.currentTimeMillis()

        val chunkBytes = try {
            android.util.Base64.decode(payload.data, android.util.Base64.DEFAULT)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode chunk data for $fileId")
            return null
        }
        buffer.chunks[payload.chunk_index] = chunkBytes

        if (buffer.chunks.size >= buffer.totalChunks) {
            bufferTimestamps.remove(fileId)
            return reassembleFile(fileId, context)
        }
        return null
    }

    private fun reassembleFile(fileId: String, context: Context): String? {
        val buffer = reassemblyBuffers[fileId] ?: return null

        val allBytes = ByteArrayOutputStream()
        for (i in 0 until buffer.totalChunks) {
            val chunk = buffer.chunks[i] ?: return null
            allBytes.write(chunk)
        }

        val fullData = allBytes.toByteArray()
        val extension = guessExtension(buffer.mimeType)
        val dir = File(context.filesDir, "media/received").apply { mkdirs() }
        val outFile = File(dir, "${fileId}_${System.currentTimeMillis()}.$extension")

        try {
            FileOutputStream(outFile).use { it.write(fullData) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write reassembled file: ${e.message}")
            return null
        }

        reassemblyBuffers.remove(fileId)
        return outFile.absolutePath
    }

    private fun guessExtension(mimeType: String): String = when {
        mimeType.contains("webp") -> "webp"
        mimeType.contains("png") -> "png"
        mimeType.contains("jpeg") || mimeType.contains("jpg") -> "jpg"
        mimeType.contains("aac") || mimeType.contains("m4a") -> "m4a"
        mimeType.contains("pdf") -> "pdf"
        mimeType.contains("text") -> "txt"
        mimeType.contains("document") || mimeType.contains("docx") -> "docx"
        else -> "bin"
    }

    fun generateFileId(prefix: String = "file"): String = "${prefix}_${System.currentTimeMillis()}"

    fun clear() {
        reassemblyBuffers.clear()
        bufferTimestamps.clear()
        bitmapCache.evictAll()
    }
}
