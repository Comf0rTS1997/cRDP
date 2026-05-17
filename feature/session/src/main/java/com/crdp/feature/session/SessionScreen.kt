package com.crdp.feature.session

import android.app.Activity
import android.os.Build
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.InputDevice
import android.view.MotionEvent
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.KeyCharacterMap
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.crdp.core.rdp.input.KeyAction
import com.crdp.core.rdp.input.KeyEventPayload
import com.crdp.core.rdp.input.PointerAction
import com.crdp.core.rdp.input.PointerEvent
import com.crdp.core.rdp.input.RemoteTouchPhase
import com.crdp.core.rdp.input.TouchContact
import com.crdp.core.rdp.model.AudioMode
import com.crdp.core.rdp.model.AudioQuality
import com.crdp.core.rdp.model.ConnectionProfile
import com.crdp.core.rdp.model.DirectConnectionProfile
import com.crdp.core.rdp.model.GatewayConnectionProfile
import com.crdp.core.rdp.model.SessionState
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.math.roundToInt

private val DockBackground = Color(0xAA000000)
private val DisconnectRed = Color(0xFFBA1A1A)

// MotionEvent.buttonState bitmask → legacy RDP button id (1=left, 2=right, 3=middle).
// BACK/FORWARD are intentionally omitted (no PTR_XFLAGS support in this engine yet).
private val MOUSE_BUTTONS: List<Pair<Int, Int>> = listOf(
    MotionEvent.BUTTON_PRIMARY to 1,
    MotionEvent.BUTTON_SECONDARY to 2,
    MotionEvent.BUTTON_TERTIARY to 3,
)

enum class InputMode { DirectTouch, Trackpad }

data class RdpTransform(val scale: Float, val offsetX: Float, val offsetY: Float) {
    fun toRdp(sx: Float, sy: Float): Pair<Float, Float> =
        (sx - offsetX) / scale to (sy - offsetY) / scale
}

fun computeRdpTransform(sw: Int, sh: Int, rw: Int, rh: Int): RdpTransform {
    if (sw == 0 || sh == 0 || rw == 0 || rh == 0) return RdpTransform(1f, 0f, 0f)
    val scale = minOf(sw.toFloat() / rw, sh.toFloat() / rh)
    return RdpTransform(scale, (sw - rw * scale) / 2f, (sh - rh * scale) / 2f)
}

data class SessionUserSettings(
    val hapticFeedback: Boolean = true,
    val touchAsMouse: Boolean = true,
    /**
     * Sign-flip wheel deltas for both physical mouse-wheel events and
     * two-finger touchpad scroll. Mirrors the "natural scrolling" toggle on
     * macOS/Windows.
     */
    val reverseScroll: Boolean = false,
    /** Percent multiplier applied to physical mouse wheel deltas. 100 = unchanged. */
    val mouseWheelSpeedPercent: Int = 100,
    /** Percent multiplier for two-finger touchpad scroll sensitivity. 100 = unchanged. */
    val touchpadScrollSpeedPercent: Int = 100,
    val autoDisconnectIdle: Boolean = false,
    val renderOptions: com.crdp.core.rdp.engine.RenderOptions = com.crdp.core.rdp.engine.RenderOptions(),
    /** App-wide default DPI percent (100..500) when the profile doesn't override. */
    val defaultDpiScale: Int = 100,
    /** DPI percent (100..500) applied when the device is in Samsung DeX (UI_MODE_TYPE_DESK). */
    val dexDpiScale: Int = 100,
    /** App-wide default playback mode for new sessions. One of AudioModes.OPTIONS labels. */
    val defaultAudioMode: String = "Local device",
    val defaultMicrophoneEnabled: Boolean = false,
    /** App-wide default audio quality. One of AudioQualities.OPTIONS labels. */
    val defaultAudioQuality: String = "Dynamic",
    /** App-wide default camera mode for new sessions. */
    val defaultCameraMode: com.crdp.core.rdp.model.CameraMode = com.crdp.core.rdp.model.CameraMode.Disabled,
    /** Native rdpecam device id when defaultCameraMode == Specific. */
    val defaultCameraDeviceId: String? = null,
    /** Encode camera frames to H.264 client-side (recommended). */
    val cameraEncode: Boolean = true,
    /** App-wide default for two-way plain-text clipboard sync (direct profiles only). */
    val defaultClipboardSync: Boolean = true,
    /** App-wide default for printer share / virtual printer redirection (direct profiles only). */
    val defaultPrinterShare: Boolean = false,
    /**
     * Windows keyboard layout id (e.g. 0x0409) the FreeRDP server should assume
     * the client is using. 0 means "let FreeRDP auto-detect from the host
     * locale", matching pre-wiring behavior.
     */
    val keyboardLayoutId: Int = 0,
    /** Ids of [AuxKeys] entries shown in the row attached above the soft keyboard. */
    val auxKeyRowKeys: Set<String> = AuxKeys.DEFAULT_ENABLED,
)

@Composable
fun SessionRoute(
    viewModel: SessionViewModel,
    onBack: () -> Unit,
    settings: SessionUserSettings = SessionUserSettings(),
    onTouchAsMouseChanged: (Boolean) -> Unit = {},
) {
    val config = LocalConfiguration.current
    val density = LocalDensity.current
    val windowW = with(density) { config.screenWidthDp.dp.roundToPx() }
    val windowH = with(density) { config.screenHeightDp.dp.roundToPx() }

    // Legacy Samsung DeX sets UI_MODE_TYPE_DESK. Modern Samsung DeX (One UI 8 / Android 15+)
    // uses AOSP Desktop Windowing — the app window lives on a non-primary display
    // (FLAG_EXTERNAL_DEX_HOSTING + WINDOWING_MODE_FREEFORM) and UI_MODE_TYPE_DESK is never set.
    // Detect both: legacy via uiMode mask, modern via display id != DEFAULT_DISPLAY.
    val view = LocalView.current
    val inDexMode = run {
        val legacyDex = (config.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) ==
            android.content.res.Configuration.UI_MODE_TYPE_DESK
        val displayId = view.display?.displayId ?: android.view.Display.DEFAULT_DISPLAY
        legacyDex || displayId != android.view.Display.DEFAULT_DISPLAY
    }
    val effectiveDpi = if (inDexMode) settings.dexDpiScale else settings.defaultDpiScale

    // Sync VM with the current effective scale before the first connect, and on every
    // subsequent change (DeX toggle, settings edit). The VM applies it to the initial
    // connect or triggers a reconnect when scale changes mid-session.
    LaunchedEffect(effectiveDpi) {
        viewModel.onEffectiveDpiScaleChanged(effectiveDpi)
    }

    LaunchedEffect(windowW, windowH) {
        viewModel.onWindowSizeAvailable(windowW, windowH)
    }

    // Tell the rdpecam HAL the orientation it should rotate captured frames into.
    //
    // Handheld: use OrientationEventListener (accelerometer-derived) rather than
    // Display.getRotation() — if Auto-rotate is off the display rotation stays
    // at 0 even when the phone is physically held in landscape, but we still
    // want the camera frame to follow the user's grip.
    //
    // DeX: the phone's accelerometer is meaningless — picking up the phone or
    // setting it down would randomly rotate the camera view on the monitor.
    // Lock to surfaceRot=90 (landscape) so the Camera2 formula resolves to a
    // rotation of 0 for sensors mounted at 90° (typical Samsung phones).
    val ctxForRotation = LocalContext.current
    DisposableEffect(inDexMode) {
        if (inDexMode) {
            com.crdp.core.rdp.CameraOrientationBridge.setDisplayRotation(90)
            return@DisposableEffect onDispose { }
        }
        val sensorListener = object : android.view.OrientationEventListener(
            ctxForRotation,
            android.hardware.SensorManager.SENSOR_DELAY_NORMAL,
        ) {
            private var lastQuantized = -1
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                // OrientationEventListener returns the CLOCKWISE angle the device
                // has been rotated from natural. The standard Camera2 rotation
                // formula expects Surface.ROTATION_* (counter-clockwise convention).
                // Convert: surface = (360 − oel) % 360.
                //   OEL  0  (natural)        ↔ Surface 0
                //   OEL  90 (top-right)      ↔ Surface 270
                //   OEL  180 (upside-down)   ↔ Surface 180
                //   OEL  270 (top-left)      ↔ Surface 90
                val oelQuantized = when {
                    orientation >= 315 || orientation < 45 -> 0
                    orientation in 45..134 -> 90
                    orientation in 135..224 -> 180
                    else -> 270
                }
                val q = (360 - oelQuantized) % 360
                if (q != lastQuantized) {
                    lastQuantized = q
                    com.crdp.core.rdp.CameraOrientationBridge.setDisplayRotation(q)
                }
            }
        }
        if (sensorListener.canDetectOrientation()) {
            sensorListener.enable()
        } else {
            val r = view.display?.rotation ?: android.view.Surface.ROTATION_0
            val q = when (r) {
                android.view.Surface.ROTATION_90 -> 90
                android.view.Surface.ROTATION_180 -> 180
                android.view.Surface.ROTATION_270 -> 270
                else -> 0
            }
            com.crdp.core.rdp.CameraOrientationBridge.setDisplayRotation(q)
        }
        onDispose { sensorListener.disable() }
    }

    // Audio defaults are negotiated at connect time only — push once per change of
    // settings; if a session is already running, the new defaults apply on the next
    // connect (matches the DPI-default model exactly).
    LaunchedEffect(
        settings.defaultAudioMode,
        settings.defaultMicrophoneEnabled,
        settings.defaultAudioQuality,
    ) {
        val mode = when (settings.defaultAudioMode) {
            "Remote PC" -> AudioMode.RemoteConsole
            "Off" -> AudioMode.Disabled
            else -> AudioMode.LocalDevice
        }
        val quality = when (settings.defaultAudioQuality) {
            "Medium" -> AudioQuality.Medium
            "High" -> AudioQuality.High
            else -> AudioQuality.Dynamic
        }
        viewModel.setAudioDefaultsHint(
            AudioDefaultsHint(
                mode = mode,
                microphoneEnabled = settings.defaultMicrophoneEnabled,
                quality = quality,
            ),
        )
    }

    LaunchedEffect(settings.defaultCameraMode, settings.defaultCameraDeviceId, settings.cameraEncode) {
        viewModel.setCameraDefaultsHint(
            CameraDefaultsHint(
                mode = settings.defaultCameraMode,
                deviceId = settings.defaultCameraDeviceId,
                encode = settings.cameraEncode,
            ),
        )
    }

    LaunchedEffect(settings.defaultClipboardSync) {
        viewModel.setClipboardDefaultsHint(settings.defaultClipboardSync)
    }

    LaunchedEffect(settings.defaultPrinterShare) {
        viewModel.setPrinterShareHint(settings.defaultPrinterShare)
    }

    LaunchedEffect(settings.keyboardLayoutId) {
        viewModel.setKeyboardLayoutHint(settings.keyboardLayoutId)
    }

    // Mic is a runtime-prompt permission. Only request when the resolved decision
    // for this session needs it; otherwise stay silent.
    val ctx = LocalContext.current
    val ready by viewModel.ready.collectAsStateWithLifecycle()
    val needsMic = remember(ready, settings.defaultMicrophoneEnabled) {
        val r = ready ?: return@remember settings.defaultMicrophoneEnabled
        when (val p = r.profile) {
            is DirectConnectionProfile -> p.microphoneOverride ?: settings.defaultMicrophoneEnabled
            is GatewayConnectionProfile -> p.microphoneOverride ?: settings.defaultMicrophoneEnabled
        }
    }
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* result not surfaced — denial just means FreeRDP fails to open the mic */ }
    LaunchedEffect(needsMic) {
        if (needsMic && ContextCompat.checkSelfPermission(
                ctx,
                Manifest.permission.RECORD_AUDIO,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Camera mirrors the mic pattern. Resolution: profile's explicit cameraMode wins;
    // UseAppDefault → app-wide default (settings.defaultCameraMode). Anything not Disabled
    // means rdpecam will be active and we need CAMERA permission.
    val needsCamera = remember(ready, settings.defaultCameraMode) {
        val r = ready ?: return@remember settings.defaultCameraMode != com.crdp.core.rdp.model.CameraMode.Disabled
        val mode = when (val p = r.profile) {
            is DirectConnectionProfile -> p.cameraMode
            is GatewayConnectionProfile -> p.cameraMode
        }
        val effective = if (mode == com.crdp.core.rdp.model.CameraMode.UseAppDefault) settings.defaultCameraMode else mode
        effective != com.crdp.core.rdp.model.CameraMode.Disabled
    }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* result not surfaced — denial just means rdpecam HAL can't open any camera */ }
    LaunchedEffect(needsCamera) {
        if (needsCamera && ContextCompat.checkSelfPermission(
                ctx,
                Manifest.permission.CAMERA,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            cameraLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val loadError by viewModel.loadError.collectAsStateWithLifecycle()
    val disconnectInfo by viewModel.disconnectInfo.collectAsStateWithLifecycle()

    // Always active — certificate/auth challenges fire during the connecting
    // handshake before ready is ever set, so this must live outside the when-branch.
    val currentChallenge by viewModel.currentChallenge.collectAsStateWithLifecycle()
    ChallengeDialog(
        challenge = currentChallenge,
        onResolve = viewModel::resolveChallenge,
    )

    // Mid-session disconnect (server kick, network drop). Layer the dialog over the
    // existing session screen so the user keeps the last frame as visual context.
    DisconnectDialog(
        info = disconnectInfo,
        onRetry = { viewModel.retryAfterDisconnect() },
        onCancel = {
            viewModel.dismissDisconnect()
            onBack()
        },
    )

    when {
        loadError != null -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(loadError ?: "", color = Color.White, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onBack) { Text("Back") }
                }
            }
        }
        ready != null -> {
            SessionScreen(
                sessionReady = ready!!,
                settings = settings,
                onBack = onBack,
                onAttachSurface = viewModel::attachSurface,
                onDetachSurface = viewModel::detachSurface,
                onTouchAsMouseChanged = onTouchAsMouseChanged,
            )
        }
        else -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                Text("Connecting…", style = MaterialTheme.typography.titleMedium, color = Color.White)
            }
        }
    }
}

