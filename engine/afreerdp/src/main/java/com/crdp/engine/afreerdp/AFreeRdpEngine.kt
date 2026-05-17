package com.crdp.engine.afreerdp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.crdp.core.rdp.engine.ChallengeResponse
import com.crdp.core.rdp.engine.CursorFrame
import com.crdp.core.rdp.engine.EngineChallenge
import com.crdp.core.rdp.engine.EngineState
import com.crdp.core.rdp.engine.FrameUpdate
import com.crdp.core.rdp.engine.RdpConnectParams
import com.crdp.core.rdp.engine.RdpEngine
import com.crdp.core.rdp.engine.RenderOptions
import com.crdp.core.rdp.input.KeyAction
import com.crdp.core.rdp.input.PointerAction
import com.crdp.core.rdp.input.RemoteTouchPhase
import com.crdp.core.rdp.input.TouchContact
import com.freerdp.freerdpcore.services.LibFreeRDP
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.coroutines.resume

/**
 * RdpEngine implementation backed by aFreeRDP's native library set.
 *
 * One AFreeRdpEngine = one FreeRDP `instance` handle = one connection.
 * NOT a singleton (see DirectRdpSession's lifecycle note). The bound Hilt
 * scope is whatever scope DirectRdpSession lives in (per-session).
 */
