package com.bitchat.android.ui.media

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoCamera
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.bitchat.android.features.media.ImageUtils
import com.bitchat.android.ui.ComposerActionSurface
import com.bitchat.android.ui.ComposerIconSize
import java.io.File
import android.util.Log

private const val TAG = "ImagePickerButton"

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImagePickerButton(
    modifier: Modifier = Modifier,
    onImageReady: (String) -> Unit,
    /**
     * Longest-side bound for the outgoing image. BLE-only conversations pass a smaller bound
     * (see ImageUtils.BLE_ONLY_IMAGE_MAX_DIM) so the payload rides the slow radio faster.
     */
    maxImageDim: Int = ImageUtils.DEFAULT_IMAGE_MAX_DIM,
    /**
     * JPEG quality for the outgoing image. BLE-only conversations pass a lower quality
     * (see ImageUtils.BLE_ONLY_IMAGE_QUALITY) to shrink the payload further.
     */
    maxImageQuality: Int = ImageUtils.DEFAULT_IMAGE_QUALITY
) {
    val context = LocalContext.current
    var capturedImagePath by remember { mutableStateOf<String?>(null) }
    
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            val outPath = ImageUtils.downscaleAndSaveToAppFiles(context, uri, maxDim = maxImageDim, quality = maxImageQuality)
            if (!outPath.isNullOrBlank()) onImageReady(outPath)
        }
    }
    
    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val path = capturedImagePath
        if (success && !path.isNullOrBlank()) {
            // Downscale + correct orientation, then send.
            val outPath = com.bitchat.android.features.media.ImageUtils.downscalePathAndSaveToAppFiles(
                context,
                path,
                maxDim = maxImageDim,
                quality = maxImageQuality
            )
            if (!outPath.isNullOrBlank()) {
                onImageReady(outPath)
                // Only the original camera capture can be cleaned up here: the send is
                // asynchronous, so never delete the file we just handed to it.
                if (outPath != path) runCatching { File(path).delete() }
            } else {
                // Decode/compress failed for the captured photo. Fall back to sending the
                // untouched camera file: it is a valid JPEG on disk, and the size gate in
                // MediaSendingManager will surface a clear message if it is too large.
                // The async sender owns that file now, so do NOT delete it here.
                Log.w(TAG, "Camera capture downscale failed; sending original capture")
                onImageReady(path)
            }
        } else {
            // Cleanup on cancel/failure
            path?.let { runCatching { File(it).delete() } }
        }
        capturedImagePath = null
    }

    fun startCameraCapture() {
        try {
            val dir = File(context.filesDir, "images/outgoing").apply { mkdirs() }
            val file = File(dir, "camera_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(
                context,
                context.packageName + ".fileprovider",
                file
            )
            capturedImagePath = file.absolutePath
            takePictureLauncher.launch(uri)
        } catch (e: Exception) {
            Log.e(TAG, "Camera capture failed", e)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCameraCapture()
        }
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    // Shares the composer's button treatment so camera, microphone and send read as one set.
    // Click: take in-app camera photo. Long-click: pick from gallery.
    ComposerActionSurface(
        isActive = false,
        isPressed = isPressed,
        modifier = modifier.combinedClickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    startCameraCapture()
                } else {
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                }
            },
            onLongClick = { imagePicker.launch("image/*") }
        )
    ) { tint ->
        Icon(
            imageVector = Icons.Filled.PhotoCamera,
            contentDescription = stringResource(com.eventpulse.mesh.R.string.pick_image),
            tint = tint,
            modifier = Modifier.size(ComposerIconSize)
        )
    }

    // No custom preview: native camera UI handles confirmation
}