@Composable
private fun SessionScreen(
    sessionReady: SessionReady,
    settings: SessionUserSettings,
    onBack: () -> Unit,
    onAttachSurface: (android.view.Surface, Int, Int, com.crdp.core.rdp.engine.RenderOptions) -> Unit,
    onDetachSurface: () -> Unit,
    onTouchAsMouseChanged: (Boolean) -> Unit = {},
) {
    val port = sessionReady.port
    val sessionState by port.sessionState.collectAsStateWithLifecycle()
    val cursorFrame by port.cursor.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val density = LocalDensity.current

    var isFullscreen by rememberSaveable { mutableStateOf(true) }
    var lastInteractionAt by remember { mutableStateOf(System.currentTimeMillis()) }
    val imeEditTextRef = remember { mutableStateOf<EditText?>(null) }
    var dockPos by remember { mutableStateOf(Offset(-1f, -1f)) }
    val isEdgeExpandedState = remember { mutableStateOf(false) }
    var isEdgeExpanded by isEdgeExpandedState
    var dockMenuOpen by remember { mutableStateOf(false) }
    // Dock layout bounds kept in sync via SideEffect; read from the pointerInput coroutine
    // to exclude dock-area touches from RDP input processing.
    val dockBoundsRef = remember { mutableStateOf<Rect?>(null) }
    // Aux key row bounds (when the soft keyboard is up). Same purpose as
    // dockBoundsRef — the outer pointerInput consumes touches everywhere it
    // doesn't recognize, so without an exclusion the row's taps/scroll would
    // never reach the row itself.
    val auxRowBoundsRef = remember { mutableStateOf<Rect?>(null) }

    // Surface dimensions (updated from SurfaceHolder callbacks on main thread).
    // Use an explicit MutableState so the AndroidView factory lambda captures the reference.
    val surfaceDims = remember { mutableStateOf(Pair(0, 0)) }
    val (surfaceW, surfaceH) = surfaceDims.value
    // Reference to the focusable SurfaceView that owns pointer capture and key
    // pre-IME dispatch. Populated by the AndroidView factory below.
    val rdpSurfaceRef = remember { mutableStateOf<RdpSurfaceView?>(null) }

    val profile = sessionReady.profile
    val rdpW = profileWidth(profile)
    val rdpH = profileHeight(profile)
    val transform = remember(surfaceW, surfaceH, rdpW, rdpH) {
        computeRdpTransform(surfaceW, surfaceH, rdpW, rdpH)
    }

    // Input mode: Trackpad (relative movement) or DirectTouch (absolute mapped to RDP).
    var inputMode by remember {
        mutableStateOf(if (settings.touchAsMouse) InputMode.Trackpad else InputMode.DirectTouch)
    }

    // Multiplier applied to every emitted wheel delta.
    //
    // Default (reverseScroll = false) → -1, i.e. content tracks the finger
    // (macOS "natural scrolling"): finger drag down moves the page down.
    // Toggling reverseScroll on → +1, the traditional Windows-style mapping
    // where finger drag down sends the page contents up. Applies uniformly to
    // physical mouse wheel events and two-finger trackpad pan.
    val scrollSign: Float = if (settings.reverseScroll) 1f else -1f

    // Virtual cursor position in RDP space (used in Trackpad mode).
    var cursorX by remember { mutableStateOf(rdpW / 2f) }
    var cursorY by remember { mutableStateOf(rdpH / 2f) }
    val tapThresholdPx = with(density) { 12.dp.toPx() }
    // ~12dp of finger travel = one wheel detent at 100% speed. Higher speed
    // setting → fewer px per detent (more sensitive); lower → more px per
    // detent (slower). Clamp to a sane floor so a runaway pref can't make
    // every micro-jitter emit a detent.
    val scrollPxPerDetent = with(density) {
        val base = 12.dp.toPx()
        (base * 100f / settings.touchpadScrollSpeedPercent.coerceAtLeast(1)).coerceAtLeast(1f)
    }
    // Per-detent multiplier for physical mouse wheel events. Independent of
    // the touchpad sensitivity above.
    val mouseWheelScale: Float = settings.mouseWheelSpeedPercent / 100f
    // ~24dp change in finger spread = one Ctrl+wheel zoom step.
    val pinchPxPerDetent = with(density) { 24.dp.toPx() }
    // Trackpad: a second tap that starts within this window of the first tap's UP
    // becomes "tap-and-a-half" → drag. Position is intentionally not constrained
    // (see the tap-and-half block for why).
    val doubleTapDragWindowMs = 300L
    // Trackpad two-finger tap (right-click) constraints.
    val twoFingerTapMaxDurationMs = 350L
    val twoFingerTapMaxMovePx = with(density) { 16.dp.toPx() }
    // DirectTouch long-press / double-tap constants.
    val longPressTimeoutMs = 500L
    val doubleTapWindowMs = 300L
    val doubleTapMaxMoveRdp = 32f
    // Records the time of the last single-finger tap UP so a follow-up Down can
    // promote into a tap-and-a-half drag. Persisted across gesture-loop iterations.
    val trackpadLastTapMs = remember { mutableStateOf(0L) }
    val directTouchLastTapMs = remember { mutableStateOf(0L) }
    val directTouchLastTapRdpX = remember { mutableStateOf(0f) }
    val directTouchLastTapRdpY = remember { mutableStateOf(0f) }
    val rdpeiSupported = remember { mutableStateOf(true) }
    val rdpeiFingerIds = remember { mutableStateOf(mutableMapOf<Long, Int>()) }
    val rdpeiNextFingerId = remember { mutableStateOf(0) }

    // Tracks the previous MotionEvent.buttonState bitmask so we can emit Up/Down
    // transitions for left/right/middle separately when a hardware mouse delivers
    // events through dispatchCapturedPointerEvent or dispatchGenericMotionEvent.
    var lastMouseButtons by remember { mutableStateOf(0) }

    fun markInteraction() {
        lastInteractionAt = System.currentTimeMillis()
    }

    fun haptic(constant: Int) {
        if (settings.hapticFeedback) {
            view.performHapticFeedback(constant)
        }
    }

    /**
     * Single entry point for hardware mouse / touchpad / stylus motion. Fed from
     * three callsites: setOnCapturedPointerListener (relative deltas while pointer
     * capture is active), setOnGenericMotionListener (hover + scroll when capture
     * is not active), and the Compose pointerInput when a pointer of type Mouse
     * or Stylus arrives via dispatchTouchEvent (button-press path on devices
     * without pointer capture).
     *
     * @param captured true when delivered via the captured-pointer path
     *   (AXIS_RELATIVE_X/Y carry the deltas, [ev]'s x/y are clamped to the view
     *   and must NOT be used as absolute positions).
     */
    fun handleHardwareMouseMotion(ev: MotionEvent, captured: Boolean): Boolean {
        val action = ev.actionMasked

        // Update virtual cursor position from this event.
        when (action) {
            MotionEvent.ACTION_HOVER_ENTER,
            MotionEvent.ACTION_HOVER_MOVE,
            MotionEvent.ACTION_HOVER_EXIT,
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_BUTTON_PRESS,
            MotionEvent.ACTION_BUTTON_RELEASE -> {
                if (captured) {
                    val dx = ev.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
                    val dy = ev.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
                    cursorX = (cursorX + dx).coerceIn(0f, rdpW.toFloat())
                    cursorY = (cursorY + dy).coerceIn(0f, rdpH.toFloat())
                } else if (transform.scale > 0f) {
                    val (rx, ry) = transform.toRdp(ev.x, ev.y)
                    cursorX = rx.coerceIn(0f, rdpW.toFloat())
                    cursorY = ry.coerceIn(0f, rdpH.toFloat())
                }
            }
        }

        // Emit movement first so the server cursor is at the new position before
        // a button transition lands. Skip on button-only events.
        val isMotionAction = action == MotionEvent.ACTION_HOVER_MOVE ||
            action == MotionEvent.ACTION_HOVER_ENTER ||
            action == MotionEvent.ACTION_MOVE ||
            action == MotionEvent.ACTION_DOWN ||
            action == MotionEvent.ACTION_UP
        if (isMotionAction) {
            val dragging = ev.buttonState != 0
            port.onPointerEvent(
                PointerEvent(
                    x = cursorX,
                    y = cursorY,
                    action = if (dragging) PointerAction.Move else PointerAction.Hover,
                    buttons = 0,
                    wheelDelta = 0f,
                    wheelDeltaH = 0f,
                ),
            )
            markInteraction()
        }

        // Button-state transitions → per-button Down/Up. MotionEvent.buttonState is a
        // bitmask; we diff against the last seen mask to find newly pressed/released
        // buttons. Each transition becomes one RDP cursor event with the legacy
        // 1=left / 2=right / 3=middle id.
        val curButtons = ev.buttonState
        if (curButtons != lastMouseButtons) {
            val prev = lastMouseButtons
            val changed = prev xor curButtons
            for ((mask, id) in MOUSE_BUTTONS) {
                if ((changed and mask) != 0) {
                    val pressed = (curButtons and mask) != 0
                    // Mouse / touchpad / stylus button events must NOT vibrate — the buzz
                    // is only sensible for touchscreen taps, which arrive via the Compose
                    // pointerInput path below.
                    port.onPointerEvent(
                        PointerEvent(
                            x = cursorX,
                            y = cursorY,
                            action = if (pressed) PointerAction.Down else PointerAction.Up,
                            buttons = id,
                            wheelDelta = 0f,
                            wheelDeltaH = 0f,
                        ),
                    )
                }
            }
            lastMouseButtons = curButtons
            markInteraction()
        }

        // Scroll wheel. Windows convention: 120 == one detent. AXIS_VSCROLL is the
        // detent count, signed. AXIS_HSCROLL ditto for horizontal wheels.
        if (action == MotionEvent.ACTION_SCROLL) {
            val vsc = ev.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val hsc = ev.getAxisValue(MotionEvent.AXIS_HSCROLL)
            if (vsc != 0f || hsc != 0f) {
                port.onPointerEvent(
                    PointerEvent(
                        x = cursorX,
                        y = cursorY,
                        action = PointerAction.Hover,
                        buttons = 0,
                        // Android AXIS_VSCROLL and RDP wheel rotation use the same sign
                        // convention (positive = wheel rolling away from user / scroll-up),
                        // so pass through without flipping — unless the user opted into
                        // "Reverse scroll direction" in settings.
                        wheelDelta = vsc * 120f * scrollSign * mouseWheelScale,
                        wheelDeltaH = hsc * 120f * scrollSign * mouseWheelScale,
                    ),
                )
                markInteraction()
            }
        }

        return true
    }

    // Focus is owned by RdpSurfaceView (via its onAttachedToWindow + captureOnFocus
    // wiring). Don't pull it onto the Compose root — see the focusable() comment
    // on BoxWithConstraints below.

    // Hoisted up — the brightness DisposableEffect and clipboard focus push below
    // both gate on a live session before sessionConnected was previously declared.
    val sessionConnected = sessionState is SessionState.Connected

    LaunchedEffect(isFullscreen) {
        val activity = context as? Activity ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (isFullscreen) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // When the session leaves composition (disconnect / nav back), make sure
    // system bars are restored so the rest of the app isn't stuck full-screen.
    DisposableEffect(Unit) {
        onDispose {
            val activity = context as? Activity ?: return@onDispose
            val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            controller.show(WindowInsetsCompat.Type.systemBars())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching {
                    if (view.hasPointerCapture()) view.releasePointerCapture()
                    val surf = rdpSurfaceRef.value
                    if (surf?.hasPointerCapture() == true) surf.releasePointerCapture()
                }
            }
        }
    }

    // Capture hardware mouse / touchpad on the Compose root so pointer events stay
    // in-window (DeX task bar, etc.) without moving focus off the key-handling
    // surface (see focusRequester above). Three input paths feed handleHardwareMouseMotion:
    //  - setOnCapturedPointerListener: while pointer capture is active, every mouse
    //    event arrives here with AXIS_RELATIVE_X/Y deltas (no jumps to absolute
    //    screen coords). This is the path termux-x11 uses.
    //  - setOnGenericMotionListener: hover + scroll when capture is not active
    //    (e.g., before the session is fully connected, or on a device that refused
    //    capture). Mouse button events do NOT come through this path.
    //  - Compose pointerInput below: source-routed to MOUSE for the button-press
    //    fallback on devices without pointer capture support.
    // (sessionConnected hoisted earlier; reuse the same val here.)

    // On a fresh connect the SurfaceView is already attached and the window already
    // focused, so onAttachedToWindow / onWindowFocusChanged don't refire. The
    // AndroidView `update` block makes a single requestPointerCapture attempt, but
    // DeX sometimes silently drops the first request while the WindowManager is still
    // settling — and the user is then stuck having to press Esc and click the surface
    // to coax capture into engaging. Retry on a short cadence until capture lands or
    // the budget elapses.
    LaunchedEffect(sessionConnected) {
        if (!sessionConnected) return@LaunchedEffect
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return@LaunchedEffect
        repeat(8) {
            val surf = rdpSurfaceRef.value ?: return@repeat
            if (surf.hasPointerCapture()) return@LaunchedEffect
            surf.captureOnFocus = true
            surf.requestFocus()
            runCatching { surf.requestPointerCapture() }
            delay(100L)
        }
    }

    // Phone → Windows clipboard sync. Android's OnPrimaryClipChangedListener (engine-
    // side) is unreliable when the clipboard write came from a different app, esp. on
    // Samsung One UI 8 / Android 16 where background visibility is silenced. Push the
    // current clipboard contents to FreeRDP whenever the activity regains window focus,
    // which is when the user has just come back from the app they copied from.
    val activityForClipboard = context as? Activity
    DisposableEffect(sessionConnected, activityForClipboard) {
        if (!sessionConnected || activityForClipboard == null) {
            return@DisposableEffect onDispose { }
        }
        val cm = activityForClipboard.getSystemService(Context.CLIPBOARD_SERVICE)
            as? android.content.ClipboardManager
        fun pushNow() {
            val clip = runCatching { cm?.primaryClip }.getOrNull() ?: return
            if (clip.itemCount <= 0) return
            val text = runCatching {
                clip.getItemAt(0).coerceToText(activityForClipboard)?.toString()
            }.getOrNull()
            if (!text.isNullOrEmpty()) port.pushLocalClipboard(text)
        }
        // Initial push on connect — the user may already have something on their
        // clipboard from before launching the session.
        pushNow()
        val listener = android.view.ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            if (hasFocus) pushNow()
        }
        val vto = view.viewTreeObserver
        vto.addOnWindowFocusChangeListener(listener)
        onDispose {
            val live = view.viewTreeObserver
            if (live.isAlive) live.removeOnWindowFocusChangeListener(listener)
        }
    }

    // Stable wrapper closing over `port`/`transform`/etc. — called both from the
    // Activity-level dispatchKeyEvent hook (the primary path, which sees keys
    // BEFORE the IME/focus system can steal them — Alt+F4 falls in this bucket)
    // and from Compose's onPreviewKeyEvent (a fallback in case the activity is
    // not the bundled MainActivity, e.g., embedded host).
    //
    // Special case: ESC while pointer capture is active releases capture and is
    // NOT forwarded to the server, giving the user a way out of the captured
    // session without a hardware Back/Home (which Android disallows intercepting).
    val escConsumedRef = remember { mutableStateOf(false) }
    fun handleSessionKeyEvent(native: AndroidKeyEvent): Boolean {
        if (!sessionConnected) return false
        val action = when (native.action) {
            AndroidKeyEvent.ACTION_DOWN -> KeyAction.Down
            AndroidKeyEvent.ACTION_UP -> KeyAction.Up
            else -> return false
        }
        // ESC escape-hatch: release capture and swallow both edges of the keystroke
        // so the remote desktop doesn't see a stray Escape.
        val rdpSurface = rdpSurfaceRef.value
        val captureActive = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            (rdpSurface?.hasPointerCapture() == true || view.hasPointerCapture())
        if (native.keyCode == AndroidKeyEvent.KEYCODE_ESCAPE &&
            (captureActive || escConsumedRef.value)
        ) {
            if (action == KeyAction.Down) {
                escConsumedRef.value = true
                // Stop the SurfaceView from immediately re-grabbing capture on focus.
                rdpSurface?.captureOnFocus = false
                runCatching { rdpSurface?.releasePointerCapture() }
                runCatching { view.releasePointerCapture() }
                scope.launch {
                    snackbarHostState.showSnackbar(
                        "Pointer released. Tap the surface to recapture.",
                    )
                }
            } else if (action == KeyAction.Up) {
                escConsumedRef.value = false
            }
            return true
        }
        if (action == KeyAction.Down) {
            haptic(HapticFeedbackConstants.KEYBOARD_TAP)
        }
        markInteraction()
        port.onKeyEvent(
            KeyEventPayload(
                keyCode = native.keyCode,
                metaState = native.metaState,
                action = action,
                scanCode = native.scanCode,
            ),
        )
        return true
    }

    // Key on `transform` too: when the surface resizes (DeX, rotation), the captured
    // mouse-motion handler closes over the new transform on the next effect run.
    DisposableEffect(sessionConnected, view, transform) {
        // Mouse-source touchpad/scroll work pre-O too via setOnGenericMotionListener;
        // pointer-capture-specific wiring is gated below.
        val genericListener = android.view.View.OnGenericMotionListener { _, ev ->
            if (!ev.isFromSource(InputDevice.SOURCE_CLASS_POINTER) &&
                !ev.isFromSource(InputDevice.SOURCE_TOUCHPAD)
            ) {
                return@OnGenericMotionListener false
            }
            handleHardwareMouseMotion(ev, captured = false)
        }
        view.setOnGenericMotionListener(genericListener)

        // Activity-level key sink. This is the termux-x11 pattern: override
        // dispatchKeyEvent on the host Activity so the IME / window focus
        // system can't swallow keystrokes that the RDP server needs (Alt+F4,
        // Alt+Tab, Win+R, function keys). Plain Compose `onPreviewKeyEvent`
        // only fires when the composable has key focus, which is fragile in
        // the presence of pointer capture and surface focus shifts.
        val keyHost = context as? KeyEventHost
        if (keyHost != null && sessionConnected) {
            keyHost.setKeyEventHook { ev -> handleSessionKeyEvent(ev) }
        }

        // Pointer capture + dispatchKeyEventPreIme now live on the RdpSurfaceView
        // itself (see its onWindowFocusChanged + captureOnFocus). The previous
        // path requested capture on LocalView, which the Compose root doesn't
        // reliably hold across immersive system-bar transitions on Samsung DeX.

        onDispose {
            view.setOnGenericMotionListener(null)
            keyHost?.setKeyEventHook(null)
            val surf = rdpSurfaceRef.value
            if (surf != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                surf.captureOnFocus = false
                runCatching {
                    if (surf.hasPointerCapture()) surf.releasePointerCapture()
                }
            }
        }
    }

    // Consume the system Back gesture/key for the whole lifetime of the session
    // screen — including the brief "Connecting…" window — so neither popping the
    // route nor the predictive-back animation can dismiss the session unexpectedly.
    // Disconnect remains available via the dock menu. onPreviewKeyEvent forwards
    // the keystroke before BackHandler fires, so the remote desktop still receives
    // KEYCODE_BACK while connected.
    BackHandler(enabled = true) { /* swallow */ }

    LaunchedEffect(settings.autoDisconnectIdle) {
        if (!settings.autoDisconnectIdle) return@LaunchedEffect
        val idleLimitMs = 30L * 60_000L
        while (true) {
            delay(10_000L)
            if (System.currentTimeMillis() - lastInteractionAt >= idleLimitMs) {
                onBack()
                break
            }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            // Intentionally NOT .focusable() — we want RdpSurfaceView (added below
            // via AndroidView) to win key focus so its dispatchKeyEventPreIme and
            // pointer-capture lifecycle fire. Activity-level dispatchKeyEvent
            // covers the keyboard fallback. A focusable Compose root steals focus
            .pointerInput(port, inputMode, transform) {
                awaitPointerEventScope {
                    while (true) {
                        // Wait for the first finger to land. Anything that isn't a touchscreen
                        // contact (mouse, stylus) goes through setOnGenericMotionListener and the
                        // captured-pointer path on the RdpSurfaceView, NOT this loop.
                        val firstDown = awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Main,
                        )
                        if (firstDown.type != PointerType.Touch) continue
                        val dockBoundsAtStart = dockBoundsRef.value
                        if (dockBoundsAtStart != null && dockBoundsAtStart.contains(firstDown.position)) {
                            continue
                        }
                        val auxRowBounds = auxRowBoundsRef.value
                        if (auxRowBounds != null && auxRowBounds.contains(firstDown.position)) {
                            continue
                        }
                        firstDown.consume()

                        // Touching the surface collapses the edge-expanded dock and
                        // re-engages pointer capture after an ESC release.
                        isEdgeExpandedState.value = false
                        val surf0 = rdpSurfaceRef.value
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                            sessionConnected &&
                            surf0 != null &&
                            !surf0.hasPointerCapture()
                        ) {
                            surf0.captureOnFocus = true
                            surf0.requestFocus()
                            runCatching { surf0.requestPointerCapture() }
                        }

                        val gestureDownMs = System.currentTimeMillis()
                        val primaryId: PointerId = firstDown.id
                        val downPos = firstDown.position
                        haptic(HapticFeedbackConstants.VIRTUAL_KEY)
                        markInteraction()

                        // ── DirectTouch: pure RDPEI pass-through ──────────────
                        // The Windows touch input stack (Tablet PC Input Service)
                        // expects raw touch contacts. It synthesizes mouse clicks
                        // for non-touch-aware apps, fires press-and-hold → right-
                        // click, and runs the OS-level gesture engine (two-finger
                        // pan → scroll, pinch → zoom, etc.). Emitting mouse OR
                        // keyboard events from this code path races with that
                        // synth and breaks gesture recognition — so DirectTouch
                        // mode goes through ONE channel only: onTouchContacts().
                        if (inputMode == InputMode.DirectTouch) {
                            val activeFingerIds = mutableMapOf<Long, Int>()
                            fun newFingerId(): Int {
                                val id = rdpeiNextFingerId.value
                                rdpeiNextFingerId.value = (id + 1) and 0x7F
                                return id
                            }
                            // Register the first finger.
                            val firstFid = newFingerId()
                            activeFingerIds[primaryId.value] = firstFid
                            val (rxd, ryd) = transform.toRdp(downPos.x, downPos.y)
                            val firstOk = port.onTouchContacts(
                                listOf(
                                    TouchContact(
                                        fingerId = firstFid,
                                        x = rxd.coerceIn(0f, rdpW.toFloat()).roundToInt(),
                                        y = ryd.coerceIn(0f, rdpH.toFloat()).roundToInt(),
                                        phase = RemoteTouchPhase.Down,
                                        pressure = 1024,
                                    ),
                                ),
                            )
                            if (!firstOk) rdpeiSupported.value = false

                            while (activeFingerIds.isNotEmpty()) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                val contacts = mutableListOf<TouchContact>()
                                for (c in event.changes) {
                                    val key = c.id.value
                                    val isNew = c.pressed && !c.previousPressed
                                    val isLifted = !c.pressed && c.previousPressed
                                    if (!isNew && !isLifted && !c.pressed) continue
                                    val fid = if (isNew) {
                                        activeFingerIds.getOrPut(key) { newFingerId() }
                                    } else {
                                        activeFingerIds[key] ?: continue
                                    }
                                    val phase = when {
                                        isNew -> RemoteTouchPhase.Down
                                        isLifted -> RemoteTouchPhase.Up
                                        else -> RemoteTouchPhase.Move
                                    }
                                    val (rx, ry) = transform.toRdp(c.position.x, c.position.y)
                                    contacts += TouchContact(
                                        fingerId = fid,
                                        x = rx.coerceIn(0f, rdpW.toFloat()).roundToInt(),
                                        y = ry.coerceIn(0f, rdpH.toFloat()).roundToInt(),
                                        phase = phase,
                                        pressure = if (phase == RemoteTouchPhase.Up) 0 else 1024,
                                    )
                                    if (isLifted) activeFingerIds.remove(key)
                                    if (c.pressed) c.consume()
                                }
                                if (contacts.isNotEmpty()) {
                                    markInteraction()
                                    val ok = port.onTouchContacts(contacts)
                                    if (!ok) rdpeiSupported.value = false
                                }
                            }
                            continue
                        }

                        // ── Trackpad: cursor-model gesture handling ───────────
                        // Trackpad-only: a quick second tap promotes into a "tap-and-a-half"
                        // drag — left button held while the finger moves, the standard
                        // trackpad drag gesture. Position is intentionally NOT constrained
                        // (unlike DirectTouch's double-tap), because on a trackpad the user
                        // can tap anywhere — the cursor is what's been positioned, not the
                        // finger landing site.
                        val isTrackpad = inputMode == InputMode.Trackpad
                        val tapAndHalf = if (isTrackpad) {
                            val gap = gestureDownMs - trackpadLastTapMs.value
                            val ok = gap in 1..doubleTapDragWindowMs
                            if (ok) trackpadLastTapMs.value = 0L
                            ok
                        } else false
                        var trackpadDragHeld = tapAndHalf
                        if (isTrackpad) {
                            // Park the server cursor at our virtual cursor (so the click that
                            // we eventually emit lands on the right pixel).
                            port.onPointerEvent(
                                PointerEvent(cursorX, cursorY, PointerAction.Hover, 0, 0f),
                            )
                            if (trackpadDragHeld) {
                                haptic(HapticFeedbackConstants.LONG_PRESS)
                                port.onPointerEvent(
                                    PointerEvent(cursorX, cursorY, PointerAction.Down, 1, 0f),
                                )
                            }
                        }

                        // ── Mutable per-gesture state ───────────────────────
                        var primaryPos = downPos
                        var lastFingerPos = downPos
                        var secondId: PointerId? = null
                        var secondPos = androidx.compose.ui.geometry.Offset.Zero
                        var twoFingerEntered = false
                        var twoFingerScrolled = false
                        var twoFingerStartCentroid = androidx.compose.ui.geometry.Offset.Zero
                        var twoFingerStartTimeMs = 0L
                        var twoFingerLastCentroid = androidx.compose.ui.geometry.Offset.Zero
                        var twoFingerLastDist = 0f
                        var scrollAccumX = 0f
                        var scrollAccumY = 0f
                        var pinchAccum = 0f
                        var ctrlHeld = false
                        var directTouchDragStarted = false
                        var directTouchLongPressFired = false
                        var totalMoveAbsX = 0f
                        var totalMoveAbsY = 0f
                        var rdpeiPrimaryFingerId = -1
                        if (inputMode == InputMode.DirectTouch && rdpeiSupported.value) {
                            val id = rdpeiNextFingerId.value
                            rdpeiNextFingerId.value = (id + 1) and 0x7F
                            rdpeiFingerIds.value[primaryId.value] = id
                            rdpeiPrimaryFingerId = id
                            val (rxd, ryd) = transform.toRdp(downPos.x, downPos.y)
                            val ok = port.onTouchContacts(
                                listOf(
                                    TouchContact(
                                        fingerId = id,
                                        x = rxd.coerceIn(0f, rdpW.toFloat()).roundToInt(),
                                        y = ryd.coerceIn(0f, rdpH.toFloat()).roundToInt(),
                                        phase = RemoteTouchPhase.Down,
                                        pressure = 1024,
                                    ),
                                ),
                            )
                            if (!ok) rdpeiSupported.value = false
                        }

                        // ── Gesture loop ─────────────────────────────────────
                        var gestureDone = false
                        while (!gestureDone) {
                            // For DirectTouch single-finger, race the next pointer event
                            // against the long-press deadline so a stationary finger fires
                            // right-click without needing a sample.
                            val canLongPress = inputMode == InputMode.DirectTouch &&
                                !directTouchLongPressFired &&
                                !directTouchDragStarted &&
                                !twoFingerEntered
                            val event = if (canLongPress) {
                                val deadline = gestureDownMs + longPressTimeoutMs
                                val waitMs = (deadline - System.currentTimeMillis()).coerceAtLeast(0L)
                                withTimeoutOrNull(waitMs) {
                                    awaitPointerEvent(PointerEventPass.Main)
                                }
                            } else {
                                awaitPointerEvent(PointerEventPass.Main)
                            }

                            if (event == null) {
                                // Long-press timeout fired with no further pointer activity.
                                directTouchLongPressFired = true
                                haptic(HapticFeedbackConstants.LONG_PRESS)
                                val (lrx, lry) = transform.toRdp(downPos.x, downPos.y)
                                val lcx = lrx.coerceIn(0f, rdpW.toFloat())
                                val lcy = lry.coerceIn(0f, rdpH.toFloat())
                                port.onPointerEvent(
                                    PointerEvent(lcx, lcy, PointerAction.Hover, 0, 0f),
                                )
                                port.onPointerEvent(
                                    PointerEvent(lcx, lcy, PointerAction.Down, 2, 0f),
                                )
                                port.onPointerEvent(
                                    PointerEvent(lcx, lcy, PointerAction.Up, 2, 0f),
                                )
                                continue
                            }

                            // Detect a new finger going down (becomes the 2nd contact).
                            for (c in event.changes) {
                                if (c.pressed && !c.previousPressed && c.id != primaryId &&
                                    secondId == null
                                ) {
                                    val db = dockBoundsRef.value
                                    if (db != null && db.contains(c.position)) continue
                                    secondId = c.id
                                    secondPos = c.position
                                    twoFingerEntered = true
                                    val startCx = (primaryPos.x + secondPos.x) / 2f
                                    val startCy = (primaryPos.y + secondPos.y) / 2f
                                    twoFingerStartCentroid = androidx.compose.ui.geometry.Offset(startCx, startCy)
                                    twoFingerLastCentroid = twoFingerStartCentroid
                                    val ddx = secondPos.x - primaryPos.x
                                    val ddy = secondPos.y - primaryPos.y
                                    twoFingerLastDist = kotlin.math.sqrt((ddx * ddx + ddy * ddy).toDouble()).toFloat()
                                    twoFingerStartTimeMs = System.currentTimeMillis()
                                    scrollAccumX = 0f
                                    scrollAccumY = 0f
                                    pinchAccum = 0f
                                    twoFingerScrolled = false

                                    // If a DirectTouch single-finger drag was already in
                                    // flight, lift the mouse button so it doesn't get stuck.
                                    if (directTouchDragStarted) {
                                        val (urx, ury) = transform.toRdp(primaryPos.x, primaryPos.y)
                                        port.onPointerEvent(
                                            PointerEvent(
                                                urx.coerceIn(0f, rdpW.toFloat()),
                                                ury.coerceIn(0f, rdpH.toFloat()),
                                                PointerAction.Up, 1, 0f,
                                            ),
                                        )
                                        directTouchDragStarted = false
                                    }

                                    if (inputMode == InputMode.DirectTouch && rdpeiSupported.value) {
                                        val id = rdpeiNextFingerId.value
                                        rdpeiNextFingerId.value = (id + 1) and 0x7F
                                        rdpeiFingerIds.value[c.id.value] = id
                                        val (rxs, rys) = transform.toRdp(c.position.x, c.position.y)
                                        val ok = port.onTouchContacts(
                                            listOf(
                                                TouchContact(
                                                    fingerId = id,
                                                    x = rxs.coerceIn(0f, rdpW.toFloat()).roundToInt(),
                                                    y = rys.coerceIn(0f, rdpH.toFloat()).roundToInt(),
                                                    phase = RemoteTouchPhase.Down,
                                                    pressure = 1024,
                                                ),
                                            ),
                                        )
                                        if (!ok) rdpeiSupported.value = false
                                    }
                                    haptic(HapticFeedbackConstants.VIRTUAL_KEY)
                                    c.consume()
                                }
                            }

                            // Update cached positions; consume changes we own.
                            for (c in event.changes) {
                                when (c.id) {
                                    primaryId -> primaryPos = c.position
                                    secondId -> secondPos = c.position
                                }
                                if (c.pressed) c.consume()
                            }

                            val pressedCount = event.changes.count { it.pressed }

                            if (twoFingerEntered && pressedCount >= 2) {
                                // Two-finger pan + (DirectTouch) pinch.
                                val cx = (primaryPos.x + secondPos.x) / 2f
                                val cy = (primaryPos.y + secondPos.y) / 2f
                                val ddx = secondPos.x - primaryPos.x
                                val ddy = secondPos.y - primaryPos.y
                                val dist = kotlin.math.sqrt((ddx * ddx + ddy * ddy).toDouble()).toFloat()
                                val dcx = cx - twoFingerLastCentroid.x
                                val dcy = cy - twoFingerLastCentroid.y
                                val ddist = dist - twoFingerLastDist
                                twoFingerLastCentroid = androidx.compose.ui.geometry.Offset(cx, cy)
                                twoFingerLastDist = dist
                                scrollAccumX += dcx
                                scrollAccumY += dcy
                                pinchAccum += ddist

                                val panMag = abs(dcx) + abs(dcy)
                                val pinchMag = abs(ddist)
                                val pinchPath = inputMode == InputMode.DirectTouch && pinchMag > panMag * 1.2f

                                val anchorX: Float
                                val anchorY: Float
                                if (isTrackpad) {
                                    anchorX = cursorX
                                    anchorY = cursorY
                                } else {
                                    val (rx, ry) = transform.toRdp(cx, cy)
                                    anchorX = rx.coerceIn(0f, rdpW.toFloat())
                                    anchorY = ry.coerceIn(0f, rdpH.toFloat())
                                }

                                if (pinchPath) {
                                    while (abs(pinchAccum) >= pinchPxPerDetent) {
                                        val sign = if (pinchAccum > 0) 1f else -1f
                                        pinchAccum -= sign * pinchPxPerDetent
                                        if (!ctrlHeld) {
                                            port.onKeyEvent(
                                                KeyEventPayload(
                                                    keyCode = android.view.KeyEvent.KEYCODE_CTRL_LEFT,
                                                    metaState = android.view.KeyEvent.META_CTRL_ON or
                                                        android.view.KeyEvent.META_CTRL_LEFT_ON,
                                                    action = KeyAction.Down,
                                                ),
                                            )
                                            ctrlHeld = true
                                        }
                                        port.onPointerEvent(
                                            PointerEvent(
                                                anchorX, anchorY,
                                                PointerAction.Hover, 0, sign * 120f,
                                            ),
                                        )
                                        twoFingerScrolled = true
                                    }
                                } else {
                                    // Vertical scroll: finger drag down → wheel scrolls down.
                                    // Windows wheel-down is negative. [scrollSign] inverts
                                    // the whole thing when the "Reverse scroll" pref is on.
                                    while (abs(scrollAccumY) >= scrollPxPerDetent) {
                                        val sign = if (scrollAccumY > 0) -1f else 1f
                                        scrollAccumY -= -sign * scrollPxPerDetent
                                        port.onPointerEvent(
                                            PointerEvent(
                                                anchorX, anchorY,
                                                PointerAction.Hover, 0, sign * 120f * scrollSign,
                                            ),
                                        )
                                        twoFingerScrolled = true
                                    }
                                    while (abs(scrollAccumX) >= scrollPxPerDetent) {
                                        val sign = if (scrollAccumX > 0) 1f else -1f
                                        scrollAccumX -= sign * scrollPxPerDetent
                                        port.onPointerEvent(
                                            PointerEvent(
                                                x = anchorX,
                                                y = anchorY,
                                                action = PointerAction.Hover,
                                                buttons = 0,
                                                wheelDelta = 0f,
                                                wheelDeltaH = sign * 120f * scrollSign,
                                            ),
                                        )
                                        twoFingerScrolled = true
                                    }
                                }

                                // Forward both contacts via RDPEI Move.
                                if (inputMode == InputMode.DirectTouch && rdpeiSupported.value) {
                                    val pFid = rdpeiFingerIds.value[primaryId.value] ?: 0
                                    val sFid = secondId?.let { rdpeiFingerIds.value[it.value] } ?: 0
                                    val (rxp, ryp) = transform.toRdp(primaryPos.x, primaryPos.y)
                                    val (rxs, rys) = transform.toRdp(secondPos.x, secondPos.y)
                                    val ok = port.onTouchContacts(
                                        listOf(
                                            TouchContact(
                                                fingerId = pFid,
                                                x = rxp.coerceIn(0f, rdpW.toFloat()).roundToInt(),
                                                y = ryp.coerceIn(0f, rdpH.toFloat()).roundToInt(),
                                                phase = RemoteTouchPhase.Move,
                                                pressure = 1024,
                                            ),
                                            TouchContact(
                                                fingerId = sFid,
                                                x = rxs.coerceIn(0f, rdpW.toFloat()).roundToInt(),
                                                y = rys.coerceIn(0f, rdpH.toFloat()).roundToInt(),
                                                phase = RemoteTouchPhase.Move,
                                                pressure = 1024,
                                            ),
                                        ),
                                    )
                                    if (!ok) rdpeiSupported.value = false
                                }
                            } else if (pressedCount == 1 && !twoFingerEntered) {
                                // Single-finger handling (cursor drag or mouse drag).
                                val dx = primaryPos.x - lastFingerPos.x
                                val dy = primaryPos.y - lastFingerPos.y
                                lastFingerPos = primaryPos
                                totalMoveAbsX = abs(primaryPos.x - downPos.x)
                                totalMoveAbsY = abs(primaryPos.y - downPos.y)

                                when (inputMode) {
                                    InputMode.Trackpad -> {
                                        if (transform.scale > 0f) {
                                            cursorX = (cursorX + dx / transform.scale)
                                                .coerceIn(0f, rdpW.toFloat())
                                            cursorY = (cursorY + dy / transform.scale)
                                                .coerceIn(0f, rdpH.toFloat())
                                        }
                                        port.onPointerEvent(
                                            PointerEvent(
                                                cursorX, cursorY,
                                                PointerAction.Move,
                                                buttons = if (trackpadDragHeld) 1 else 0,
                                                wheelDelta = 0f,
                                            ),
                                        )
                                    }
                                    InputMode.DirectTouch -> {
                                        // Start a real mouse drag once we cross the tap threshold.
                                        if (!directTouchDragStarted && !directTouchLongPressFired &&
                                            (totalMoveAbsX > tapThresholdPx ||
                                                totalMoveAbsY > tapThresholdPx)
                                        ) {
                                            directTouchDragStarted = true
                                            val (sxr, syr) = transform.toRdp(downPos.x, downPos.y)
                                            val scx = sxr.coerceIn(0f, rdpW.toFloat())
                                            val scy = syr.coerceIn(0f, rdpH.toFloat())
                                            port.onPointerEvent(
                                                PointerEvent(scx, scy, PointerAction.Hover, 0, 0f),
                                            )
                                            port.onPointerEvent(
                                                PointerEvent(scx, scy, PointerAction.Down, 1, 0f),
                                            )
                                        }
                                        if (directTouchDragStarted) {
                                            val (rxr, ryr) = transform.toRdp(primaryPos.x, primaryPos.y)
                                            port.onPointerEvent(
                                                PointerEvent(
                                                    rxr.coerceIn(0f, rdpW.toFloat()),
                                                    ryr.coerceIn(0f, rdpH.toFloat()),
                                                    PointerAction.Move, 1, 0f,
                                                ),
                                            )
                                        }
                                        if (rdpeiSupported.value && rdpeiPrimaryFingerId >= 0) {
                                            val (rxr, ryr) = transform.toRdp(primaryPos.x, primaryPos.y)
                                            val ok = port.onTouchContacts(
                                                listOf(
                                                    TouchContact(
                                                        fingerId = rdpeiPrimaryFingerId,
                                                        x = rxr.coerceIn(0f, rdpW.toFloat()).roundToInt(),
                                                        y = ryr.coerceIn(0f, rdpH.toFloat()).roundToInt(),
                                                        phase = RemoteTouchPhase.Move,
                                                        pressure = 1024,
                                                    ),
                                                ),
                                            )
                                            if (!ok) rdpeiSupported.value = false
                                        }
                                    }
                                }
                                markInteraction()
                            }

                            if (pressedCount == 0) gestureDone = true
                        }

                        // ── Gesture ended: emit closing actions ─────────────
                        // Release Ctrl if we were pinching.
                        if (ctrlHeld) {
                            port.onKeyEvent(
                                KeyEventPayload(
                                    keyCode = android.view.KeyEvent.KEYCODE_CTRL_LEFT,
                                    metaState = 0,
                                    action = KeyAction.Up,
                                ),
                            )
                        }
                        // Lift any RDPEI contacts still tracked.
                        if (rdpeiSupported.value && inputMode == InputMode.DirectTouch) {
                            val contacts = mutableListOf<TouchContact>()
                            rdpeiFingerIds.value[primaryId.value]?.let { fid ->
                                val (rxe, rye) = transform.toRdp(primaryPos.x, primaryPos.y)
                                contacts += TouchContact(
                                    fingerId = fid,
                                    x = rxe.coerceIn(0f, rdpW.toFloat()).roundToInt(),
                                    y = rye.coerceIn(0f, rdpH.toFloat()).roundToInt(),
                                    phase = RemoteTouchPhase.Up,
                                    pressure = 0,
                                )
                            }
                            secondId?.let { sid ->
                                rdpeiFingerIds.value[sid.value]?.let { fid ->
                                    val (rxe, rye) = transform.toRdp(secondPos.x, secondPos.y)
                                    contacts += TouchContact(
                                        fingerId = fid,
                                        x = rxe.coerceIn(0f, rdpW.toFloat()).roundToInt(),
                                        y = rye.coerceIn(0f, rdpH.toFloat()).roundToInt(),
                                        phase = RemoteTouchPhase.Up,
                                        pressure = 0,
                                    )
                                }
                            }
                            if (contacts.isNotEmpty()) {
                                val ok = port.onTouchContacts(contacts)
                                if (!ok) rdpeiSupported.value = false
                            }
                            rdpeiFingerIds.value.remove(primaryId.value)
                            secondId?.let { rdpeiFingerIds.value.remove(it.value) }
                        }

                        when (inputMode) {
                            InputMode.Trackpad -> {
                                if (trackpadDragHeld) {
                                    // Close the tap-and-half drag.
                                    port.onPointerEvent(
                                        PointerEvent(cursorX, cursorY, PointerAction.Up, 1, 0f),
                                    )
                                } else if (twoFingerEntered) {
                                    // Was this a quick stationary two-finger tap?
                                    val elapsed = System.currentTimeMillis() - twoFingerStartTimeMs
                                    val cx = (primaryPos.x + secondPos.x) / 2f
                                    val cy = (primaryPos.y + secondPos.y) / 2f
                                    val moved = abs(cx - twoFingerStartCentroid.x) > twoFingerTapMaxMovePx ||
                                        abs(cy - twoFingerStartCentroid.y) > twoFingerTapMaxMovePx
                                    if (!twoFingerScrolled && !moved && elapsed < twoFingerTapMaxDurationMs) {
                                        port.onPointerEvent(
                                            PointerEvent(cursorX, cursorY, PointerAction.Down, 2, 0f),
                                        )
                                        port.onPointerEvent(
                                            PointerEvent(cursorX, cursorY, PointerAction.Up, 2, 0f),
                                        )
                                    }
                                } else {
                                    // Single-finger tap. DEFER the click by
                                    // doubleTapDragWindowMs so a quick follow-up touchdown
                                    // can promote into tap-and-half drag — otherwise Windows
                                    // would see the eager click as the first half of a
                                    // double-click and treat tap 2's drag as "double-click
                                    // and drag" (open / select-word, never a held-button
                                    // drag).
                                    if (totalMoveAbsX < tapThresholdPx && totalMoveAbsY < tapThresholdPx) {
                                        val myMs = System.currentTimeMillis()
                                        trackpadLastTapMs.value = myMs
                                        val clickX = cursorX
                                        val clickY = cursorY
                                        scope.launch {
                                            delay(doubleTapDragWindowMs)
                                            // tap-and-half cancels by zeroing lastTapMs, so
                                            // the timestamps no longer match → skip emit.
                                            if (trackpadLastTapMs.value != myMs) return@launch
                                            trackpadLastTapMs.value = 0L
                                            port.onPointerEvent(
                                                PointerEvent(clickX, clickY, PointerAction.Hover, 0, 0f),
                                            )
                                            port.onPointerEvent(
                                                PointerEvent(clickX, clickY, PointerAction.Down, 1, 0f),
                                            )
                                            port.onPointerEvent(
                                                PointerEvent(clickX, clickY, PointerAction.Up, 1, 0f),
                                            )
                                        }
                                    }
                                }
                            }
                            InputMode.DirectTouch -> {
                                if (directTouchDragStarted) {
                                    val (rxe, rye) = transform.toRdp(primaryPos.x, primaryPos.y)
                                    port.onPointerEvent(
                                        PointerEvent(
                                            rxe.coerceIn(0f, rdpW.toFloat()),
                                            rye.coerceIn(0f, rdpH.toFloat()),
                                            PointerAction.Up, 1, 0f,
                                        ),
                                    )
                                } else if (twoFingerEntered || directTouchLongPressFired) {
                                    // Multi-finger gesture or long-press already produced output.
                                } else {
                                    // Single tap. Apply the double-tap → double-click rule.
                                    val (tapRx, tapRy) = transform.toRdp(downPos.x, downPos.y)
                                    val tcx = tapRx.coerceIn(0f, rdpW.toFloat())
                                    val tcy = tapRy.coerceIn(0f, rdpH.toFloat())
                                    val now = System.currentTimeMillis()
                                    val gap = now - directTouchLastTapMs.value
                                    val nearLast = abs(tcx - directTouchLastTapRdpX.value) < doubleTapMaxMoveRdp &&
                                        abs(tcy - directTouchLastTapRdpY.value) < doubleTapMaxMoveRdp
                                    val isDoubleTap = gap in 1..doubleTapWindowMs && nearLast
                                    port.onPointerEvent(
                                        PointerEvent(tcx, tcy, PointerAction.Hover, 0, 0f),
                                    )
                                    port.onPointerEvent(
                                        PointerEvent(tcx, tcy, PointerAction.Down, 1, 0f),
                                    )
                                    port.onPointerEvent(
                                        PointerEvent(tcx, tcy, PointerAction.Up, 1, 0f),
                                    )
                                    if (isDoubleTap) {
                                        port.onPointerEvent(
                                            PointerEvent(tcx, tcy, PointerAction.Down, 1, 0f),
                                        )
                                        port.onPointerEvent(
                                            PointerEvent(tcx, tcy, PointerAction.Up, 1, 0f),
                                        )
                                        directTouchLastTapMs.value = 0L
                                    } else {
                                        directTouchLastTapMs.value = now
                                        directTouchLastTapRdpX.value = tcx
                                        directTouchLastTapRdpY.value = tcy
                                    }
                                }
                            }
                        }
                    }
                }
            },
    ) {
        val screenWPx = constraints.maxWidth.toFloat()
        val screenHPx = constraints.maxHeight.toFloat()

        // ── Keyboard-follow pan ───────────────────────────────────────
        // When the soft keyboard rises in Trackpad mode, the bottom of the
        // RDP image disappears under it — including the virtual cursor if
        // it happened to be down there. Shift the surface (and the cursor
        // overlay) up by just enough that the cursor stays visible above
        // the keyboard (and above the aux row that sits on top of it).
        //
        // DirectTouch never pans: the keyboard button is hidden in that
        // mode, and the input model would break if absolute touches no
        // longer matched the rendered surface position.
        val imeBottomPx = WindowInsets.ime.getBottom(density)
        val cursorMarginPx = with(density) { 24.dp.toPx() }
        val rawCursorScreenY = cursorY * transform.scale + transform.offsetY
        val visibleBottomPx = auxRowBoundsRef.value?.top ?: (screenHPx - imeBottomPx)
        val targetPanY = if (inputMode == InputMode.Trackpad && imeBottomPx > 0) {
            (rawCursorScreenY + cursorMarginPx - visibleBottomPx).coerceAtLeast(0f)
        } else 0f
        val keyboardPanY by animateFloatAsState(
            targetValue = targetPanY,
            label = "keyboardPanY",
        )

        // Switching into DirectTouch with the keyboard up would leave the
        // user with a covered bottom half and no pan to compensate. Drop
        // the IME so the surface returns to a clean state.
        LaunchedEffect(inputMode) {
            if (inputMode == InputMode.DirectTouch) {
                val edit = imeEditTextRef.value
                if (edit != null) {
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(edit.windowToken, 0)
                }
            }
        }

        // Dock sizing: collapsed = 1 chevron (48dp) + 8dp padding×2 = 64dp
        //              edge-expanded = chevron + [Keyboard?] + Mouse + Menu + padding
        //              free-floating = [Keyboard?] + Mouse + Menu + padding
        // The Keyboard button is hidden in DirectTouch mode; shrink the bounds so
        // the dock-exclusion rect doesn't over-cover empty space next to the dock.
        val hasKeyboardButton = inputMode == InputMode.Trackpad
        val collapsedDockWPx = with(density) { 64.dp.toPx() }
        val edgeExpandedDockWPx = with(density) {
            val buttons = if (hasKeyboardButton) 4 else 3
            (16 + buttons * 48).dp.toPx()
        }
        val freeDockWPx = with(density) {
            val buttons = if (hasKeyboardButton) 3 else 2
            (16 + buttons * 48).dp.toPx()
        }
        val dockHPx = with(density) { 60.dp.toPx() }

        // Initialise position: right edge, upper 1/3 of screen.
        // Also re-clamp on every screen-size change (live resize / rotation / DeX
        // window resize) so the dock never strands itself outside the window.
        LaunchedEffect(screenWPx, screenHPx) {
            if (screenWPx <= 0f || screenHPx <= 0f) return@LaunchedEffect
            if (dockPos.x < 0f) {
                dockPos = Offset(
                    x = screenWPx - collapsedDockWPx,
                    y = screenHPx / 6f,
                )
            } else {
                // Dock always rests at an edge. Preserve whichever edge it was on:
                // steady-state x is 0f (left) or screenWPx - collapsedDockWPx (right),
                // so any positive x means "was on the right edge".
                val wasOnRight = dockPos.x > 0f
                val newX = if (wasOnRight) {
                    (screenWPx - collapsedDockWPx).coerceAtLeast(0f)
                } else {
                    0f
                }
                val newY = dockPos.y.coerceIn(0f, (screenHPx - dockHPx).coerceAtLeast(0f))
                if (newX != dockPos.x || newY != dockPos.y) {
                    dockPos = Offset(newX, newY)
                }
            }
        }

        val isOnRightEdge = dockPos.x >= screenWPx - collapsedDockWPx - 1f
        val isOnLeftEdge = dockPos.x in 0f..1f
        val isOnEdge = dockPos.x >= 0f && (isOnRightEdge || isOnLeftEdge)
        val showCollapsed = isOnEdge && !isEdgeExpanded

        val currentDockWPx = when {
            showCollapsed -> collapsedDockWPx
            isOnEdge -> edgeExpandedDockWPx
            else -> freeDockWPx
        }

        // When expanding on the right edge, shift the dock inward so it stays on screen.
        // Always clamp against the current window so a shrink-resize can't strand the
        // dock outside the visible area before the resize LaunchedEffect re-fires.
        val maxRenderX = (screenWPx - currentDockWPx).coerceAtLeast(0f)
        val renderDockX = when {
            isEdgeExpanded && isOnRightEdge -> (screenWPx - edgeExpandedDockWPx).coerceAtLeast(0f)
            dockPos.x >= 0f -> dockPos.x.coerceIn(0f, maxRenderX)
            else -> 0f
        }
        val renderDockY = if (dockPos.y >= 0f) {
            dockPos.y.coerceIn(0f, (screenHPx - dockHPx).coerceAtLeast(0f))
        } else 0f

        // Keep dock exclusion bounds current every frame so the outer pointerInput
        // always skips touches on the dock regardless of how fast it moves.
        SideEffect {
            dockBoundsRef.value = Rect(renderDockX, renderDockY, renderDockX + currentDockWPx, renderDockY + dockHPx)
        }

        // Re-engage pointer capture when the user clicks/taps the surface
        // after an ESC release. Called from RdpSurfaceView itself (touch
        // events and ACTION_BUTTON_PRESS), so it fires for mouse users on
        // DeX who have no way to reach the phone touchscreen. Skips clicks
        // that landed inside the floating dock — otherwise the dock would
        // be impossible to interact with once capture is released, since
        // the SurfaceView sits beneath it and consumes the down event
        // before any dock pointerInput can react.
        val reengageCapture: (Float, Float) -> Unit = { x, y ->
            val bounds = dockBoundsRef.value
            val inDock = bounds != null && bounds.contains(androidx.compose.ui.geometry.Offset(x, y))
            if (!inDock) {
                val surf = rdpSurfaceRef.value
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    surf != null &&
                    !surf.hasPointerCapture()
                ) {
                    surf.captureOnFocus = true
                    surf.requestFocus()
                    runCatching { surf.requestPointerCapture() }
                }
            }
        }

        AndroidView(
            factory = { ctx ->
                RdpSurfaceView(ctx).apply {
                    rdpSurfaceRef.value = this
                    captureOnFocus = true
                    onKeyIntercept = { ev -> handleSessionKeyEvent(ev) }
                    onSurfaceDown = reengageCapture
                    setCapturedPointerCallback { ev ->
                        handleHardwareMouseMotion(ev, captured = true)
                    }
                    holder.addCallback(
                        object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {}

                            override fun surfaceChanged(
                                holder: SurfaceHolder,
                                format: Int,
                                width: Int,
                                height: Int,
                            ) {
                                Log.d(
                                    "cRdpSession",
                                    "Remote surface ${width}x$height; profile session " +
                                        "${profileWidth(profile)}x${profileHeight(profile)}",
                                )
                                surfaceDims.value = width to height
                                onAttachSurface(holder.surface, width, height, settings.renderOptions)
                            }

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                onDetachSurface()
                            }
                        },
                    )
                }
            },
            update = { surfaceView ->
                // Keep callbacks fresh across recompositions so they close over
                // the latest `transform`, `sessionConnected`, etc.
                val wasCaptureOnFocus = surfaceView.captureOnFocus
                surfaceView.captureOnFocus = sessionConnected
                surfaceView.onKeyIntercept = { ev -> handleSessionKeyEvent(ev) }
                surfaceView.onSurfaceDown = reengageCapture
                surfaceView.setCapturedPointerCallback { ev ->
                    handleHardwareMouseMotion(ev, captured = true)
                }
                // When sessionConnected flips false→true, captureOnFocus is
                // now true but neither onAttachedToWindow nor onWindowFocusChanged
                // refires (View is already attached and focused). Manually kick
                // a capture request here so the mouse starts working immediately
                // on connect — without this, the user has to nudge the phone
                // touchscreen to coax DeX into engaging capture.
                if (sessionConnected && !wasCaptureOnFocus &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    !surfaceView.hasPointerCapture()
                ) {
                    surfaceView.requestFocus()
                    surfaceView.post {
                        runCatching { surfaceView.requestPointerCapture() }
                    }
                }
            },
            modifier = Modifier
                .fillMaxSize()
                // Visual-only translation when the keyboard is up; the SurfaceView
                // keeps its full layout size so FreeRDP's render target is unchanged.
                .absoluteOffset { IntOffset(0, -keyboardPanY.roundToInt()) },
        )

        // Cursor overlay for trackpad mode: prefer the server bitmap, fall back to
        // the locally-drawn Win11 arrow when the engine has nothing to show.
        if (inputMode == InputMode.Trackpad && surfaceW > 0) {
            TrackpadCursor(
                cursor = cursorFrame,
                cx = cursorX * transform.scale + transform.offsetX,
                // Cursor sits on top of the same surface, so apply the
                // same vertical shift the keyboard-follow pan applies above.
                cy = cursorY * transform.scale + transform.offsetY - keyboardPanY,
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 80.dp),
        )

        // ── Floating draggable dock ───────────────────────────────────
        if (dockPos.x >= 0f) {
            Box(
                modifier = Modifier
                    .offset { IntOffset(renderDockX.roundToInt(), renderDockY.roundToInt()) }
                    .wrapContentSize(),
            ) {
                Surface(
                    modifier = Modifier
                        .wrapContentSize()
                        .pointerInput(screenWPx, screenHPx) {
                            detectDragGestures(
                                onDragStart = { isEdgeExpanded = false },
                                onDragEnd = {
                                    // Always snap to whichever edge the dock center is
                                    // closer to — the dock never rests mid-screen.
                                    val rightEdgeX = (screenWPx - collapsedDockWPx).coerceAtLeast(0f)
                                    val dockCenterX = dockPos.x + collapsedDockWPx / 2f
                                    val newX = if (dockCenterX >= screenWPx / 2f) rightEdgeX else 0f
                                    dockPos = Offset(
                                        newX,
                                        dockPos.y.coerceIn(0f, (screenHPx - dockHPx).coerceAtLeast(0f)),
                                    )
                                },
                            ) { change, dragAmount ->
                                change.consume()
                                dockPos = Offset(
                                    x = (dockPos.x + dragAmount.x).coerceIn(0f, screenWPx - collapsedDockWPx),
                                    y = (dockPos.y + dragAmount.y).coerceIn(0f, screenHPx - dockHPx),
                                )
                            }
                        },
                    shape = RoundedCornerShape(28.dp),
                    color = DockBackground,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (showCollapsed) {
                            // Collapsed handle on edge: chevron pointing inward to expand.
                            DockButton(
                                icon = if (isOnRightEdge) {
                                    Icons.AutoMirrored.Filled.KeyboardArrowLeft
                                } else {
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight
                                },
                                onClick = { isEdgeExpanded = true },
                            )
                        } else {
                            // Edge-expanded: leading chevron collapses back to edge.
                            if (isOnEdge && isOnRightEdge) {
                                DockButton(
                                    icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    onClick = { isEdgeExpanded = false },
                                )
                            }
                            // Soft keyboard is only useful in Trackpad mode — typing
                                // in DirectTouch would also fight with the touch-event
                                // pass-through, and there's no pan to keep the surface
                                // visible under the IME. Hide the button there.
                            if (inputMode == InputMode.Trackpad) {
                                DockButton(
                                    icon = Icons.Default.Keyboard,
                                    onClick = {
                                        val editText = imeEditTextRef.value ?: return@DockButton
                                        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                                        // Toggle: if the IME is already up, tapping the
                                        // button again should dismiss it. imeBottomPx is
                                        // the live IME inset computed in the parent scope.
                                        if (imeBottomPx > 0) {
                                            imm.hideSoftInputFromWindow(editText.windowToken, 0)
                                        } else {
                                            editText.requestFocus()
                                            imm.showSoftInput(editText, InputMethodManager.SHOW_FORCED)
                                        }
                                    },
                                )
                            }
                            DockButton(
                                // Show the icon matching the CURRENT mode so the dock
                                // always tells the user which input scheme is live —
                                // tap to swap to the other.
                                icon = if (inputMode == InputMode.DirectTouch) {
                                    Icons.Default.TouchApp
                                } else {
                                    Icons.Default.Mouse
                                },
                                onClick = {
                                    inputMode = if (inputMode == InputMode.Trackpad) {
                                        InputMode.DirectTouch
                                    } else {
                                        InputMode.Trackpad
                                    }
                                    onTouchAsMouseChanged(inputMode == InputMode.Trackpad)
                                    val label = if (inputMode == InputMode.Trackpad) "Trackpad mode" else "Direct touch mode"
                                    scope.launch { snackbarHostState.showSnackbar(label) }
                                },
                            )
                            Box {
                                DockButton(
                                    icon = Icons.Default.Menu,
                                    onClick = { dockMenuOpen = true },
                                )
                                DropdownMenu(
                                    expanded = dockMenuOpen,
                                    onDismissRequest = { dockMenuOpen = false },
                                ) {
                                DropdownMenuItem(
                                    text = { Text(if (isFullscreen) "Exit fullscreen" else "Fullscreen") },
                                    leadingIcon = {
                                        Icon(
                                            if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        dockMenuOpen = false
                                        isFullscreen = !isFullscreen
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Resolution") },
                                    leadingIcon = { Icon(Icons.Default.Monitor, contentDescription = null) },
                                    onClick = {
                                        dockMenuOpen = false
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                "${profileWidth(profile)}×${profileHeight(profile)}",
                                            )
                                        }
                                    },
                                )
                                HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("Disconnect", color = DisconnectRed) },
                                        leadingIcon = {
                                            Icon(Icons.Default.Close, contentDescription = null, tint = DisconnectRed)
                                        },
                                        onClick = {
                                            dockMenuOpen = false
                                            onBack()
                                        },
                                    )
                                }
                            }
                            // Trailing chevron when on left edge: collapses back to the edge.
                            if (isOnEdge && isOnLeftEdge) {
                                DockButton(
                                    icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                    onClick = { isEdgeExpanded = false },
                                )
                            }
                        }
                    }
                }
            }
        }

        // Hidden 1dp EditText that hosts a custom InputConnection. The IC
        // converts the IME's commit/composing protocol into clean Down/Up
        // key events forwarded to the remote desktop. See RemoteImeEditText
        // for why we don't just use a TextWatcher.
        AndroidView(
            factory = { ctx ->
                RemoteImeEditText(ctx).apply {
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    isCursorVisible = false
                    setTextColor(android.graphics.Color.TRANSPARENT)
                    setHintTextColor(android.graphics.Color.TRANSPARENT)

                    onTextInput = { text ->
                        val kcm = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
                        for (ch in text) {
                            val events = kcm.getEvents(charArrayOf(ch)) ?: continue
                            for (ke in events) {
                                val kAction = when (ke.action) {
                                    AndroidKeyEvent.ACTION_DOWN -> KeyAction.Down
                                    AndroidKeyEvent.ACTION_UP -> KeyAction.Up
                                    else -> null
                                } ?: continue
                                if (kAction == KeyAction.Down) haptic(HapticFeedbackConstants.KEYBOARD_TAP)
                                markInteraction()
                                port.onKeyEvent(
                                    KeyEventPayload(
                                        keyCode = ke.keyCode,
                                        metaState = ke.metaState,
                                        action = kAction,
                                        scanCode = ke.scanCode,
                                    ),
                                )
                            }
                        }
                    }
                    onSpecialKey = { ev ->
                        val kAction = when (ev.action) {
                            AndroidKeyEvent.ACTION_DOWN -> KeyAction.Down
                            AndroidKeyEvent.ACTION_UP -> KeyAction.Up
                            else -> null
                        }
                        if (kAction != null) {
                            if (kAction == KeyAction.Down) haptic(HapticFeedbackConstants.KEYBOARD_TAP)
                            markInteraction()
                            port.onKeyEvent(
                                KeyEventPayload(
                                    keyCode = ev.keyCode,
                                    metaState = ev.metaState,
                                    action = kAction,
                                    scanCode = ev.scanCode,
                                ),
                            )
                        }
                    }
                    onBackspace = { count ->
                        repeat(count) {
                            haptic(HapticFeedbackConstants.KEYBOARD_TAP)
                            markInteraction()
                            port.onKeyEvent(
                                KeyEventPayload(
                                    keyCode = AndroidKeyEvent.KEYCODE_DEL,
                                    metaState = 0,
                                    action = KeyAction.Down,
                                ),
                            )
                            port.onKeyEvent(
                                KeyEventPayload(
                                    keyCode = AndroidKeyEvent.KEYCODE_DEL,
                                    metaState = 0,
                                    action = KeyAction.Up,
                                ),
                            )
                        }
                    }
                    // Hardware-key passthrough (physical keyboards, DeX). The
                    // custom InputConnection above doesn't see these because
                    // they bypass the IME entirely.
                    setOnKeyListener { _, keyCode, event ->
                        val kAction = when (event.action) {
                            AndroidKeyEvent.ACTION_DOWN -> KeyAction.Down
                            AndroidKeyEvent.ACTION_UP -> KeyAction.Up
                            else -> return@setOnKeyListener false
                        }
                        if (kAction == KeyAction.Down) haptic(HapticFeedbackConstants.KEYBOARD_TAP)
                        markInteraction()
                        port.onKeyEvent(
                            KeyEventPayload(
                                keyCode = keyCode,
                                metaState = event.metaState,
                                action = kAction,
                                scanCode = event.scanCode,
                            ),
                        )
                        true
                    }
                    imeEditTextRef.value = this
                }
            },
            modifier = Modifier
                .size(1.dp)
                .align(Alignment.TopStart),
        )

        // Aux key row pinned to the top of the soft keyboard. imePadding()
        // translates the row up by the IME inset so it tracks the keyboard's
        // open/close animation; the visibility gate hides it when no IME is up.
        val imeBottom = WindowInsets.ime.getBottom(density)
        if (imeBottom > 0 && settings.auxKeyRowKeys.isNotEmpty()) {
            AuxKeyRow(
                enabledIds = settings.auxKeyRowKeys,
                onKey = { entry, action ->
                    if (action == KeyAction.Down) haptic(HapticFeedbackConstants.KEYBOARD_TAP)
                    markInteraction()
                    port.onKeyEvent(
                        KeyEventPayload(
                            keyCode = entry.keyCode,
                            metaState = 0,
                            action = action,
                        ),
                    )
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .imePadding()
                    // Bounds are reported in this BoxWithConstraints' local
                    // coords, matching firstDown.position in the outer
                    // pointerInput — so the exclusion check works directly.
                    .onGloballyPositioned { coords ->
                        val pos = coords.positionInParent()
                        val sz = coords.size
                        auxRowBoundsRef.value = Rect(
                            left = pos.x,
                            top = pos.y,
                            right = pos.x + sz.width,
                            bottom = pos.y + sz.height,
                        )
                    },
            )
        } else if (auxRowBoundsRef.value != null) {
            // IME hidden → row not laid out → onGloballyPositioned won't fire.
            // Reset the exclusion rect so the area becomes RDP-clickable again.
            auxRowBoundsRef.value = null
        }
    }
}

