package com.bitchat.android.calls

import android.Manifest
import android.content.pm.PackageManager
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.eventpulse.mesh.R
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bitchat.android.calls.CallManager.CallUiState
import com.bitchat.android.ui.theme.LocalBitchatPalette

/**
 * Full-screen overlay for the call lifecycle. Rendered above the chat UI; shows nothing while
 * [CallManager.state] is [CallUiState.Idle].
 *
 * - Dialing: peer avatar, "calling…" pulse, red end button.
 * - RingingIn: peer avatar, accept (green) + decline (red); permissions requested on accept.
 * - Active: remote video surface (video calls), mute toggle, duration timer, end button.
 */
@Composable
fun CallOverlay(modifier: Modifier = Modifier) {
    val state by CallManager.state.collectAsState()
    AnimatedVisibility(
        visible = state !is CallUiState.Idle,
        modifier = modifier,
        enter = fadeIn(tween(220)),
        exit = fadeOut(tween(220))
    ) {
        when (state) {
            is CallUiState.Idle -> Unit
            is CallUiState.Dialing -> DialingScreen(state as CallUiState.Dialing)
            is CallUiState.RingingIn -> RingingInScreen(state as CallUiState.RingingIn)
            is CallUiState.Active -> ActiveCallScreen(state as CallUiState.Active)
        }
    }
}

@Composable
private fun CallBackdrop(content: @Composable () -> Unit) {
    val palette = LocalBitchatPalette.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF14151A),
                        Color(0xFF1C1D24)
                    )
                )
            )
    ) {
        content()
    }
}

@Composable
private fun PeerAvatar(nickname: String, size: androidx.compose.ui.unit.Dp, pulsing: Boolean) {
    val palette = LocalBitchatPalette.current
    val initial = nickname.trim().firstOrNull()?.uppercase() ?: "?"
    val transition = rememberInfiniteTransition(label = "avatarPulse")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "avatarPulseScale"
    )
    val scale by animateFloatAsState(
        targetValue = if (pulsing) pulse else 1f,
        label = "avatarScale"
    )
    Box(
        modifier = Modifier
            .size(size)
            .scale(scale)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    listOf(
                        palette.accentOrange.copy(alpha = 0.95f),
                        palette.accentOrange.copy(alpha = 0.55f)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initial,
            color = Color.White,
            fontSize = (size.value * 0.42f).sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun CallTitle(nickname: String, subtitle: String) {
    Column(
        modifier = Modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = nickname,
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = subtitle,
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 15.sp
        )
    }
}

@Composable
private fun RoundActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    tint: Color,
    container: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Transparent)
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(68.dp)
                .clip(CircleShape)
                .background(container),
            contentAlignment = Alignment.Center
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(30.dp))
            } else {
                Text(text = label, color = tint, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(text = label, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
    }
}

@Composable
private fun DialingScreen(state: CallUiState.Dialing) {
    CallBackdrop {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(Modifier.height(8.dp))
            PeerAvatar(nickname = state.nickname, size = 148.dp, pulsing = true)
            CallTitle(nickname = state.nickname, subtitle = stringResource(R.string.call_calling))
            RoundActionButton(
                label = stringResource(R.string.call_end),
                icon = Icons.Filled.CallEnd,
                tint = Color.White,
                container = MaterialTheme.colorScheme.error,
                onClick = { CallManager.endCall() }
            )
        }
    }
}

