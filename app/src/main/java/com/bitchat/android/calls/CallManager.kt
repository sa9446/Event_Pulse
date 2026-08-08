package com.bitchat.android.calls

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.LifecycleOwner
import com.eventpulse.mesh.R
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.model.NoisePayloadType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Process-wide coordinator for real-time calls (voice + video over Wi-Fi Direct / Aware).
 *
 * Control signals (INVITE/ACCEPT/REJECT/END) ride the encrypted Noise payload path via
 * [MeshService.sendCallSignal]; media frames ride CALL_MEDIA packets over the Wi-Fi peer socket
 * via [MeshService.sendCallMedia]. The UI observes [state]; transient notices arrive on [events].
 *
 * Inbound signals/media are delivered here from the mesh delegate (see ChatViewModel), which is
 * the single UI-facing delegate for every transport.
 */
object CallManager {

    private const val TAG = "CallManager"

    sealed interface CallUiState {
        data object Idle : CallUiState

        /** Common shape of every in-progress call (dialing, ringing, active). */
        sealed interface InProgress : CallUiState {
            val peerID: String
            val nickname: String
            val video: Boolean
        }

        data class Dialing(
            override val peerID: String,
            override val nickname: String,
            override val video: Boolean
        ) : InProgress

        data class RingingIn(
            override val peerID: String,
            override val nickname: String,
            override val video: Boolean
        ) : InProgress

        data class Active(
            override val peerID: String,
            override val nickname: String,
            override val video: Boolean,
            val startedAtMs: Long
        ) : InProgress
    }

    private val _state = MutableStateFlow<CallUiState>(CallUiState.Idle)
    val state: StateFlow<CallUiState> = _state

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var mesh: MeshService? = null
    private var appContext: Context? = null
    @Volatile private var activeCallId: String? = null
    @Volatile private var muted = false
    @Volatile private var videoLifecycleOwner: LifecycleOwner? = null
    @Volatile private var videoStarted = false
    private var ringTimeoutJob: Job? = null

    private val audioEngine = CallAudioEngine()
    private val videoEngine = CallVideoEngine()

    fun initialize(context: Context, meshService: MeshService) {
        appContext = context.applicationContext
        mesh = meshService
    }

    // ── UI-facing actions ────────────────────────────────────────────────────

    /**
     * Start an outgoing call. Returns false (and emits a notice) when a call is already active
     * or the peer is not reachable over a Wi-Fi link (calls are not viable over BLE).
     */
    fun startCall(peerID: String, nickname: String, video: Boolean): Boolean {
        val meshService = mesh ?: return false
        if (_state.value !is CallUiState.Idle) {
            emitRes(R.string.call_already_in_progress)
            return false
        }
        if (!meshService.isPeerCallCapable(peerID)) {
            emitRes(R.string.call_no_wifi)
            return false
        }
        val callId = UUID.randomUUID().toString()
        activeCallId = callId
        val callerNickname = try {
            com.bitchat.android.services.NicknameProvider.getNickname(
                appContext ?: return false,
                meshService.myPeerID
            ) ?: ""
        } catch (_: Exception) { "" }
        val invite = com.bitchat.android.model.CallSignal(callId, callerNickname, video)
        meshService.sendCallSignal(peerID, NoisePayloadType.CALL_INVITE, invite.toJson().toByteArray(Charsets.UTF_8))
        _state.value = CallUiState.Dialing(peerID, nickname, video)
        Log.i(TAG, "Outgoing ${if (video) "video" else "voice"} call to ${peerID.take(8)} ($callId)")
        ringTimeoutJob?.cancel()
        ringTimeoutJob = scope.launch {
            delay(CallSignaling.RING_TIMEOUT_MS)
            if (_state.value is CallUiState.Dialing && activeCallId == callId) {
                sendControl(NoisePayloadType.CALL_END)
                teardown(R.string.call_no_answer)
            }
        }
        return true
    }

    /** Accept the ringing incoming call (after permissions are granted by the UI). */
    fun acceptCall() {
        val current = _state.value as? CallUiState.RingingIn ?: return
        sendControl(NoisePayloadType.CALL_ACCEPT)
        becomeActive(current.peerID, current.nickname, current.video)
    }

    /** Decline the ringing incoming call. */
    fun rejectCall() {
        val current = _state.value as? CallUiState.RingingIn ?: return
        sendControl(NoisePayloadType.CALL_REJECT)
        teardown(null)
    }

    /** Hang up an active/dialing call (or dismiss an incoming one). */
    fun endCall() {
        if (_state.value is CallUiState.Idle) return
        sendControl(NoisePayloadType.CALL_END)
        teardown(null)
    }

    /** Toggle microphone muting. */
    fun setMuted(value: Boolean) {
        muted = value
        audioEngine.muted = value
    }

    val isMuted: Boolean get() = muted

    /**
     * The active call screen supplies its lifecycle owner here so CameraX can bind the camera
     * once a video call goes active (or the incoming call is accepted).
     */
    fun attachVideoLifecycleOwner(owner: LifecycleOwner?) {
        videoLifecycleOwner = owner
        val current = _state.value
        if (owner != null && current is CallUiState.Active && current.video && !videoStarted) {
            startVideo()
        }
    }

    /**
     * The active call screen supplies the TextureView surface for remote video rendering.
     */
    fun attachRemoteSurface(surface: android.view.Surface) {
        videoEngine.setRemoteSurface(surface)
    }

    // ── Inbound (called from the mesh delegate) ──────────────────────────────

