package com.crdp.rdp.direct

import android.view.KeyEvent
import android.view.Surface
import com.crdp.core.rdp.RdpSessionPort
import com.crdp.core.rdp.engine.AudioPlayback
import com.crdp.core.rdp.engine.CameraRedirect
import com.crdp.core.rdp.engine.ChallengeResponse
import com.crdp.core.rdp.engine.CursorFrame
import com.crdp.core.rdp.engine.DirectEngine
import com.crdp.core.rdp.engine.EngineChallenge
import com.crdp.core.rdp.engine.EngineState
import com.crdp.core.rdp.engine.RdpConnectParams
import com.crdp.core.rdp.engine.RdpEngine
import com.crdp.core.rdp.engine.RenderOptions
import com.crdp.core.rdp.input.KeyAction
import com.crdp.core.rdp.input.KeyEventPayload
import com.crdp.core.rdp.input.PointerEvent
import com.crdp.core.rdp.input.TouchContact
import com.crdp.core.rdp.model.AudioMode
import com.crdp.core.rdp.model.CameraMode
import com.crdp.core.rdp.model.ConnectionProfile
import com.crdp.core.rdp.model.DirectConnectionProfile
import com.crdp.core.rdp.model.SessionState
import com.crdp.core.rdp.engine.AudioQuality as EngineAudioQuality
import com.crdp.core.rdp.model.AudioQuality as ProfileAudioQuality
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * RdpSessionPort for [DirectConnectionProfile]s. Translates profile → engine params,
 * forwards I/O verbatim, and adapts engine state into the legacy SessionState surface.
 *
 * Not @Singleton — each session owns its own engine worker. SessionCoordinator hands out
 * a fresh instance per portFor() call via Provider<>.
 */
