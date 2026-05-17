package com.crdp.feature.session

import android.view.Surface
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.crdp.core.rdp.RdpSessionFactory
import com.crdp.core.rdp.RdpSessionPort
import com.crdp.core.rdp.engine.ChallengeResponse
import com.crdp.core.rdp.engine.EngineChallenge
import com.crdp.core.rdp.engine.RenderOptions
import com.crdp.core.rdp.model.AudioMode
import com.crdp.core.rdp.model.AudioQuality
import com.crdp.core.rdp.model.CameraMode
import com.crdp.core.rdp.model.ConnectionProfile
import com.crdp.core.rdp.model.DirectConnectionProfile
import com.crdp.core.rdp.model.GatewayConnectionProfile
import com.crdp.core.rdp.model.SessionHistoryEntry
import com.crdp.core.rdp.model.SessionState
import com.crdp.core.rdp.repository.ProfileRepository
import com.crdp.core.rdp.repository.SessionHistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.inject.Inject

data class SessionReady(
    val profile: ConnectionProfile,
    val port: RdpSessionPort,
)

/**
 * Surfaces a remote-initiated disconnect (server kick, network drop, etc.) so the
 * UI can show a Cancel/Retry modal instead of leaving the user staring at the last frame.
 */
data class DisconnectInfo(val reason: String)

/**
 * App-level audio defaults pushed in by the UI. Used to resolve a profile's
 * [AudioMode.UseAppDefault] / [AudioQuality.UseAppDefault] / null mic override
 * to a concrete value before [RdpSessionPort.connect].
 */
data class AudioDefaultsHint(
    val mode: AudioMode,
    val microphoneEnabled: Boolean,
    val quality: AudioQuality,
)

/**
 * Camera defaults pushed in by the UI so a profile's [CameraMode.UseAppDefault] can be
 * resolved into a concrete value before connect. cameraEncode is global (no per-profile
 * override) and rides along on the same hint to avoid a third channel.
 */
data class CameraDefaultsHint(
    val mode: CameraMode,
    val deviceId: String?,
    val encode: Boolean,
)