    /**
     * Inbound call signal, dispatched by the mesh delegate with the NoisePayloadType it rode in on.
     */
    fun handleSignal(signalType: NoisePayloadType, peerID: String, payload: ByteArray) {
        val decoded = CallSignaling.decodeSignal(signalType, payload) ?: return
        val (type, signal) = decoded
        Log.i(TAG, "Call signal ${type.name} from ${peerID.take(8)} (call ${signal.callId.take(8)})")
        when (type) {
            NoisePayloadType.CALL_INVITE -> onInvite(peerID, signal)
            NoisePayloadType.CALL_ACCEPT -> onAccept(signal)
            NoisePayloadType.CALL_REJECT -> onReject(signal)
            NoisePayloadType.CALL_END -> onEnd(signal)
            else -> Unit
        }
    }

    /** Inbound media frame from the mesh delegate (raw CALL_MEDIA payload). */
    fun handleMedia(peerID: String, frame: ByteArray) {
        val current = _state.value as? CallUiState.Active ?: return
        if (current.peerID != peerID) return
        val unwrapped = CallSignaling.unwrapFrame(frame) ?: return
        val (stream, flags, media) = unwrapped
        when (stream) {
            CallSignaling.STREAM_AUDIO -> audioEngine.feed(media)
            CallSignaling.STREAM_VIDEO -> videoEngine.onRemoteFrame(media)
            else -> Unit
        }
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private fun onInvite(peerID: String, signal: com.bitchat.android.model.CallSignal) {
        val current = _state.value
        if (current !is CallUiState.Idle) {
            // Busy: politely decline.
            mesh?.sendCallSignal(peerID, NoisePayloadType.CALL_REJECT, CallSignaling.encodeSimple(signal.callId))
            emitRes(R.string.call_missed, signal.nickname.ifBlank { peerID.take(8) })
            return
        }
        activeCallId = signal.callId
        _state.value = CallUiState.RingingIn(peerID, signal.nickname.ifBlank { peerID }, signal.video)
        ringTimeoutJob?.cancel()
        ringTimeoutJob = scope.launch {
            delay(CallSignaling.INCOMING_RING_TIMEOUT_MS)
            if (_state.value is CallUiState.RingingIn && activeCallId == signal.callId) {
                sendControl(NoisePayloadType.CALL_REJECT)
                teardown(null)
            }
        }
    }

    private fun onAccept(signal: com.bitchat.android.model.CallSignal) {
        val current = _state.value
        if (current is CallUiState.Dialing && activeCallId == signal.callId) {
            becomeActive(current.peerID, current.nickname, current.video)
        }
    }

    private fun onReject(signal: com.bitchat.android.model.CallSignal) {
        if (activeCallId == signal.callId && _state.value is CallUiState.Dialing) {
            teardown(R.string.call_declined)
        }
    }

    private fun onEnd(signal: com.bitchat.android.model.CallSignal) {
        if (activeCallId == signal.callId && _state.value !is CallUiState.Idle) {
            teardown(
                if (_state.value is CallUiState.Active) R.string.call_ended
                else R.string.call_cancelled
            )
        }
    }

    private fun becomeActive(peerID: String, nickname: String, video: Boolean) {
        ringTimeoutJob?.cancel()
        activeCallId = activeCallId ?: UUID.randomUUID().toString()
        _state.value = CallUiState.Active(peerID, nickname, video, System.currentTimeMillis())
        muted = false
        audioEngine.muted = false

        // Audio starts immediately; video waits for a lifecycle owner (UI already attached for
        // outgoing calls; for accepted incoming calls the overlay attaches on the next frame).
        audioEngine.start { pcm ->
            val active = _state.value as? CallUiState.Active ?: return@start
            mesh?.sendCallMedia(active.peerID, CallSignaling.audioFrame(pcm))
        }
        if (video && videoLifecycleOwner != null) {
            startVideo()
        }
        Log.i(TAG, "Call active with ${peerID.take(8)} (video=$video)")
    }

    private fun startVideo() {
        if (videoStarted) return
        val ctx = appContext ?: return
        val owner = videoLifecycleOwner ?: return
        videoStarted = true
        try {
            videoEngine.startLocalVideo(ctx, owner) { nalBytes, isKeyframe ->
                val active = _state.value as? CallUiState.Active ?: return@startLocalVideo
                mesh?.sendCallMedia(active.peerID, CallSignaling.videoFrame(nalBytes, isKeyframe))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Video start failed: ${e.message}")
        }
    }

    private fun sendControl(type: NoisePayloadType) {
        val current = _state.value as? CallUiState.InProgress ?: return
        val callId = activeCallId ?: return
        mesh?.sendCallSignal(current.peerID, type, CallSignaling.encodeSimple(callId))
    }

    private fun teardown(@StringRes reasonRes: Int?) {
        ringTimeoutJob?.cancel()
        ringTimeoutJob = null
        audioEngine.stop()
        videoEngine.stop()
        videoStarted = false
        videoLifecycleOwner = null
        activeCallId = null
        _state.value = CallUiState.Idle
        if (reasonRes != null) {
            emitRes(reasonRes)
        }
        Log.i(TAG, "Call torn down${reasonRes?.let { " (res $it)" } ?: ""}")
    }

    private fun emit(message: String) {
        _events.tryEmit(message)
    }

    private fun emitRes(@StringRes resId: Int, vararg args: Any) {
        val ctx = appContext ?: return
        _events.tryEmit(ctx.getString(resId, *args))
    }
}