class AFreeRdpEngine @Inject constructor(
    @ApplicationContext private val appContext: Context,
) : RdpEngine {

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _frames = MutableSharedFlow<FrameUpdate>(extraBufferCapacity = 64)
    override val frames: SharedFlow<FrameUpdate> = _frames.asSharedFlow()

    private val _challenges = MutableSharedFlow<EngineChallenge>(replay = 1, extraBufferCapacity = 16)
    override val challenges: SharedFlow<EngineChallenge> = _challenges.asSharedFlow()

    private val _cursor = MutableStateFlow<CursorFrame?>(null)
    override val cursor: StateFlow<CursorFrame?> = _cursor.asStateFlow()

    private val pendingChallenges = ConcurrentHashMap<String, CompletableDeferred<ChallengeResponse>>()
    private val blitter = SurfaceBlitter()

    private var instance: Long = 0L
    private var worker: ExecutorService? = null
    // Completed by onDisconnected; tearDownInstance waits on it before freeInstance.
    private var sessionEndedSignal: CompletableDeferred<Unit> =
        CompletableDeferred<Unit>().also { it.complete(Unit) }

    // True once the local app has called disconnect(). Used by onDisconnected to tell
    // a clean local teardown apart from a remote-initiated drop (server kick, network loss).
    @Volatile private var userInitiatedDisconnect: Boolean = false

    // True once we've reached Connected at least once. onDisconnecting() rewrites state
    // to Disconnecting before onDisconnected() fires, so we can't read state to learn
    // whether the session ever succeeded — track it explicitly.
    @Volatile private var hasBeenConnected: Boolean = false

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var clipboardSyncEnabledForSession: Boolean = false
    @Volatile private var clipboardBridgeActive: Boolean = false
    @Volatile private var ignoreNextLocalClip: Boolean = false
    @Volatile private var lastTextSentToRemote: String? = null

    // Resolved at connect() from params.printerShareEnabled AND the native
    // capability probe. buildArgs() reads this to decide whether to emit /printer.
    @Volatile private var printerArgEnabledForSession: Boolean = false

    private var clipListener: ClipboardManager.OnPrimaryClipChangedListener? = null

    private val clipDebounceRunnable = Runnable {
        if (!clipboardBridgeActive) return@Runnable
        val text = readPrimaryPlainText() ?: return@Runnable
        if (ignoreNextLocalClip) {
            ignoreNextLocalClip = false
            return@Runnable
        }
        if (text == lastTextSentToRemote) return@Runnable
        val exec = worker ?: return@Runnable
        val inst = instance
        if (inst == 0L) return@Runnable
        exec.execute {
            if (!clipboardBridgeActive || instance != inst) return@execute
            lastTextSentToRemote = text
            LibFreeRDP.sendClipboardData(inst, text)
        }
    }

    init {
        LibFreeRDP.loadNativeLibraries()
        // Install the camera-orientation sink. The UI side (SessionScreen via
        // SessionViewModel) pushes display rotation through CameraOrientationBridge
        // when the configuration changes; we forward it to the rdpecam native HAL.
        com.crdp.core.rdp.CameraOrientationBridge.installSink { degrees ->
            runCatching { LibFreeRDP.freerdp_set_camera_display_rotation(degrees) }
        }
    }

    override suspend fun connect(params: RdpConnectParams): Result<Unit> = withContext(Dispatchers.IO) {
        if (instance != 0L) {
            return@withContext Result.failure(IllegalStateException("Engine already connected"))
        }
        clipboardSyncEnabledForSession = params.clipboardSyncEnabled
        clipboardBridgeActive = false
        ignoreNextLocalClip = false
        lastTextSentToRemote = null
        userInitiatedDisconnect = false
        hasBeenConnected = false
        _cursor.value = null
        _state.value = EngineState.Connecting

        // Configure the native printer backend ONLY when libfreerdp-client3.so was
        // rebuilt with CHANNEL_PRINTER_CLIENT=ON. Without that rebuild the JNI
        // symbols don't exist and — worse — FreeRDP's rdpdr would try to dlopen
        // libprinter-client.so and kill the session if /printer is in argv.
        // See PrinterRedirectBridge.isNativeAvailable for the rationale.
        printerArgEnabledForSession = params.printerShareEnabled &&
            PrinterRedirectBridge.isNativeAvailable
        android.util.Log.i(
            TAG,
            "printer-share decision: paramsEnabled=${params.printerShareEnabled} " +
                "nativeAvail=${PrinterRedirectBridge.isNativeAvailable} " +
                "→ printerArgEnabledForSession=$printerArgEnabledForSession",
        )
        if (printerArgEnabledForSession) {
            // Resolve the spool dir lazily: external-files dir is created on-demand
            // (returns null if external storage is missing — fall back to internal).
            val dir = appContext.getExternalFilesDir(PRINTER_SPOOL_DIR_NAME)
                ?: java.io.File(appContext.filesDir, PRINTER_SPOOL_DIR_NAME).apply { mkdirs() }
            val cfg = runCatching {
                PrinterRedirectBridge.setSpoolDir(dir.absolutePath)
                PrinterRedirectBridge.setPrinterName(params.printerName.ifBlank { "cRDP" })
            }
            // If either setter fails at config time, the probe lied (or only some
            // symbols are registered). Disable /printer for this session so rdpdr
            // doesn't kick us with ERRCONNECT_POST_CONNECT_FAILED on every connect
            // — most painfully visible as a "connection lost" toast immediately
            // after every rotation/auto-resize-reconnect.
            if (cfg.isFailure) {
                android.util.Log.w(TAG, "PrinterRedirectBridge config failed: ${cfg.exceptionOrNull()?.message} — disabling /printer for this session")
                printerArgEnabledForSession = false
            }
        } else if (params.printerShareEnabled) {
            android.util.Log.w(
                TAG,
                "Printer share requested but native printer subsystem missing — " +
                    "rebuild FreeRDP with CHANNEL_PRINTER_CLIENT=ON. Skipping /printer argv.",
            )
        }

        val inst = LibFreeRDP.newInstance(appContext)
        if (inst == 0L) {
            _state.value = EngineState.Error("freerdp_new returned null")
            return@withContext Result.failure(RuntimeException("freerdp_new returned null"))
        }
        instance = inst
        LibFreeRDP.registerCallbacks(inst, callbacks)

        val args = buildArgs(params)
        if (!LibFreeRDP.parseArguments(inst, args)) {
            tearDownInstance()
            _state.value = EngineState.Error("Invalid FreeRDP arguments")
            return@withContext Result.failure(RuntimeException("freerdp_parse_arguments failed"))
        }

        val connectComplete = CompletableDeferred<Result<Unit>>()
        connectFuture = connectComplete

        val sessionEnded = CompletableDeferred<Unit>()
        sessionEndedSignal = sessionEnded

        // freerdp_connect is NON-BLOCKING: it creates the session thread and returns
        // true/false immediately. The session runs on the FreeRDP-internal thread;
        // callbacks (onConnectionSuccess/Failure/Disconnected) drive the rest.
        val exec = Executors.newSingleThreadExecutor { r ->
            Thread(r, "afreerdp-worker-${inst.toString(16)}").apply { isDaemon = true }
        }
        worker = exec
        exec.execute {
            try {
                val started = LibFreeRDP.connect(inst)
                if (!started) {
                    val msg = "freerdp_connect failed to start session thread"
                    if (!connectComplete.isCompleted)
                        connectComplete.complete(Result.failure(RuntimeException(msg)))
                    _state.value = EngineState.Error(msg)
                    sessionEnded.complete(Unit)
                }
                // started == true: session running asynchronously; callbacks drive the rest.
            } catch (t: Throwable) {
                if (!connectComplete.isCompleted)
                    connectComplete.complete(Result.failure(t))
                _state.value = EngineState.Error(t.message ?: "engine crashed")
                sessionEnded.complete(Unit)
            }
        }

        connectComplete.await()
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        if (instance == 0L) return@withContext
        userInitiatedDisconnect = true
        // If the session was already dropped from the remote side (state = Disconnected),
        // the FreeRDP session thread is already gone and onDisconnected() will never fire
        // — skip the 5-second wait by completing the signal up front.
        if (_state.value is EngineState.Disconnected) {
            sessionEndedSignal.complete(Unit)
        }
        _state.value = EngineState.Disconnecting
        tearDownInstance()
        _state.value = EngineState.Idle
    }

    private suspend fun tearDownInstance() {
        stopClipboardBridgeSync()
        val inst = instance
        val signal = sessionEndedSignal
        if (inst != 0L) {
            instance = 0L
            // Signal the session thread to stop, then wait for onDisconnected to confirm
            // it has finished. Only then is it safe to free the native instance.
            runCatching { LibFreeRDP.disconnect(inst) }
            withTimeoutOrNull(5_000) { signal.await() }
            LibFreeRDP.unregisterCallbacks(inst)
            runCatching { LibFreeRDP.freeInstance(inst) }
        }
        worker?.shutdownNow()
        worker = null
        blitter.release()
        val snapshot = pendingChallenges.toMap()
        pendingChallenges.clear()
        snapshot.values.forEach { it.complete(ChallengeResponse.Reject) }
    }

    override fun attachSurface(surface: Surface, width: Int, height: Int, options: RenderOptions) {
        // Read display refresh so the blitter paces to the actual panel rate
        // (90/120/144Hz devices unlock proportional throughput vs a hard 60Hz cap).
        val refreshHz = try {
            val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            @Suppress("DEPRECATION") wm.defaultDisplay?.refreshRate ?: 60f
        } catch (_: Throwable) { 60f }
        blitter.attach(surface, width, height, refreshHz, options)
    }

    override fun detachSurface() {
        blitter.detach()
    }

    override fun resolveChallenge(id: String, response: ChallengeResponse) {
        pendingChallenges.remove(id)?.complete(response)
    }

    override fun sendKey(scancode: Int, action: KeyAction, meta: Int) {
        val inst = instance
        if (inst == 0L) return
        LibFreeRDP.sendKeyEvent(inst, scancode, action == KeyAction.Down)
    }

    override fun requestResolution(width: Int, height: Int, dpiScale: Int): Boolean {
        val inst = instance
        if (inst == 0L) return false
        if (_state.value !is EngineState.Connected) return false
        if (width <= 0 || height <= 0) return false
        return runCatching {
            LibFreeRDP.sendMonitorLayout(inst, width, height, dpiScale.coerceIn(0, 500))
        }.getOrDefault(false)
    }

    override fun pushLocalClipboard(text: String): Boolean {
        if (!clipboardSyncEnabledForSession || !clipboardBridgeActive) return false
        if (text.isEmpty()) return false
        val inst = instance
        if (inst == 0L) return false
        val exec = worker ?: return false
        // Treat this as the authoritative local clipboard for dedupe so the
        // OnPrimaryClipChangedListener path doesn't fire a duplicate copy if it
        // ever wakes up — and clamp ignoreNextLocalClip so a remote echo of the
        // same string doesn't bounce back as a local change.
        if (text == lastTextSentToRemote) return true
        lastTextSentToRemote = text
        exec.execute {
            if (!clipboardBridgeActive || instance != inst) return@execute
            LibFreeRDP.sendClipboardData(inst, text)
        }
        return true
    }

    override fun sendPointer(x: Int, y: Int, buttons: Int, action: PointerAction, wheel: Int, wheelH: Int) {
        val inst = instance
        if (inst == 0L) return
        // FreeRDP cursor flags (subset):
        //   PTR_FLAGS_MOVE        0x0800
        //   PTR_FLAGS_DOWN        0x8000
        //   PTR_FLAGS_BUTTON1     0x1000  (left)
        //   PTR_FLAGS_BUTTON2     0x2000  (right)
        //   PTR_FLAGS_BUTTON3     0x4000  (middle)
        //   PTR_FLAGS_WHEEL          0x0200
        //   PTR_FLAGS_HWHEEL         0x0400
        //   PTR_FLAGS_WHEEL_NEGATIVE 0x0100  (direction bit — magnitude is unsigned)
        var base = 0
        when (action) {
            PointerAction.Move, PointerAction.Hover -> base = base or 0x0800
            PointerAction.Down -> base = base or 0x8000 or buttonFlag(buttons)
            PointerAction.Up -> base = base or buttonFlag(buttons)
        }
        // MS-RDPBCGR §2.2.8.1.1.3.1.1.3: wheel rotation is encoded as an
        // unsigned magnitude in the low 8 bits, with PTR_FLAGS_WHEEL_NEGATIVE
        // set for backward scrolls. The old encoding `wheel and 0xFF` flipped
        // sign for negative values (e.g., -120 → 0x88 = +136 forward), so
        // half of every two-finger scroll went the wrong way on Windows.
        if (wheel != 0) {
            val neg = if (wheel < 0) 0x0100 else 0
            val mag = kotlin.math.abs(wheel).coerceAtMost(0xFF)
            LibFreeRDP.sendCursorEvent(inst, x, y, base or 0x0200 or neg or mag)
        }
        if (wheelH != 0) {
            val neg = if (wheelH < 0) 0x0100 else 0
            val mag = kotlin.math.abs(wheelH).coerceAtMost(0xFF)
            LibFreeRDP.sendCursorEvent(inst, x, y, base or 0x0400 or neg or mag)
        }
        if (wheel == 0 && wheelH == 0) {
            LibFreeRDP.sendCursorEvent(inst, x, y, base)
        }
    }

    override fun sendTouchContacts(contacts: List<TouchContact>): Boolean {
        val inst = instance
        if (inst == 0L || contacts.isEmpty()) return false
        var ok = true
        for (c in contacts) {
            val flags = freerdpTouchFlags(c.phase)
            try {
                if (!LibFreeRDP.sendTouchContact(inst, flags, c.fingerId, c.pressure, c.x, c.y)) {
                    ok = false
                }
            } catch (_: UnsatisfiedLinkError) {
                return false
            }
        }
        return ok
    }

    private fun freerdpTouchFlags(phase: RemoteTouchPhase): Int = when (phase) {
        RemoteTouchPhase.Down -> FREERDP_TOUCH_DOWN
        RemoteTouchPhase.Move -> FREERDP_TOUCH_MOTION
        RemoteTouchPhase.Up -> FREERDP_TOUCH_UP
        RemoteTouchPhase.Cancel -> FREERDP_TOUCH_CANCEL
    }

    private fun buttonFlag(buttons: Int): Int = when (buttons) {
        2 -> 0x2000  // right
        3 -> 0x4000  // middle
        else -> 0x1000  // left / default
    }

    private var connectFuture: CompletableDeferred<Result<Unit>>? = null
    private var bytesSent = 0L
    private var bytesReceived = 0L

    private val callbacks = object : LibFreeRDP.AdapterCallbacks {
        override fun onPreConnect() {
            _state.value = EngineState.Connecting
        }

        override fun onConnectionSuccess() {
            hasBeenConnected = true
            if (clipboardSyncEnabledForSession) {
                clipboardBridgeActive = true
            }
            _state.value = EngineState.Connected(
                detail = "FreeRDP ${LibFreeRDP.version()}",
                bytesSent = bytesSent,
                bytesReceived = bytesReceived,
            )
            connectFuture?.takeUnless { it.isCompleted }?.complete(Result.success(Unit))
            if (clipboardSyncEnabledForSession) {
                mainHandler.post {
                    if (instance == 0L) {
                        clipboardBridgeActive = false
                        return@post
                    }
                    innerStartClipboardBridge()
                }
            }
        }

        override fun onConnectionFailure() {
            val inst = instance
            val msg = if (inst != 0L) sanitizeReason(LibFreeRDP.lastErrorString(inst)) else ""
            val text = msg.ifBlank { "Connection failed" }
            _state.value = EngineState.Error(text)
            connectFuture?.takeUnless { it.isCompleted }?.complete(Result.failure(RuntimeException(text)))
            // onDisconnected will follow and complete sessionEndedSignal.
        }

        override fun onDisconnecting() {
            // Empirically, FreeRDP's android client fires onDisconnecting but NOT
            // onDisconnected when the server initiates the drop (e.g., RPC-initiated
            // disconnect after another client takes over the session). So we have to
            // publish the terminal state from here, not wait for onDisconnected.
            val inst = instance
            val rawReason = if (inst != 0L) sanitizeReason(LibFreeRDP.lastErrorString(inst)) else ""
            val previous = _state.value
            android.util.Log.d(
                TAG,
                "onDisconnecting (userInitiated=$userInitiatedDisconnect, " +
                    "hasBeenConnected=$hasBeenConnected, prev=$previous, lastErr='$rawReason')",
            )
            when {
                // Local teardown: disconnect() owns the state transition (Disconnecting → Idle).
                userInitiatedDisconnect -> _state.value = EngineState.Disconnecting
                // onConnectionFailure already published the error; don't clobber it.
                previous is EngineState.Error -> Unit
                // We at least once reached Connected → this is a remote-initiated drop.
                hasBeenConnected ->
                    _state.value = EngineState.Disconnected(rawReason.ifBlank { "Remote session ended" })
                // Disconnected before we ever reached Connected (fast fail). Connect future
                // resolves below; leave the state machine alone so loadError drives the UI.
                else -> _state.value = EngineState.Disconnecting
            }
        }

        override fun onDisconnected() {
            // Often skipped by FreeRDP for remote-initiated drops; treat it as a fallback
            // for paths where it does fire (e.g., post-disconnect on the user-initiated path).
            val inst = instance
            val rawReason = if (inst != 0L) sanitizeReason(LibFreeRDP.lastErrorString(inst)) else ""
            val previous = _state.value
            android.util.Log.d(
                TAG,
                "onDisconnected (userInitiated=$userInitiatedDisconnect, hasBeenConnected=$hasBeenConnected, " +
                    "prev=$previous, lastErr='$rawReason')",
            )
            when {
                userInitiatedDisconnect -> Unit
                previous is EngineState.Error -> Unit
                // onDisconnecting already set Disconnected for the kick path — keep it.
                previous is EngineState.Disconnected -> Unit
                hasBeenConnected ->
                    _state.value = EngineState.Disconnected(rawReason.ifBlank { "Remote session ended" })
                else -> _state.value = EngineState.Idle
            }
            // If connection never succeeded (fast-fail before onConnectionSuccess/Failure),
            // unblock connect() so the caller gets an error instead of hanging forever.
            connectFuture?.takeUnless { it.isCompleted }
                ?.complete(Result.failure(RuntimeException("Disconnected before connection established")))
            sessionEndedSignal.complete(Unit)
        }

        override fun onAuthenticate(
            username: StringBuilder,
            domain: StringBuilder,
            password: StringBuilder,
        ): Boolean = blockOnChallenge(
            EngineChallenge.Auth(
                id = UUID.randomUUID().toString(),
                title = "Sign in",
                usernameHint = username.toString(),
                domainHint = domain.toString().ifBlank { null },
            ),
        ).let { resp ->
            if (resp is ChallengeResponse.Credentials) {
                username.setLength(0); username.append(resp.username)
                password.setLength(0); password.append(resp.password)
                domain.setLength(0); domain.append(resp.domain.orEmpty())
                true
            } else false
        }

        override fun onGatewayAuthenticate(
            username: StringBuilder,
            domain: StringBuilder,
            password: StringBuilder,
        ): Boolean = onAuthenticate(username, domain, password)

        override fun onVerifyCertificateEx(
            host: String,
            port: Long,
            commonName: String,
            subject: String,
            issuer: String,
            fingerprint: String,
            flags: Long,
        ): Int = blockOnChallenge(
            EngineChallenge.Certificate(
                id = UUID.randomUUID().toString(),
                host = host,
                port = port.toInt(),
                commonName = commonName,
                subject = subject,
                issuer = issuer,
                fingerprint = fingerprint,
                flags = flags,
            ),
        ).let { resp ->
            when (resp) {
                ChallengeResponse.AcceptAlways -> 1
                ChallengeResponse.AcceptOnce -> 2
                else -> 0
            }
        }

        override fun onVerifyChangedCertificateEx(
            host: String,
            port: Long,
            commonName: String,
            subject: String,
            issuer: String,
            fingerprint: String,
            oldSubject: String,
            oldIssuer: String,
            oldFingerprint: String,
            flags: Long,
        ): Int = blockOnChallenge(
            EngineChallenge.CertificateChanged(
                id = UUID.randomUUID().toString(),
                host = host,
                port = port.toInt(),
                commonName = commonName,
                subject = subject,
                issuer = issuer,
                fingerprint = fingerprint,
                oldSubject = oldSubject,
                oldIssuer = oldIssuer,
                oldFingerprint = oldFingerprint,
                flags = flags,
            ),
        ).let { resp ->
            when (resp) {
                ChallengeResponse.AcceptAlways -> 1
                ChallengeResponse.AcceptOnce -> 2
                else -> 0
            }
        }

        override fun onSettingsChanged(width: Int, height: Int, bpp: Int) {
            blitter.bitmapFor(width, height)
        }

        override fun onGraphicsUpdate(x: Int, y: Int, width: Int, height: Int) {
            val inst = instance
            if (inst == 0L) return
            val bmp = blitter.currentBitmap() ?: return
            LibFreeRDP.updateGraphics(inst, bmp, x, y, width, height)
            blitter.markDirty(x, y, width, height)
            // Blitter handles vsync-aware pacing internally; returns the union dirty
            // rect when it actually flushed, null when throttled. Emit one frame
            // event per real flush so consumers don't see per-rect chatter.
            val flushed = blitter.flushDirty()
            if (flushed != null) {
                _frames.tryEmit(FrameUpdate(flushed[0], flushed[1], flushed[2], flushed[3]))
            }
        }

        override fun onGraphicsResize(width: Int, height: Int, bpp: Int) {
            blitter.bitmapFor(width, height)
        }

        override fun onRemoteClipboardChanged(data: String) {
            if (!clipboardSyncEnabledForSession || !clipboardBridgeActive) return
            if (data.isEmpty()) return
            val inst = instance
            if (inst == 0L) return
            mainHandler.post {
                if (!clipboardBridgeActive || instance != inst) return@post
                if (readPrimaryPlainText() == data) return@post
                try {
                    ignoreNextLocalClip = true
                    val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("cRDP", data))
                } catch (_: Throwable) {
                    ignoreNextLocalClip = false
                }
            }
        }

        override fun requestSurfaceBitmap(): android.graphics.Bitmap =
            blitter.currentBitmap() ?: blitter.bitmapFor(blitterFallbackWidth(), blitterFallbackHeight())

        override fun onCursorBitmap(argb: ByteArray, width: Int, height: Int, hotX: Int, hotY: Int) {
            // Defensive: malformed payload would crash the renderer downstream.
            if (width <= 0 || height <= 0 || argb.size != width * height * 4) {
                _cursor.value = CursorFrame.Default
                return
            }
            _cursor.value = CursorFrame.Bitmap(argb, width, height, hotX, hotY)
        }

        override fun onCursorState(state: Int) {
            _cursor.value = when (state) {
                LibFreeRDP.CURSOR_STATE_HIDDEN -> CursorFrame.Hidden
                LibFreeRDP.CURSOR_STATE_DEFAULT -> CursorFrame.Default
                else -> CursorFrame.Default
            }
        }
    }

    private fun blitterFallbackWidth() = 1280
    private fun blitterFallbackHeight() = 720

    // freerdp_get_last_error_string returns the literal "Success." when no error code
    // was set — several disconnect paths tear down without setting one, so unfiltered
    // it surfaces as "Connection lost: Success." Treat as blank so callers fall back.
    private fun sanitizeReason(reason: String?): String {
        val trimmed = reason?.trim().orEmpty().trimEnd('.', ' ')
        if (trimmed.isEmpty()) return ""
        return if (trimmed.equals("Success", ignoreCase = true)) "" else reason!!.trim()
    }

    private fun readPrimaryPlainText(): String? {
        return try {
            val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip ?: return null
            if (clip.itemCount <= 0) return null
            val t = clip.getItemAt(0).coerceToText(appContext)?.toString() ?: return null
            if (t.isEmpty()) return null
            t
        } catch (_: Throwable) {
            null
        }
    }

    private fun innerStartClipboardBridge() {
        if (clipListener != null) return
        val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            if (!clipboardBridgeActive) return@OnPrimaryClipChangedListener
            mainHandler.removeCallbacks(clipDebounceRunnable)
            mainHandler.postDelayed(clipDebounceRunnable, CLIP_DEBOUNCE_MS)
        }
        clipListener = listener
        cm.addPrimaryClipChangedListener(listener)
    }

    private fun innerStopClipboardBridge() {
        mainHandler.removeCallbacks(clipDebounceRunnable)
        val listener = clipListener ?: return
        clipListener = null
        try {
            val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.removePrimaryClipChangedListener(listener)
        } catch (_: Throwable) {
        }
    }

    private fun stopClipboardBridgeSync() {
        clipboardBridgeActive = false
        if (Looper.myLooper() == Looper.getMainLooper()) {
            innerStopClipboardBridge()
            return
        }
        val latch = CountDownLatch(1)
        mainHandler.post {
            innerStopClipboardBridge()
            latch.countDown()
        }
        try {
            latch.await(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun blockOnChallenge(challenge: EngineChallenge): ChallengeResponse {
        val deferred = CompletableDeferred<ChallengeResponse>()
        pendingChallenges[challenge.id] = deferred
        // Best-effort emit; if no UI is collecting we will hang until disconnect tears down.
        _challenges.tryEmit(challenge)
        return runBlocking { deferred.await() }
    }

    private fun buildArgs(p: RdpConnectParams): Array<String> {
        val args = mutableListOf<String>()
        args += "freerdp"
        args += "/v:${p.host}"
        args += "/port:${p.port}"
        if (p.username.isNotBlank()) args += "/u:${p.username}"
        if (!p.domain.isNullOrBlank()) args += "/d:${p.domain}"
        if (p.password.isNotBlank()) args += "/p:${p.password}"
        args += "/size:${p.width}x${p.height}"
        args += "/bpp:${p.colorDepth}"
        // FreeRDP /scale-desktop: 100..500. Skip when 100 to keep argv minimal.
        // Per MS-RDPBCGR §2.2.1.3.2, Windows servers ignore DesktopScaleFactor
        // unless DeviceScaleFactor is also one of {100, 140, 180}, so we derive
        // the closest device bucket and pair them. Without this the percent the
        // user picks is silently dropped on real Windows hosts.
        val scale = p.desktopScaleFactor.coerceIn(100, 500)
        if (scale != 100) {
            args += "/scale-desktop:$scale"
            val deviceScale = when {
                scale < 120 -> 100
                scale < 160 -> 140
                else -> 180
            }
            args += "/scale-device:$deviceScale"
        }
        args += "/gdi:hw"
        // /network:auto enables NetworkAutoDetect (MS-RDPBCGR §2.2.14 — RTT and
        // bandwidth probes round-trip with the server) on top of a LAN baseline.
        // Servers with adaptive GFX (Win8+/Server 2012+) throttle H.264 quality
        // on low-bandwidth links; servers without it ignore the probes. The
        // fixed /network:lan path is kept as an escape hatch for rare cases
        // where the autodetect handshake misbehaves.
        args += if (p.networkAutoDetect) "/network:auto" else "/network:lan"
        // Enable MS-RDPEGFX with H.264 AVC444 when the bundled FreeRDP was built
        // with libavcodec (true on arm64/armv7 builds — see jniLibs/libavcodec.so).
        // Without this the server falls back to per-bitmap-rect updates, which is
        // why the Android client looks dramatically slower than mstsc — desktop
        // animations and scrolling end up sending raw pixels instead of an H.264
        // video stream. `/network:lan` alone does NOT pick H.264 even when the
        // codec is present.
        if (LibFreeRDP.hasH264Support()) {
            args += "/gfx:AVC444,progressive,RFX"
        } else {
            args += "/gfx:progressive,RFX"
        }
        // The deprecated `/bitmap-cache`, `/glyph-cache`, `/offscreen-cache` flags
        // are absent from this libfreerdp build (built without
        // WITH_FREERDP_DEPRECATED_COMMANDLINE), so use the unified `/cache:` form.
        args += "/cache:bitmap,glyph,offscreen"
        // Negotiate the DisplayControl DVC so requestResolution() can push
        // a new monitor layout mid-session. Rotations and window-size
        // changes go through SessionViewModel.onWindowSizeAvailable →
        // requestResolution → LibFreeRDP.sendMonitorLayout instead of
        // a full disconnect+reconnect cycle.
        args += "/dynamic-resolution"
        val qualityTok = when (p.audioQuality) {
            com.crdp.core.rdp.engine.AudioQuality.Dynamic -> "dynamic"
            com.crdp.core.rdp.engine.AudioQuality.Medium -> "medium"
            com.crdp.core.rdp.engine.AudioQuality.High -> "high"
        }
        when (p.audioPlayback) {
            com.crdp.core.rdp.engine.AudioPlayback.LocalDevice ->
                args += "/sound:sys:opensles,quality:$qualityTok"
            com.crdp.core.rdp.engine.AudioPlayback.RemoteConsole ->
                args += "/audio-mode:1"
            com.crdp.core.rdp.engine.AudioPlayback.Disabled ->
                args += "/audio-mode:2"
        }
        if (p.microphoneEnabled) args += "/microphone:sys:opensles"
        // rdpecam DVC. The Android HAL surfaces device ids like "front", "back",
        // "external:0", "usb:vid_pid". Built-in Front/Back/External map to a wildcard
        // plus a hint the HAL filters on; Specific pins to the chosen native id.
        //
        // Hard gate: if the user hasn't granted CAMERA permission, do NOT add the
        // rdpecam DVC arg. The native HAL otherwise opens Camera2 inside the FreeRDP
        // worker thread and crashes (no permission → SecurityException across the JNI
        // boundary, which manifests as a native abort). The user's runtime grant comes
        // through the activity result on the next reconnect.
        val cameraPermitted = androidx.core.content.ContextCompat.checkSelfPermission(
            appContext,
            android.Manifest.permission.CAMERA,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (p.cameraRedirect != com.crdp.core.rdp.engine.CameraRedirect.Disabled && cameraPermitted) {
            val devTok = when (p.cameraRedirect) {
                com.crdp.core.rdp.engine.CameraRedirect.BuiltInFront -> "front"
                com.crdp.core.rdp.engine.CameraRedirect.BuiltInBack -> "back"
                com.crdp.core.rdp.engine.CameraRedirect.External -> "external"
                com.crdp.core.rdp.engine.CameraRedirect.Specific -> p.cameraDeviceId ?: "*"
                com.crdp.core.rdp.engine.CameraRedirect.Disabled -> "*"
            }
            val enc = if (p.cameraEncode) "1" else "0"
            args += "/dvc:rdpecam,device:$devTok,encode:$enc,quality:2"
        }
        // Printer redirection: gated on printerArgEnabledForSession (NOT
        // p.printerShareEnabled directly). When the native printer subsystem
        // isn't compiled into libfreerdp-client3.so, emitting /printer makes
        // rdpdr try to dlopen the missing libprinter-client.so and tears the
        // session down with ERRCONNECT_POST_CONNECT_FAILED — connections then
        // immediately drop and reconnect in a loop. See connect() for the gate.
        if (printerArgEnabledForSession) {
            val safeName = p.printerName.ifBlank { "cRDP" }
                .replace(',', ' ').replace(':', ' ').trim()
            // Driver hint "Microsoft Print to PDF" matches Windows' in-box PDF
            // virtual printer (present on Win10 1809+ and Win11). The server
            // installs the redirected printer with that driver, so when an app
            // prints to it the spool payload arrives as a PDF instead of XPS /
            // PostScript. PDF lands naturally in android.print.PrintManager
            // (which accepts only PDF) and in any external PDF viewer.
            //
            // The exact-match gotcha that bit us earlier ("Microsoft XPS
            // Document Writer v4" was silently dropped) doesn't apply here:
            // "Microsoft Print to PDF" is shipped in the in-box driver list.
            // If it ever isn't (e.g. Server core), Windows falls back to Easy
            // Print and we still get XPS — the spool watcher handles either.
            args += "/printer:$safeName,Microsoft Print to PDF"
        }
        args += "/multitouch"
        if (p.keyboardLayoutId != 0) {
            args += "/kbd:lang:0x%04X".format(p.keyboardLayoutId)
        }
        args += "/sec:nla"
        // Restrict Negotiate SPNEGO to NTLM only; skips Kerberos (no KDC in direct-IP scenarios).
        args += "/auth-pkg-list:ntlm"
        args += "/log-level:WARN"
        // Bump rdpdr + printer channel logs to DEBUG so we can see why the
        // virtual printer isn't appearing on the server. Override is per-tag
        // so the rest of the log stays quiet.
        if (printerArgEnabledForSession) {
            args += "/log-filters:com.freerdp.channels.rdpdr.client:TRACE,com.freerdp.channels.printer.client:TRACE,com.freerdp.channels.printer.client.android:TRACE"
        }
        android.util.Log.i(TAG, "freerdp argv: ${args.joinToString(" ")}")
        return args.toTypedArray()
    }

    @Suppress("unused")
    private suspend fun <T> awaitCallback(block: ((T) -> Unit) -> Unit): T =
        suspendCancellableCoroutine { cont -> block { cont.resume(it) } }

    private companion object {
        const val TAG = "AFreeRdpEngine"
        const val CLIP_DEBOUNCE_MS = 150L
        const val PRINTER_SPOOL_DIR_NAME = "printer_spool"

        // FreeRDP client/client.c — FreeRDPTouchEventType
        const val FREERDP_TOUCH_DOWN = 0x01
        const val FREERDP_TOUCH_UP = 0x02
        const val FREERDP_TOUCH_MOTION = 0x04
        const val FREERDP_TOUCH_CANCEL = 0x08
    }
}