@HiltViewModel
class SessionViewModel @Inject constructor(
    private val repository: ProfileRepository,
    private val sessionHistory: SessionHistoryRepository,
    private val sessionFactory: RdpSessionFactory,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private var connectedAtMs: Long = 0L

    private val profileId: String =
        savedStateHandle.get<String>("profileId")?.let {
            URLDecoder.decode(it, StandardCharsets.UTF_8.name())
        } ?: error("profileId required")

    private val _ready = MutableStateFlow<SessionReady?>(null)
    val ready: StateFlow<SessionReady?> = _ready.asStateFlow()

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    // Stable challenge relay — StateFlow so the UI always sees the latest challenge
    // even if the composable subscribes after the engine emits.
    private val _currentChallenge = MutableStateFlow<EngineChallenge?>(null)
    val currentChallenge: StateFlow<EngineChallenge?> = _currentChallenge.asStateFlow()

    private var activePort: RdpSessionPort? = null
    private var challengeForwardJob: Job? = null
    private var sessionStateWatchJob: Job? = null

    private val _disconnectInfo = MutableStateFlow<DisconnectInfo?>(null)
    val disconnectInfo: StateFlow<DisconnectInfo?> = _disconnectInfo.asStateFlow()

    // Used when autoResolution = true: defer connecting until window size is known.
    private var autoProfile: ConnectionProfile? = null
    private var autoPort: RdpSessionPort? = null

    // Stored so init can use them if onWindowSizeAvailable fires before the profile loads.
    private var storedWindowW = 0
    private var storedWindowH = 0

    // Debounces DisplayControl monitor-layout updates during DeX freeform drag-resize.
    // `LaunchedEffect(windowW, windowH)` in SessionScreen fires on every intermediate
    // pixel size while the user is dragging the window edge — sending a fresh
    // sendMonitorLayout for each one floods the server's RDPEDISP DVC and the Win11
    // host drops the session. Hold the latest requested size in this job and apply
    // it once the gesture settles. Mirrors what mstsc / RDP Manager do internally.
    private var pendingResizeJob: Job? = null
    private val resizeDebounceMs = 250L

    // Effective DPI percent the UI most recently asked for. 0 means "not yet known —
    // wait for the UI to report the first effective scale before opening the
    // connection so we don't open at 100% then immediately reconnect at 150%."
    // The profile's explicit per-connection override (>= 100) always wins.
    private val _effectiveDpiHint = MutableStateFlow(0)

    // True when the running session's scale was filled in from the hint (i.e. the
    // stored profile says "use app default"). False when the user pinned a
    // per-profile scale, which means hint changes don't trigger a reconnect.
    private var currentScaleIsHintTracked: Boolean = false

    // App-default audio settings pushed by the UI before connect. Audio is negotiated
    // at connect-time, so we hold connecting until the UI publishes these once.
    private val _audioDefaultsHint = MutableStateFlow<AudioDefaultsHint?>(null)

    // Camera mirrors audio: rdpecam is negotiated at connect-time, so the hint is captured once.
    // No init-time wait — camera is opt-in and a missing hint just resolves to "Off".
    private val _cameraDefaultsHint = MutableStateFlow<CameraDefaultsHint?>(null)

    // Clipboard defaults from app settings; wait for non-null like audio so the first connect
    // does not race ahead of SessionRoute's LaunchedEffect.
    private val _clipboardDefaultsHint = MutableStateFlow<Boolean?>(null)

    // Printer-share default from app settings. Same wait-for-hint pattern as
    // clipboard: the printer channel is negotiated at connect time, so we hold
    // the first connect until the UI has published the default once.
    private val _printerShareHint = MutableStateFlow<Boolean?>(null)

    // Keyboard layout id (Windows LCID) pushed by the UI. Null = hint not yet
    // delivered; 0 = "let FreeRDP auto-detect". Captured before the first
    // connect via the same wait-for-hint pattern as audio/clipboard.
    private val _keyboardLayoutHint = MutableStateFlow<Int?>(null)

    private fun activatePort(port: RdpSessionPort) {
        activePort = port
        challengeForwardJob?.cancel()
        challengeForwardJob = viewModelScope.launch {
            port.challenges.collect { _currentChallenge.value = it }
        }
        sessionStateWatchJob?.cancel()
        sessionStateWatchJob = viewModelScope.launch {
            port.sessionState.collect { state ->
                if (state is SessionState.Disconnected) {
                    _disconnectInfo.value = DisconnectInfo(state.reason)
                }
            }
        }
    }

    init {
        viewModelScope.launch {
            val profile = repository.getById(profileId)
            if (profile == null) {
                _loadError.value = "Profile not found"
                return@launch
            }
            // Wait for the UI to publish its first effective DPI before connecting.
            // Settles the start-of-session race: SessionRoute pushes the hint via
            // LaunchedEffect on first composition, but if the repository fetch above
            // returned synchronously we'd otherwise connect at 100% and immediately
            // reconnect at the user's configured value.
            _effectiveDpiHint.filter { it in 100..500 }.first()
            // Same race for audio: a profile that says "use app default" needs the
            // app-level audio settings before we can build the argv.
            _audioDefaultsHint.filter { it != null }.first()
            _clipboardDefaultsHint.filter { it != null }.first()
            _printerShareHint.filter { it != null }.first()
            _keyboardLayoutHint.filter { it != null }.first()
            val isAuto = when (profile) {
                is DirectConnectionProfile -> profile.autoResolution
                is GatewayConnectionProfile -> profile.autoResolution
            }
            if (isAuto) {
                val port = sessionFactory.portFor(profile)
                activatePort(port)
                autoProfile = profile
                autoPort = port
                // onWindowSizeAvailable may have fired before this coroutine ran.
                val w = storedWindowW
                val h = storedWindowH
                if (w > 0 && h > 0) {
                    autoProfile = null
                    autoPort = null
                    val resolved = applyEffectiveKeyboard(applyEffectivePrinter(applyEffectiveClipboard(applyEffectiveCamera(applyEffectiveAudio(applyEffectiveScale(
                        when (profile) {
                            is DirectConnectionProfile -> profile.copy(width = w, height = h)
                            is GatewayConnectionProfile -> profile.copy(width = w, height = h)
                        },
                    ))))))
                    port.connect(resolved).onFailure { e ->
                        _loadError.value = e.message ?: "Connection failed"
                    }.onSuccess {
                        connectedAtMs = System.currentTimeMillis()
                        _ready.value = SessionReady(resolved, port)
                    }
                }
            } else {
                val port = sessionFactory.portFor(profile)
                activatePort(port)
                val resolved = applyEffectiveKeyboard(applyEffectivePrinter(applyEffectiveClipboard(applyEffectiveCamera(applyEffectiveAudio(applyEffectiveScale(profile))))))
                port.connect(resolved).onFailure { e ->
                    _loadError.value = e.message ?: "Connection failed"
                }.onSuccess {
                    connectedAtMs = System.currentTimeMillis()
                    _ready.value = SessionReady(resolved, port)
                }
            }
        }
    }

    /**
     * Returns the percent we should connect with right now. Order of precedence:
     *  1. Profile's own desktopScaleFactor when it's a valid percent (100..500).
     *  2. The hint set via [onEffectiveDpiScaleChanged] (default 100% when DPI > 100).
     *  3. 100% (no scaling).
     */
    private fun resolveScale(profile: ConnectionProfile): Int {
        val explicit = when (profile) {
            is DirectConnectionProfile -> profile.desktopScaleFactor
            is GatewayConnectionProfile -> profile.desktopScaleFactor
        }
        if (explicit in 100..500) return explicit
        return _effectiveDpiHint.value.takeIf { it in 100..500 } ?: 100
    }

    private fun applyEffectiveScale(profile: ConnectionProfile): ConnectionProfile {
        val explicit = when (profile) {
            is DirectConnectionProfile -> profile.desktopScaleFactor
            is GatewayConnectionProfile -> profile.desktopScaleFactor
        }
        currentScaleIsHintTracked = explicit !in 100..500
        val scale = resolveScale(profile)
        return when (profile) {
            is DirectConnectionProfile -> profile.copy(desktopScaleFactor = scale)
            is GatewayConnectionProfile -> profile.copy(desktopScaleFactor = scale)
        }
    }

    /**
     * Substitute every "use app default" sentinel in [profile]'s audio fields with
     * the concrete value from the most recent [AudioDefaultsHint]. The init coroutine
     * waits for the hint before calling this, so [_audioDefaultsHint.value] is non-null
     * by the time we get here.
     */
    private fun applyEffectiveAudio(profile: ConnectionProfile): ConnectionProfile {
        val hint = _audioDefaultsHint.value ?: AudioDefaultsHint(
            mode = AudioMode.LocalDevice,
            microphoneEnabled = false,
            quality = AudioQuality.Dynamic,
        )
        return when (profile) {
            is DirectConnectionProfile -> profile.copy(
                audioMode = if (profile.audioMode == AudioMode.UseAppDefault) hint.mode else profile.audioMode,
                microphoneOverride = profile.microphoneOverride ?: hint.microphoneEnabled,
                audioQuality = if (profile.audioQuality == AudioQuality.UseAppDefault) hint.quality else profile.audioQuality,
            )
            is GatewayConnectionProfile -> profile.copy(
                audioMode = if (profile.audioMode == AudioMode.UseAppDefault) hint.mode else profile.audioMode,
                microphoneOverride = profile.microphoneOverride ?: hint.microphoneEnabled,
                audioQuality = if (profile.audioQuality == AudioQuality.UseAppDefault) hint.quality else profile.audioQuality,
            )
        }
    }

    /**
     * The UI calls this once per session with the current app-level audio defaults.
     * Mid-session changes are intentionally ignored — FreeRDP negotiates audio at
     * connect time, mirroring the way [onEffectiveDpiScaleChanged] is treated.
     */
    fun setAudioDefaultsHint(hint: AudioDefaultsHint) {
        _audioDefaultsHint.value = hint
    }

    private fun applyEffectiveCamera(profile: ConnectionProfile): ConnectionProfile {
        val hint = _cameraDefaultsHint.value ?: CameraDefaultsHint(
            mode = CameraMode.Disabled,
            deviceId = null,
            encode = true,
        )
        fun resolveMode(m: CameraMode) = if (m == CameraMode.UseAppDefault) hint.mode else m
        return when (profile) {
            is DirectConnectionProfile -> profile.copy(
                cameraMode = resolveMode(profile.cameraMode),
                cameraDeviceId = profile.cameraDeviceId ?: hint.deviceId,
            )
            is GatewayConnectionProfile -> profile.copy(
                cameraMode = resolveMode(profile.cameraMode),
                cameraDeviceId = profile.cameraDeviceId ?: hint.deviceId,
            )
        }
    }

    fun setCameraDefaultsHint(hint: CameraDefaultsHint) {
        _cameraDefaultsHint.value = hint
    }

    private fun applyEffectiveClipboard(profile: ConnectionProfile): ConnectionProfile {
        val enabled = _clipboardDefaultsHint.value ?: true
        return when (profile) {
            is DirectConnectionProfile -> profile.copy(
                clipboardSyncOverride = profile.clipboardSyncOverride ?: enabled,
            )
            is GatewayConnectionProfile -> profile.copy(
                clipboardSyncOverride = profile.clipboardSyncOverride ?: enabled,
            )
        }
    }

    fun setClipboardDefaultsHint(enabled: Boolean) {
        _clipboardDefaultsHint.value = enabled
    }

    /**
     * Resolve "use app default" into a concrete boolean for printer redirection.
     * Mirrors [applyEffectiveClipboard]. Default-disabled because exposing a printer
     * to the remote session is unexpected for many users; settings opt-in is the
     * intentional path.
     */
    private fun applyEffectivePrinter(profile: ConnectionProfile): ConnectionProfile {
        val enabled = _printerShareHint.value ?: false
        return when (profile) {
            is DirectConnectionProfile -> profile.copy(
                printerShareOverride = profile.printerShareOverride ?: enabled,
            )
            is GatewayConnectionProfile -> profile.copy(
                printerShareOverride = profile.printerShareOverride ?: enabled,
            )
        }
    }

    fun setPrinterShareHint(enabled: Boolean) {
        _printerShareHint.value = enabled
    }

    /**
     * Stamp the connect-time keyboard layout id onto the profile. Profiles
     * stored with the default 0 inherit the app-level layout the UI has just
     * pushed; a non-zero stored id (none today, but future per-profile override)
     * wins.
     */
    private fun applyEffectiveKeyboard(profile: ConnectionProfile): ConnectionProfile {
        val hintId = _keyboardLayoutHint.value ?: 0
        return when (profile) {
            is DirectConnectionProfile -> if (profile.keyboardLayoutId == 0) {
                profile.copy(keyboardLayoutId = hintId)
            } else profile
            is GatewayConnectionProfile -> if (profile.keyboardLayoutId == 0) {
                profile.copy(keyboardLayoutId = hintId)
            } else profile
        }
    }

    fun setKeyboardLayoutHint(layoutId: Int) {
        _keyboardLayoutHint.value = layoutId
    }

    /**
     * Called by the UI whenever the effective DPI changes (DeX toggled, default edited).
     * If a session is already running and the resolved scale shifts, reconnect with the
     * new value — FreeRDP needs the desktop-scale negotiated at connect time.
     */
    fun onEffectiveDpiScaleChanged(newScale: Int) {
        if (newScale !in 100..500) return
        if (newScale == _effectiveDpiHint.value) return
        _effectiveDpiHint.value = newScale

        // Only reconnect when the active session is following the app default
        // (i.e. the profile didn't pin its own scale).
        val ready = _ready.value ?: return
        if (!currentScaleIsHintTracked) return
        val current = when (val p = ready.profile) {
            is DirectConnectionProfile -> p.desktopScaleFactor
            is GatewayConnectionProfile -> p.desktopScaleFactor
        }
        if (current != newScale) {
            reconnect(ready, profileWidth(ready.profile), profileHeight(ready.profile), newScale)
        }
    }

    private fun profileWidth(profile: ConnectionProfile): Int = when (profile) {
        is DirectConnectionProfile -> profile.width
        is GatewayConnectionProfile -> profile.width
    }

    private fun profileHeight(profile: ConnectionProfile): Int = when (profile) {
        is DirectConnectionProfile -> profile.height
        is GatewayConnectionProfile -> profile.height
    }

    fun onWindowSizeAvailable(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        storedWindowW = w
        storedWindowH = h

        // Already connected with auto-resolution: try a live DisplayControl resize
        // first; only fall back to a full reconnect if the engine can't do it.
        val ready = _ready.value
        if (ready != null) {
            val p = ready.profile
            val isAuto = when (p) {
                is DirectConnectionProfile -> p.autoResolution
                is GatewayConnectionProfile -> p.autoResolution
            }
            val curW = when (p) {
                is DirectConnectionProfile -> p.width
                is GatewayConnectionProfile -> p.width
            }
            val curH = when (p) {
                is DirectConnectionProfile -> p.height
                is GatewayConnectionProfile -> p.height
            }
            if (isAuto && (curW != w || curH != h)) {
                // Cancel any in-flight pending resize so only the LATEST requested
                // size is applied. Each new window dimension during a drag gesture
                // restarts the timer; the actual sendMonitorLayout only fires once
                // the user stops dragging for resizeDebounceMs.
                pendingResizeJob?.cancel()
                pendingResizeJob = viewModelScope.launch {
                    kotlinx.coroutines.delay(resizeDebounceMs)
                    val current = _ready.value ?: return@launch
                    val curProfile = current.profile
                    val nowW = when (curProfile) {
                        is DirectConnectionProfile -> curProfile.width
                        is GatewayConnectionProfile -> curProfile.width
                    }
                    val nowH = when (curProfile) {
                        is DirectConnectionProfile -> curProfile.height
                        is GatewayConnectionProfile -> curProfile.height
                    }
                    if (nowW == w && nowH == h) return@launch
                    val scale = when (curProfile) {
                        is DirectConnectionProfile -> curProfile.desktopScaleFactor
                        is GatewayConnectionProfile -> curProfile.desktopScaleFactor
                    }
                    val live = runCatching {
                        current.port.requestResolution(w, h, scale)
                    }.getOrDefault(false)
                    if (live) {
                        val resized = when (curProfile) {
                            is DirectConnectionProfile -> curProfile.copy(width = w, height = h)
                            is GatewayConnectionProfile -> curProfile.copy(width = w, height = h)
                        }
                        _ready.value = SessionReady(resized, current.port)
                    } else {
                        reconnect(current, w, h)
                    }
                }
            }
            return
        }

        // Initial auto-resolution connect (profile still loading or port not yet connected).
        val profile = autoProfile ?: return
        val port = autoPort ?: return
        autoProfile = null
        autoPort = null
        // activatePort already called when port was created; no need to call again.
        viewModelScope.launch {
            val resolved = applyEffectiveKeyboard(applyEffectivePrinter(applyEffectiveClipboard(applyEffectiveCamera(applyEffectiveAudio(applyEffectiveScale(
                when (profile) {
                    is DirectConnectionProfile -> profile.copy(width = w, height = h)
                    is GatewayConnectionProfile -> profile.copy(width = w, height = h)
                },
            ))))))
            port.connect(resolved).onFailure { e ->
                _loadError.value = e.message ?: "Connection failed"
            }.onSuccess {
                connectedAtMs = System.currentTimeMillis()
                _ready.value = SessionReady(resolved, port)
            }
        }
    }

    private fun reconnect(oldReady: SessionReady, w: Int, h: Int, overrideScale: Int? = null) {
        val oldPort = oldReady.port
        val profile = oldReady.profile
        _ready.value = null
        connectedAtMs = 0L
        viewModelScope.launch {
            runCatching { oldPort.disconnect() }
            val resized = when (profile) {
                is DirectConnectionProfile -> profile.copy(width = w, height = h)
                is GatewayConnectionProfile -> profile.copy(width = w, height = h)
            }
            val resolved = applyEffectiveKeyboard(applyEffectivePrinter(applyEffectiveClipboard(applyEffectiveCamera(applyEffectiveAudio(
                if (overrideScale != null) {
                    when (resized) {
                        is DirectConnectionProfile -> resized.copy(desktopScaleFactor = overrideScale)
                        is GatewayConnectionProfile -> resized.copy(desktopScaleFactor = overrideScale)
                    }
                } else {
                    applyEffectiveScale(resized)
                }
            )))))
            val port = sessionFactory.portFor(resolved)
            activatePort(port)
            port.connect(resolved).onFailure { e ->
                _loadError.value = e.message ?: "Connection failed"
            }.onSuccess {
                connectedAtMs = System.currentTimeMillis()
                _ready.value = SessionReady(resolved, port)
            }
        }
    }

    /**
     * User chose Retry from the disconnect modal: tear the dead port down and
     * open a fresh session against the same profile/size.
     */
    fun retryAfterDisconnect() {
        val ready = _ready.value ?: return
        _disconnectInfo.value = null
        reconnect(ready, profileWidth(ready.profile), profileHeight(ready.profile))
    }

    /** Hide the disconnect modal without retrying (caller is expected to navigate back). */
    fun dismissDisconnect() {
        _disconnectInfo.value = null
    }

    fun attachSurface(surface: Surface, width: Int, height: Int, options: RenderOptions = RenderOptions()) {
        _ready.value?.port?.attachSurface(surface, width, height, options)
    }

    fun detachSurface() {
        _ready.value?.port?.detachSurface()
    }

    fun resolveChallenge(id: String, response: ChallengeResponse) {
        _currentChallenge.value = null
        activePort?.resolveChallenge(id, response)
    }

    override fun onCleared() {
        super.onCleared()
        autoPort = null
        autoProfile = null
        val ready = _ready.value
        // If connect() never returned successfully, disconnect the active port so any
        // blocked challenge or in-progress handshake is cancelled.
        if (ready == null) {
            activePort?.let { port -> teardownScope.launch { runCatching { port.disconnect() } } }
            return
        }
        val port = ready.port
        val profile = ready.profile
        // Fire-and-forget: do NOT runBlocking here. Engine.disconnect can block
        // on its own worker thread, which may be the very thread we'd be holding.
        teardownScope.launch {
            withTimeoutOrNull(DISCONNECT_TIMEOUT_MS) {
                val (up, down) = when (val s = port.sessionState.value) {
                    is SessionState.Connected -> s.bytesSent to s.bytesReceived
                    else -> 0L to 0L
                }
                if (connectedAtMs > 0L) {
                    sessionHistory.append(
                        SessionHistoryEntry(
                            id = UUID.randomUUID().toString(),
                            profileId = profile.id,
                            displayName = profile.displayName,
                            startedAtEpochMs = connectedAtMs,
                            endedAtEpochMs = System.currentTimeMillis(),
                            bytesSent = up,
                            bytesReceived = down,
                        ),
                    )
                }
                port.disconnect()
            }
        }
    }

    private companion object {
        const val DISCONNECT_TIMEOUT_MS = 5_000L

        // App-lifetime scope so disconnect work survives the ViewModel itself.
        val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