class DirectRdpSession @Inject constructor(
    @DirectEngine private val engine: RdpEngine,
    @IoDispatcher private val io: CoroutineDispatcher,
) : RdpSessionPort {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _sessionState = MutableStateFlow<SessionState>(SessionState.Idle)
    override val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()
    override val challenges: SharedFlow<EngineChallenge> = engine.challenges
    override val cursor: StateFlow<CursorFrame?> = engine.cursor

    private var stateJob: Job? = null

    /**
     * Tracks modifier bits we have already reflected to the native layer via synthetic
     * or physical modifier keys. FreeRDP JNI ignores `meta` on `sendKey`, so we emit
     * explicit Ctrl/Alt/Shift/Meta down/up before non-modifier keys when needed.
     */
    private var lastForwardedModifierMeta: Int = 0

    init {
        stateJob = scope.launch {
            engine.state.collect { _sessionState.value = it.toSessionState() }
        }
    }

    override suspend fun connect(profile: ConnectionProfile): Result<Unit> = withContext(io) {
        if (profile !is DirectConnectionProfile) {
            return@withContext Result.failure(IllegalArgumentException("Direct profile required"))
        }
        engine.connect(
            RdpConnectParams(
                host = profile.host,
                port = profile.port,
                username = profile.username,
                domain = profile.domain,
                password = profile.password,
                width = profile.width,
                height = profile.height,
                colorDepth = profile.colorDepth,
                // Profile carries 0 when "use app default" — caller (SessionViewModel) is
                // expected to resolve that to a concrete percent before invoking connect.
                desktopScaleFactor = if (profile.desktopScaleFactor in 100..500) {
                    profile.desktopScaleFactor
                } else {
                    100
                },
                audioPlayback = when (profile.audioMode) {
                    AudioMode.LocalDevice, AudioMode.UseAppDefault -> AudioPlayback.LocalDevice
                    AudioMode.RemoteConsole -> AudioPlayback.RemoteConsole
                    AudioMode.Disabled -> AudioPlayback.Disabled
                },
                microphoneEnabled = profile.microphoneOverride ?: false,
                audioQuality = when (profile.audioQuality) {
                    ProfileAudioQuality.Medium -> EngineAudioQuality.Medium
                    ProfileAudioQuality.High -> EngineAudioQuality.High
                    ProfileAudioQuality.Dynamic, ProfileAudioQuality.UseAppDefault -> EngineAudioQuality.Dynamic
                },
                cameraRedirect = when (profile.cameraMode) {
                    CameraMode.Disabled, CameraMode.UseAppDefault -> CameraRedirect.Disabled
                    CameraMode.BuiltInFront -> CameraRedirect.BuiltInFront
                    CameraMode.BuiltInBack -> CameraRedirect.BuiltInBack
                    CameraMode.External -> CameraRedirect.External
                    CameraMode.Specific -> CameraRedirect.Specific
                },
                cameraDeviceId = profile.cameraDeviceId,
                clipboardSyncEnabled = profile.clipboardSyncOverride ?: true,
            ),
        )
    }

    override suspend fun disconnect() = withContext(io) {
        engine.disconnect()
        stateJob?.cancel()
        scope.cancel()
    }

    override fun attachSurface(surface: Surface, width: Int, height: Int, options: RenderOptions) {
        engine.attachSurface(surface, width, height, options)
    }

    override fun detachSurface() {
        engine.detachSurface()
    }

    override fun resolveChallenge(id: String, response: ChallengeResponse) {
        engine.resolveChallenge(id, response)
    }

    override fun onPointerEvent(event: PointerEvent) {
        engine.sendPointer(
            x = event.x.toInt(),
            y = event.y.toInt(),
            buttons = event.buttons,
            action = event.action,
            wheel = event.wheelDelta.toInt(),
            wheelH = event.wheelDeltaH.toInt(),
        )
    }

    override fun onTouchContacts(contacts: List<TouchContact>): Boolean =
        engine.sendTouchContacts(contacts)

    override fun onKeyEvent(event: KeyEventPayload) {
        val targetMeta = event.metaState and SYNTH_MODIFIER_MASK
        if (isModifierKeyCode(event.keyCode)) {
            sendEngineScanOrKey(event)
            lastForwardedModifierMeta = targetMeta
            return
        }
        if (targetMeta != lastForwardedModifierMeta) {
            reconcileModifiers(lastForwardedModifierMeta, targetMeta)
            lastForwardedModifierMeta = targetMeta
        }
        sendEngineScanOrKey(event)
    }

    /** Prefer Linux HID scan codes from hardware keyboards; fall back to Android keyCode. */
    private fun sendEngineScanOrKey(event: KeyEventPayload) {
        val code = if (event.scanCode != 0) event.scanCode else event.keyCode
        engine.sendKey(scancode = code, action = event.action, meta = event.metaState)
    }

    private fun reconcileModifiers(from: Int, to: Int) {
        val releaseBits = from and to.inv()
        val pressBits = to and from.inv()
        for (i in MODIFIER_ORDER.indices.reversed()) {
            val bit = MODIFIER_ORDER[i].first
            if (releaseBits and bit != 0) {
                engine.sendKey(MODIFIER_ORDER[i].second, KeyAction.Up, 0)
            }
        }
        for ((bit, keyCode) in MODIFIER_ORDER) {
            if (pressBits and bit != 0) {
                engine.sendKey(keyCode, KeyAction.Down, 0)
            }
        }
    }

    private fun EngineState.toSessionState(): SessionState = when (this) {
        EngineState.Idle -> SessionState.Idle
        EngineState.Connecting -> SessionState.Connecting
        EngineState.Disconnecting -> SessionState.Disconnecting
        is EngineState.Connected -> SessionState.Connected(detail, bytesSent, bytesReceived)
        is EngineState.Disconnected -> SessionState.Disconnected(reason)
        is EngineState.Error -> SessionState.Error(message)
    }

    private fun isModifierKeyCode(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_SHIFT_LEFT,
        KeyEvent.KEYCODE_SHIFT_RIGHT,
        KeyEvent.KEYCODE_ALT_LEFT,
        KeyEvent.KEYCODE_ALT_RIGHT,
        KeyEvent.KEYCODE_CTRL_LEFT,
        KeyEvent.KEYCODE_CTRL_RIGHT,
        KeyEvent.KEYCODE_META_LEFT,
        KeyEvent.KEYCODE_META_RIGHT,
        KeyEvent.KEYCODE_SYM,
        -> true
        else -> false
    }

    private companion object {
        private val SYNTH_MODIFIER_MASK: Int =
            KeyEvent.META_SHIFT_ON or
                KeyEvent.META_ALT_ON or
                KeyEvent.META_CTRL_ON or
                KeyEvent.META_META_ON or
                KeyEvent.META_SYM_ON

        /** Press order; release uses reverse order. */
        private val MODIFIER_ORDER: List<Pair<Int, Int>> = listOf(
            KeyEvent.META_CTRL_ON to KeyEvent.KEYCODE_CTRL_LEFT,
            KeyEvent.META_ALT_ON to KeyEvent.KEYCODE_ALT_LEFT,
            KeyEvent.META_SHIFT_ON to KeyEvent.KEYCODE_SHIFT_LEFT,
            KeyEvent.META_META_ON to KeyEvent.KEYCODE_META_LEFT,
            KeyEvent.META_SYM_ON to KeyEvent.KEYCODE_SYM,
        )
    }
}
