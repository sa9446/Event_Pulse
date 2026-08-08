package com.bitchat.android.features.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

object ImageUtils {

    /** Longest-side bound for outgoing images on standard transports (Wi-Fi/Nostr). */
    const val DEFAULT_IMAGE_MAX_DIM = 512

    /**
     * Longest-side bound for outgoing images to BLE-only peers. The smaller payload rides
     * the slow BLE radio faster and is far less likely to fail mid-transfer.
     */
    const val BLE_ONLY_IMAGE_MAX_DIM = 384

    /** JPEG quality for outgoing images on standard transports (Wi-Fi/Nostr). */
    const val DEFAULT_IMAGE_QUALITY = 85

    /**
     * JPEG quality for outgoing images to BLE-only peers. Lower quality shrinks the payload
     * further (alongside the 384px dimension bound) so it rides the slow BLE radio faster and
     * is less likely to fail mid-transfer.
     */
    const val BLE_ONLY_IMAGE_QUALITY = 75

    /**
     * Compute a power-of-two sample size that bounds the decoded bitmap to roughly
     * [maxDim] on its longest side. Reading bounds first (inJustDecodeBounds) means a
     * 48 MP camera photo is never fully decoded into memory just to be shrunk.
     */
    private fun sampleSizeFor(width: Int, height: Int, maxDim: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        var w = width
        var h = height
        while (maxOf(w, h) / 2 >= maxDim && sample < 64) {
            sample *= 2
            w /= 2
            h /= 2
        }
        return sample
    }

    private fun decodeSampledFromPath(path: String, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxDim)
        }
        return BitmapFactory.decodeFile(path, opts)
    }

    fun downscaleAndSaveToAppFiles(context: Context, uri: Uri, maxDim: Int = DEFAULT_IMAGE_MAX_DIM, quality: Int = DEFAULT_IMAGE_QUALITY): String? {
        return try {
            val resolver = context.contentResolver
            val exifRotation = resolver.openInputStream(uri)?.use { getRotationDegreesFromExif(it) } ?: 0

            // Bounds pass on its own stream (decodeStream consumes whatever it reads).
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            // Sampled decode on a fresh stream keeps memory bounded for full-resolution
            // gallery photos and never reads a partially-consumed stream.
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxDim)
            }
            val original = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null

            val oriented = if (exifRotation != 0) rotateBitmap(original, exifRotation) else original

            val w = oriented.width
            val h = oriented.height
            val scale = (maxOf(w, h).toFloat() / maxDim.toFloat()).coerceAtLeast(1f)
            val newW = (w / scale).toInt().coerceAtLeast(1)
            val newH = (h / scale).toInt().coerceAtLeast(1)
            val scaled = if (scale > 1f) Bitmap.createScaledBitmap(oriented, newW, newH, true) else oriented
            val dir = File(context.filesDir, "images/outgoing").apply { mkdirs() }
            val outFile = File(dir, "img_${System.currentTimeMillis()}.jpg")
            FileOutputStream(outFile).use { fos ->
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, fos)
            }
            try { if (oriented !== original) original.recycle() } catch (_: Exception) {}
            try { if (scaled !== oriented) oriented.recycle() } catch (_: Exception) {}
            outFile.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    fun downscalePathAndSaveToAppFiles(context: Context, path: String, maxDim: Int = DEFAULT_IMAGE_MAX_DIM, quality: Int = DEFAULT_IMAGE_QUALITY): String? {
        return try {
            val original = decodeSampledFromPath(path, maxDim) ?: return null
            val exifRotation = getRotationDegreesFromExif(path)
            val oriented = if (exifRotation != 0) rotateBitmap(original, exifRotation) else original

            val w = oriented.width
            val h = oriented.height
            val scale = (maxOf(w, h).toFloat() / maxDim.toFloat()).coerceAtLeast(1f)
            val newW = (w / scale).toInt().coerceAtLeast(1)
            val newH = (h / scale).toInt().coerceAtLeast(1)
            val scaled = if (scale > 1f) Bitmap.createScaledBitmap(oriented, newW, newH, true) else oriented
            val dir = File(context.filesDir, "images/outgoing").apply { mkdirs() }
            val outFile = File(dir, "img_${System.currentTimeMillis()}.jpg")
            FileOutputStream(outFile).use { fos ->
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, fos)
            }
            try { if (oriented !== original) original.recycle() } catch (_: Exception) {}
            try { if (scaled !== oriented) oriented.recycle() } catch (_: Exception) {}
            outFile.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    fun loadBitmapWithExifOrientation(path: String): Bitmap? {
        return try {
            val base = BitmapFactory.decodeFile(path) ?: return null
            val rotation = getRotationDegreesFromExif(path)
            if (rotation != 0) rotateBitmap(base, rotation) else base
        } catch (_: Exception) {
            null
        }
    }

    private fun rotateBitmap(src: Bitmap, degrees: Int): Bitmap {
        return try {
            val m = Matrix()
            m.postRotate(degrees.toFloat())
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true).also {
                try { src.recycle() } catch (_: Exception) {}
            }
        } catch (_: Exception) {
            src
        }
    }

    private fun getRotationDegreesFromExif(path: String): Int = try {
        val exif = ExifInterface(path)
        orientationToDegrees(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL))
    } catch (_: Exception) { 0 }

    private fun getRotationDegreesFromExif(stream: InputStream): Int = try {
        val exif = ExifInterface(stream)
        orientationToDegrees(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL))
    } catch (_: Exception) { 0 }

    private fun orientationToDegrees(orientation: Int): Int = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        ExifInterface.ORIENTATION_TRANSPOSE -> 90
        ExifInterface.ORIENTATION_TRANSVERSE -> 270
        else -> 0
    }
}