@Composable
private fun DockButton(icon: ImageVector, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
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

private fun statusShort(state: SessionState): String = when (state) {
    is SessionState.Idle -> "Idle"
    SessionState.Connecting -> "Connecting…"
    is SessionState.Connected -> "${state.detail} · ↑${state.bytesSent} ↓${state.bytesReceived}"
    SessionState.Disconnecting -> "Disconnecting…"
    is SessionState.Disconnected -> "Disconnected: ${state.reason}"
    is SessionState.Error -> "Error"
}

@Composable
private fun DisconnectDialog(
    info: DisconnectInfo?,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    info ?: return
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Connection lost") },
        text = {
            Text(
                info.reason.ifBlank { "The remote session ended unexpectedly." },
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onRetry) { Text("Retry") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onCancel) { Text("Cancel") }
        },
    )
}

@Composable
internal fun ChallengeDialog(
    challenge: com.crdp.core.rdp.engine.EngineChallenge?,
    onResolve: (String, com.crdp.core.rdp.engine.ChallengeResponse) -> Unit,
) {
    challenge ?: return

    when (challenge) {
        is com.crdp.core.rdp.engine.EngineChallenge.Certificate -> CertificateDialog(
            host = challenge.host,
            port = challenge.port,
            subject = challenge.subject,
            issuer = challenge.issuer,
            fingerprint = challenge.fingerprint,
            changed = false,
            onAcceptOnce = { onResolve(challenge.id, com.crdp.core.rdp.engine.ChallengeResponse.AcceptOnce) },
            onAcceptAlways = { onResolve(challenge.id, com.crdp.core.rdp.engine.ChallengeResponse.AcceptAlways) },
            onReject = { onResolve(challenge.id, com.crdp.core.rdp.engine.ChallengeResponse.Reject) },
        )
        is com.crdp.core.rdp.engine.EngineChallenge.CertificateChanged -> CertificateDialog(
            host = challenge.host,
            port = challenge.port,
            subject = challenge.subject,
            issuer = challenge.issuer,
            fingerprint = challenge.fingerprint,
            changed = true,
            onAcceptOnce = { onResolve(challenge.id, com.crdp.core.rdp.engine.ChallengeResponse.AcceptOnce) },
            onAcceptAlways = { onResolve(challenge.id, com.crdp.core.rdp.engine.ChallengeResponse.AcceptAlways) },
            onReject = { onResolve(challenge.id, com.crdp.core.rdp.engine.ChallengeResponse.Reject) },
        )
        is com.crdp.core.rdp.engine.EngineChallenge.Auth -> AuthDialog(
            title = challenge.title,
            initialUsername = challenge.usernameHint,
            initialDomain = challenge.domainHint.orEmpty(),
            onSubmit = { user, pass, dom ->
                onResolve(
                    challenge.id,
                    com.crdp.core.rdp.engine.ChallengeResponse.Credentials(user, pass, dom.ifBlank { null }),
                )
            },
            onCancel = { onResolve(challenge.id, com.crdp.core.rdp.engine.ChallengeResponse.Reject) },
        )
    }
}

