package com.crdp.feature.session

import android.app.Activity
import android.os.Build
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.InputDevice
import android.view.MotionEvent
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.Mouse
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
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.KeyCharacterMap
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.ui.text.AnnotatedString
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
)

@Composable
fun SessionRoute(
    viewModel: SessionViewModel,
    onBack: () -> Unit,
    settings: SessionUserSettings = SessionUserSettings(),
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
) {
    val port = sessionReady.port
    val sessionState by port.sessionState.collectAsStateWithLifecycle()
    val cursorFrame by port.cursor.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }
    val clipboardManager = LocalClipboardManager.current
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

    // Virtual cursor position in RDP space (used in Trackpad mode).
    var cursorX by remember { mutableStateOf(rdpW / 2f) }
    var cursorY by remember { mutableStateOf(rdpH / 2f) }
    var lastFingerX by remember { mutableStateOf(0f) }
    var lastFingerY by remember { mutableStateOf(0f) }
    var fingerDownX by remember { mutableStateOf(0f) }
    var fingerDownY by remember { mutableStateOf(0f) }
    val tapThresholdPx = with(density) { 12.dp.toPx() }

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
                    hWheelDelta = 0f,
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
                    if (pressed) haptic(HapticFeedbackConstants.VIRTUAL_KEY)
                    port.onPointerEvent(
                        PointerEvent(
                            x = cursorX,
                            y = cursorY,
                            action = if (pressed) PointerAction.Down else PointerAction.Up,
                            buttons = id,
                            wheelDelta = 0f,
                            hWheelDelta = 0f,
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
                        // so pass through without flipping.
                        wheelDelta = vsc * 120f,
                        hWheelDelta = hsc * 120f,
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
    val sessionConnected = sessionState is SessionState.Connected

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

    // Consume the system Back gesture/key while a session is live so it goes to
    // the remote desktop instead of popping the session screen. Disconnect is
    // available via the dock menu. onPreviewKeyEvent already forwards the keystroke
    // before BackHandler fires, so the server sees the KEYCODE_BACK event.
    BackHandler(enabled = sessionConnected) { /* swallow */ }

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
            // away from the SurfaceView, breaking pointer capture in immersive
            // fullscreen (no system bars to trigger a focus regain → no recovery).
            .pointerInput(port, inputMode, transform) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        // Hardware mouse + stylus go through setOnCapturedPointerListener /
                        // setOnGenericMotionListener on the host View. If any pointer in
                        // this Compose event isn't of type Touch, drop the whole event so
                        // we don't double-dispatch through the touchscreen path.
                        if (event.changes.any { it.type != PointerType.Touch }) continue
                        event.changes.forEach { change ->
                            // Skip touches that land on the floating dock.
                            val bounds = dockBoundsRef.value
                            if (bounds != null && bounds.contains(change.position)) return@forEach
                            if (change.isConsumed) return@forEach
                            val rawAction = when {
                                change.pressed && !change.previousPressed -> PointerAction.Down
                                !change.pressed && change.previousPressed -> PointerAction.Up
                                change.previousPressed -> PointerAction.Move
                                else -> PointerAction.Hover
                            }

                            // Any touch reaching the RDP surface collapses an edge-expanded dock.
                            // It also re-engages pointer capture if a prior ESC released it
                            // (this is the "tap surface to recapture" cue from the snackbar).
                            if (rawAction == PointerAction.Down) {
                                isEdgeExpandedState.value = false
                                val surf = rdpSurfaceRef.value
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                                    sessionConnected &&
                                    surf != null &&
                                    !surf.hasPointerCapture()
                                ) {
                                    surf.captureOnFocus = true
                                    surf.requestFocus()
                                    runCatching { surf.requestPointerCapture() }
                                }
                            }

                            when (inputMode) {
                                InputMode.Trackpad -> {
                                    when (rawAction) {
                                        PointerAction.Down -> {
                                            haptic(HapticFeedbackConstants.VIRTUAL_KEY)
                                            markInteraction()
                                            lastFingerX = change.position.x
                                            lastFingerY = change.position.y
                                            fingerDownX = change.position.x
                                            fingerDownY = change.position.y
                                            // Sync server cursor to our virtual position.
                                            port.onPointerEvent(
                                                PointerEvent(
                                                    x = cursorX,
                                                    y = cursorY,
                                                    action = PointerAction.Hover,
                                                    buttons = 0,
                                                    wheelDelta = 0f,
                                                ),
                                            )
                                        }
                                        PointerAction.Move -> {
                                            markInteraction()
                                            val dx = change.position.x - lastFingerX
                                            val dy = change.position.y - lastFingerY
                                            lastFingerX = change.position.x
                                            lastFingerY = change.position.y
                                            if (transform.scale > 0f) {
                                                cursorX = (cursorX + dx / transform.scale)
                                                    .coerceIn(0f, rdpW.toFloat())
                                                cursorY = (cursorY + dy / transform.scale)
                                                    .coerceIn(0f, rdpH.toFloat())
                                            }
                                            port.onPointerEvent(
                                                PointerEvent(
                                                    x = cursorX,
                                                    y = cursorY,
                                                    action = PointerAction.Move,
                                                    buttons = 1,
                                                    wheelDelta = 0f,
                                                ),
                                            )
                                        }
                                        PointerAction.Up -> {
                                            markInteraction()
                                            val totalDx = abs(change.position.x - fingerDownX)
                                            val totalDy = abs(change.position.y - fingerDownY)
                                            if (totalDx < tapThresholdPx && totalDy < tapThresholdPx) {
                                                // Tap = left click at virtual cursor position.
                                                port.onPointerEvent(
                                                    PointerEvent(
                                                        x = cursorX,
                                                        y = cursorY,
                                                        action = PointerAction.Down,
                                                        buttons = 1,
                                                        wheelDelta = 0f,
                                                    ),
                                                )
                                                port.onPointerEvent(
                                                    PointerEvent(
                                                        x = cursorX,
                                                        y = cursorY,
                                                        action = PointerAction.Up,
                                                        buttons = 1,
                                                        wheelDelta = 0f,
                                                    ),
                                                )
                                            }
                                        }
                                        PointerAction.Hover -> {
                                            // Finger hover doesn't fire on touchscreens; ignore.
                                        }
                                    }
                                }

                                InputMode.DirectTouch -> {
                                    val (rdpX, rdpY) = transform.toRdp(
                                        change.position.x,
                                        change.position.y,
                                    )
                                    val clampedX = rdpX.coerceIn(0f, rdpW.toFloat())
                                    val clampedY = rdpY.coerceIn(0f, rdpH.toFloat())

                                    if (rawAction == PointerAction.Down) {
                                        haptic(HapticFeedbackConstants.VIRTUAL_KEY)
                                        markInteraction()
                                    } else if (rawAction == PointerAction.Up || rawAction == PointerAction.Move) {
                                        markInteraction()
                                    }

                                    port.onPointerEvent(
                                        PointerEvent(
                                            x = clampedX,
                                            y = clampedY,
                                            action = rawAction,
                                            buttons = if (change.pressed) 1 else 0,
                                            wheelDelta = 0f,
                                        ),
                                    )
                                }
                            }

                            change.consume()
                        }
                    }
                }
            },
    ) {
        val screenWPx = constraints.maxWidth.toFloat()
        val screenHPx = constraints.maxHeight.toFloat()

        // Dock sizing: collapsed = 1 chevron (48dp) + 8dp padding×2 = 64dp
        //              edge-expanded = chevron + Keyboard + Mouse + Menu (4×48=192) + padding = 208dp
        //              free-floating = Keyboard + Mouse + Menu (144) + padding = 160dp
        val collapsedDockWPx = with(density) { 64.dp.toPx() }
        val edgeExpandedDockWPx = with(density) { 208.dp.toPx() }
        val freeDockWPx = with(density) { 160.dp.toPx() }
        val dockHPx = with(density) { 60.dp.toPx() }
        val snapPx = with(density) { 64.dp.toPx() }

        // Initialise position: right edge, upper 1/3 of screen.
        LaunchedEffect(screenWPx, screenHPx) {
            if (dockPos.x < 0f && screenWPx > 0f && screenHPx > 0f) {
                dockPos = Offset(
                    x = screenWPx - collapsedDockWPx,
                    y = screenHPx / 6f,
                )
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
        val renderDockX = when {
            isEdgeExpanded && isOnRightEdge -> (screenWPx - edgeExpandedDockWPx).coerceAtLeast(0f)
            dockPos.x >= 0f -> dockPos.x
            else -> 0f
        }
        val renderDockY = if (dockPos.y >= 0f) dockPos.y.coerceIn(0f, screenHPx - dockHPx) else 0f

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
            modifier = Modifier.fillMaxSize(),
        )

        // Cursor overlay for trackpad mode: prefer the server bitmap, fall back to
        // the locally-drawn Win11 arrow when the engine has nothing to show.
        if (inputMode == InputMode.Trackpad && surfaceW > 0) {
            TrackpadCursor(
                cursor = cursorFrame,
                cx = cursorX * transform.scale + transform.offsetX,
                cy = cursorY * transform.scale + transform.offsetY,
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
                                    val rightSnap = screenWPx - collapsedDockWPx
                                    val newX = when {
                                        dockPos.x >= rightSnap - snapPx -> rightSnap
                                        dockPos.x <= snapPx -> 0f
                                        else -> dockPos.x
                                    }.coerceIn(0f, screenWPx - collapsedDockWPx)
                                    dockPos = Offset(newX, dockPos.y.coerceIn(0f, screenHPx - dockHPx))
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
                            DockButton(
                                icon = Icons.Default.Keyboard,
                                onClick = {
                                    val editText = imeEditTextRef.value ?: return@DockButton
                                    editText.requestFocus()
                                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                                    imm.showSoftInput(editText, InputMethodManager.SHOW_FORCED)
                                },
                            )
                            DockButton(
                                icon = Icons.Default.Mouse,
                                onClick = {
                                    inputMode = if (inputMode == InputMode.Trackpad) {
                                        InputMode.DirectTouch
                                    } else {
                                        InputMode.Trackpad
                                    }
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
                                DropdownMenuItem(
                                    text = { Text("File transfer") },
                                    leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null) },
                                    onClick = {
                                        dockMenuOpen = false
                                        scope.launch { snackbarHostState.showSnackbar("File transfer not available") }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Copy address") },
                                    onClick = {
                                        dockMenuOpen = false
                                        val addr = when (profile) {
                                            is DirectConnectionProfile -> profile.host
                                            is GatewayConnectionProfile -> profile.gatewayBaseUrl
                                        }
                                        clipboardManager.setText(AnnotatedString(addr))
                                        scope.launch { snackbarHostState.showSnackbar("Address copied") }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Session info") },
                                    onClick = {
                                        dockMenuOpen = false
                                        scope.launch {
                                            val info = when (val s = sessionState) {
                                                is SessionState.Connected ->
                                                    "↑${s.bytesSent}B ↓${s.bytesReceived}B · ${s.detail}"
                                                else -> statusShort(sessionState)
                                            }
                                            snackbarHostState.showSnackbar(info)
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

        // Hidden 1dp EditText that provides an InputConnection for soft-keyboard input.
        // Characters committed via IME are forwarded to the RDP session as key events.
        AndroidView(
            factory = { ctx ->
                EditText(ctx).apply {
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    isCursorVisible = false
                    setTextColor(android.graphics.Color.TRANSPARENT)
                    setHintTextColor(android.graphics.Color.TRANSPARENT)
                    imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    var ignoreChange = false
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                        override fun afterTextChanged(s: Editable?) {
                            if (ignoreChange) return
                            val text = s?.toString() ?: return
                            if (text.isEmpty()) return
                            ignoreChange = true
                            s?.clear()
                            ignoreChange = false
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
                    })
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
