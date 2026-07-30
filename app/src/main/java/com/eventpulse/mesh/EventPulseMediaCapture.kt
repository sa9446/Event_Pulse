package com.eventpulse.mesh

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/**
 * Feature B: In-App Camera/Audio Capture & Instant Mesh Transmission
 *
 * Provides Compose UI components for:
 * - One-tap photo capture (auto-compressed + broadcast) with CAMERA permission handling
 * - One-tap voice note recording (auto-compressed + broadcast) with RECORD_AUDIO permission handling
 *
 * These components integrate into the existing chat input sheet.
 */

// ─── Photo Capture Button ──────────────────────────────────────────────

/**
 * Camera capture button that launches the system camera and returns the
 * captured photo path for instant mesh broadcast.
 * Requests CAMERA runtime permission before launching.
 */
@Composable
fun EventPulseCameraCaptureButton(
    onPhotoCaptured: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var photoUri by remember { mutableStateOf<Uri?>(null) }

    /** Track whether permission has been granted this session */
    var permissionGranted by remember { mutableStateOf(
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
    ) }

    // Temporary URI for the camera output — defined first
    val tempPhotoUri = remember {
        val dir = File(context.filesDir, "camera/temp").apply { mkdirs() }
        val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    // Camera launcher — defined second (referenced by permission launcher below)
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && photoUri != null) {
            val savedPath = saveCameraPhoto(context, photoUri!!)
            if (savedPath != null) {
                onPhotoCaptured(savedPath)
            }
        }
    }

    // Permission launcher — defined last (references tempPhotoUri and cameraLauncher)
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        permissionGranted = isGranted
        if (isGranted) {
            photoUri = tempPhotoUri
            cameraLauncher.launch(tempPhotoUri)
        } else {
            Toast.makeText(context, "Camera permission is required to take photos", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.clickable {
            if (permissionGranted) {
                photoUri = tempPhotoUri
                cameraLauncher.launch(tempPhotoUri)
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
            modifier = Modifier.size(48.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Filled.CameraAlt,
                    contentDescription = "Take photo",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text("Photo", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Save camera photo from URI to app's file directory for mesh sending.
 */
private fun saveCameraPhoto(context: Context, uri: Uri): String? {
    return try {
        val dir = File(context.filesDir, "camera/outgoing").apply { mkdirs() }
        val file = File(dir, "photo_${System.currentTimeMillis()}.jpg")
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        file.absolutePath
    } catch (e: Exception) {
        Log.w("CameraCapture", "Failed to save photo: ${e.message}")
        null
    }
}

// ─── Voice Note Quick Record Button ────────────────────────────────────

/**
 * Quick voice note recording button with record/stop/send flow.
 * Requests RECORD_AUDIO runtime permission before recording.
 */
@Composable
fun EventPulseVoiceCaptureButton(
    onVoiceNoteReady: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isRecording by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var outputFile by remember { mutableStateOf<File?>(null) }
    var elapsedMs by remember { mutableStateOf(0L) }

    /** Track whether audio permission has been granted this session */
    var audioPermissionGranted by remember { mutableStateOf(
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    ) }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        audioPermissionGranted = isGranted
        if (isGranted) {
            startVoiceRecording(context) { rec, file ->
                recorder = rec
                outputFile = file
                isRecording = true
            }
        } else {
            Toast.makeText(context, "Audio recording permission is required for voice notes", Toast.LENGTH_SHORT).show()
        }
    }

    // Timer effect during recording
    LaunchedEffect(isRecording) {
        if (isRecording) {
            val startTime = System.currentTimeMillis()
            while (isRecording) {
                elapsedMs = System.currentTimeMillis() - startTime
                kotlinx.coroutines.delay(100)
            }
        }
    }

    val formatTime: (Long) -> String = { ms ->
        val secs = (ms / 1000).toInt()
        String.format("%02d:%02d", secs / 60, secs % 60)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.clickable(enabled = !isRecording) {
            if (!isRecording) {
                if (audioPermissionGranted) {
                    startVoiceRecording(context) { rec, file ->
                        recorder = rec
                        outputFile = file
                        isRecording = true
                    }
                } else {
                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }
    ) {
        Surface(
            shape = CircleShape,
            color = if (isRecording) MaterialTheme.colorScheme.error.copy(alpha = 0.2f)
                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
            modifier = Modifier.size(48.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (isRecording) {
                    Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = "Stop recording",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(22.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Mic,
                        contentDescription = "Record voice note",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        if (isRecording) {
            Text(
                text = formatTime(elapsedMs),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error
            )
        } else {
            Spacer(Modifier.height(2.dp))
            Text("Voice", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    // Stop and send button when recording
    if (isRecording) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                onClick = {
                    isRecording = false
                    try { recorder?.apply { stop(); release() } } catch (_: Exception) {}
                    recorder = null
                    outputFile?.let { file ->
                        if (file.exists() && file.length() > 0) {
                            onVoiceNoteReady(file.absolutePath)
                        }
                    }
                },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.error
            ) {
                Text(
                    "Stop & Send",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}

/**
 * Helper to start voice recording after permission is confirmed.
 */
private fun startVoiceRecording(
    context: Context,
    onStarted: (MediaRecorder, File) -> Unit
) {
    try {
        val dir = File(context.filesDir, "voicenotes/outgoing").apply { mkdirs() }
        val file = File(dir, "voice_${System.currentTimeMillis()}.m4a")
        val rec = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioChannels(1)
            setAudioSamplingRate(16000)
            setAudioEncodingBitRate(20000)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
        onStarted(rec, file)
    } catch (e: Exception) {
        Log.w("VoiceCapture", "Failed to start recording: ${e.message}")
    }
}

// ─── Media Quick-Action Row ───────────────────────────────────────────

/**
 * Row of quick media capture buttons (camera + voice).
 */
@Composable
fun EventPulseMediaCaptureRow(
    onPhotoCaptured: (String) -> Unit,
    onVoiceNoteReady: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        EventPulseCameraCaptureButton(onPhotoCaptured = onPhotoCaptured)
        EventPulseVoiceCaptureButton(onVoiceNoteReady = onVoiceNoteReady)
    }
}