@Composable
private fun CertificateDialog(
    host: String,
    port: Int,
    subject: String,
    issuer: String,
    fingerprint: String,
    changed: Boolean,
    onAcceptOnce: () -> Unit,
    onAcceptAlways: () -> Unit,
    onReject: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onReject,
        title = {
            Text(if (changed) "Certificate changed" else "Verify certificate")
        },
        text = {
            Column {
                Text("$host:$port", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("Subject: $subject", fontSize = 12.sp)
                Text("Issuer: $issuer", fontSize = 12.sp)
                Text("Fingerprint: $fingerprint", fontSize = 11.sp)
                if (changed) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "The server's certificate has changed since last visit.",
                        color = DisconnectRed,
                        fontSize = 12.sp,
                    )
                }
            }
        },
        confirmButton = {
            Row {
                androidx.compose.material3.TextButton(onClick = onAcceptAlways) { Text("Always") }
                androidx.compose.material3.TextButton(onClick = onAcceptOnce) { Text("Once") }
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onReject) { Text("Reject") }
        },
    )
}

@Composable
private fun AuthDialog(
    title: String,
    initialUsername: String,
    initialDomain: String,
    onSubmit: (String, String, String) -> Unit,
    onCancel: () -> Unit,
) {
    var user by remember { mutableStateOf(initialUsername) }
    var pass by remember { mutableStateOf("") }
    var dom by remember { mutableStateOf(initialDomain) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title.ifBlank { "Sign in" }) },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text("Username") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                )
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = dom,
                    onValueChange = { dom = it },
                    label = { Text("Domain (optional)") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { onSubmit(user, pass, dom) },
            ) { Text("Sign in") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onCancel) { Text("Cancel") }
        },
    )
}