@Composable
private fun RingingInScreen(state: CallUiState.RingingIn) {
    val context = LocalContext.current
    val needsCamera = state.video
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val audioOk = results[Manifest.permission.RECORD_AUDIO] == true
        val cameraOk = !needsCamera || results[Manifest.permission.CAMERA] == true
        if (audioOk && cameraOk) {
            CallManager.acceptCall()
        } else {
            CallManager.rejectCall()
        }
    }
    fun acceptWithPermissions() {
        val missing = buildList {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.RECORD_AUDIO)
            if (needsCamera && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.CAMERA)
        }
        if (missing.isEmpty()) {
            CallManager.acceptCall()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    CallBackdrop {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(Modifier.height(8.dp))
            PeerAvatar(nickname = state.nickname, size = 148.dp, pulsing = true)
            CallTitle(
                nickname = state.nickname,
                subtitle = stringResource(
                    if (state.video) R.string.call_incoming_video else R.string.call_incoming_voice
                )
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RoundActionButton(
                    label = stringResource(R.string.call_decline),
                    icon = Icons.Filled.CallEnd,
                    tint = Color.White,
                    container = MaterialTheme.colorScheme.error,
                    onClick = { CallManager.rejectCall() }
                )
                RoundActionButton(
                    label = stringResource(R.string.call_accept),
                    icon = Icons.Filled.Call,
                    tint = Color.White,
                    container = Color(0xFF2E7D32),
                    onClick = ::acceptWithPermissions
                )
            }
        }
    }
}

@Composable
private fun ActiveCallScreen(state: CallUiState.Active) {
    val palette = LocalBitchatPalette.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var muted by remember { mutableStateOf(CallManager.isMuted) }

    // Keep CameraX bound to this lifecycle owner for the duration of the video call.
    LaunchedEffect(state.video) {
        CallManager.attachVideoLifecycleOwner(lifecycleOwner)
    }
    DisposableEffect(Unit) {
        onDispose {
            CallManager.attachVideoLifecycleOwner(null)
        }
    }

    CallBackdrop {
        Box(modifier = Modifier.fillMaxSize()) {
            // Remote video surface (video calls) with an avatar fallback behind it.
            if (state.video) {
                AndroidView(
                    factory = { ctx ->
                        TextureView(ctx).apply {
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(surfaceTexture: android.graphics.SurfaceTexture, width: Int, height: Int) {
                                    CallManager.attachRemoteSurface(Surface(surfaceTexture))
                                }
                                override fun onSurfaceTextureSizeChanged(surfaceTexture: android.graphics.SurfaceTexture, width: Int, height: Int) {}
                                override fun onSurfaceTextureDestroyed(surfaceTexture: android.graphics.SurfaceTexture): Boolean = true
                                override fun onSurfaceTextureUpdated(surfaceTexture: android.graphics.SurfaceTexture) {}
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
            // Fallback avatar shown until remote frames render (sits behind the TextureView).
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                PeerAvatar(nickname = state.nickname, size = 120.dp, pulsing = false)
            }

            // Top bar: call type + duration.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(top = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = state.nickname,
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (state.video) {
                        Icon(
                            imageVector = Icons.Filled.Videocam,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = Color.White.copy(alpha = 0.65f)
                        )
                    }
                    CallDurationText(startedAtMs = state.startedAtMs)
                }
            }

            // Bottom controls.
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(40.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (state.video) {
                    // Mute doubles as the speaker-label free control; keep both mute + end.
                    RoundActionButton(
                        label = stringResource(if (muted) R.string.call_unmute else R.string.call_mute),
                        icon = if (muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                        tint = Color.White,
                        container = if (muted) palette.accentOrange else Color(0xFF3A3D46),
                        onClick = {
                            muted = !muted
                            CallManager.setMuted(muted)
                        }
                    )
                }
                RoundActionButton(
                    label = stringResource(R.string.call_end),
                    icon = Icons.Filled.CallEnd,
                    tint = Color.White,
                    container = MaterialTheme.colorScheme.error,
                    onClick = { CallManager.endCall() }
                )
            }
        }
    }
}

@Composable
private fun CallDurationText(startedAtMs: Long) {
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(startedAtMs) {
        while (true) {
            elapsed = System.currentTimeMillis() - startedAtMs
            kotlinx.coroutines.delay(1000)
        }
    }
    val minutes = elapsed / 60_000
    val seconds = (elapsed % 60_000) / 1000
    Text(
        text = "%02d:%02d".format(minutes, seconds),
        color = Color.White.copy(alpha = 0.65f),
        fontSize = 14.sp
    )
}
