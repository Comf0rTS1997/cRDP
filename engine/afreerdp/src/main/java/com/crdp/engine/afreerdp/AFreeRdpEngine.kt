package com.crdp.engine.afreerdp

import android.content.Context
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
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

    init {
        LibFreeRDP.loadNativeLibraries()
    }

    override suspend fun connect(params: RdpConnectParams): Result<Unit> = withContext(Dispatchers.IO) {
        if (instance != 0L) {
            return@withContext Result.failure(IllegalStateException("Engine already connected"))
        }
        userInitiatedDisconnect = false
        hasBeenConnected = false
        _cursor.value = null
        _state.value = EngineState.Connecting

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

    override fun sendPointer(x: Int, y: Int, buttons: Int, action: PointerAction, wheel: Int, hWheel: Int) {
        val inst = instance
        if (inst == 0L) return
        // FreeRDP cursor flags (subset):
        //   PTR_FLAGS_MOVE             0x0800
        //   PTR_FLAGS_DOWN             0x8000
        //   PTR_FLAGS_BUTTON1          0x1000  (left)
        //   PTR_FLAGS_BUTTON2          0x2000  (right)
        //   PTR_FLAGS_BUTTON3          0x4000  (middle)
        //   PTR_FLAGS_WHEEL            0x0200
        //   PTR_FLAGS_HWHEEL           0x0400
        //   PTR_FLAGS_WHEEL_NEGATIVE   0x0100
        var moveFlags = 0
        when (action) {
            PointerAction.Move, PointerAction.Hover -> moveFlags = moveFlags or 0x0800
            PointerAction.Down -> moveFlags = moveFlags or 0x8000 or buttonFlag(buttons)
            PointerAction.Up   -> moveFlags = moveFlags or buttonFlag(buttons)
        }
        if (moveFlags != 0) {
            LibFreeRDP.sendCursorEvent(inst, x, y, moveFlags)
        }
        // Wheel events ride their own cursor event — VWHEEL and HWHEEL share the
        // rotation byte / negative-sign flag, so they can't be combined in one PDU.
        if (wheel != 0) sendWheel(inst, x, y, axis = 0x0200, delta = wheel)
        if (hWheel != 0) sendWheel(inst, x, y, axis = 0x0400, delta = hWheel)
    }

    private fun sendWheel(inst: Long, x: Int, y: Int, axis: Int, delta: Int) {
        // Magnitude in the low 8 bits; sign carried by PTR_FLAGS_WHEEL_NEGATIVE.
        val magnitude = (if (delta < 0) -delta else delta).coerceAtMost(0xFF)
        var f = axis or magnitude
        if (delta < 0) f = f or 0x0100
        LibFreeRDP.sendCursorEvent(inst, x, y, f)
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
            _state.value = EngineState.Connected(
                detail = "FreeRDP ${LibFreeRDP.version()}",
                bytesSent = bytesSent,
                bytesReceived = bytesReceived,
            )
            connectFuture?.takeUnless { it.isCompleted }?.complete(Result.success(Unit))
        }

        override fun onConnectionFailure() {
            val inst = instance
            val msg = if (inst != 0L) LibFreeRDP.lastErrorString(inst).orEmpty() else ""
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
            val rawReason = if (inst != 0L) LibFreeRDP.lastErrorString(inst).orEmpty() else ""
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
            val rawReason = if (inst != 0L) LibFreeRDP.lastErrorString(inst).orEmpty() else ""
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
            // Clipboard plumbing deferred to v1.1.
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
        args += "/network:lan"
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
        // /dynamic-resolution intentionally OMITTED.
        //
        // The bundled libfreerdp-android.so registers `android_desktop_resize` as
        // its update->DesktopResize callback, and that handler only fires the
        // Java OnGraphicsResize callback — it does NOT call `gdi_resize`. Every
        // other FreeRDP client (X11 xf_sw_desktop_resize, SDL, Wayland) calls
        // gdi_resize first so the GDI primary buffer reallocates to the new
        // desktop dimensions BEFORE the next GFX frame lands.
        //
        // With /gfx:AVC444 + /dynamic-resolution, a successful DisplayControl
        // resize triggers a server-side desktop resize, but our GDI buffer stays
        // at the old size. The next AVC444 frame arrives at the new size,
        // gdi_OutputUpdate sees mismatched surface vs gdi dims, calls
        // freerdp_image_scale (which we don't fully support), gets
        // CHANNEL_RC_NULL_DATA back (error 16), and the rdpgfx channel tears
        // down the whole session. Confirmed in logcat after a DeX maximize.
        //
        // Until the native lib is rebuilt with a correct DesktopResize handler,
        // we omit the flag entirely so requestResolution() always returns false
        // and the viewmodel falls through to its reconnect-on-resize path.
        // Slower but doesn't drop the connection.
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
        if (p.cameraRedirect != com.crdp.core.rdp.engine.CameraRedirect.Disabled) {
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
        args += "/cert:ignore"
        args += "/sec:nla"
        // Restrict Negotiate SPNEGO to NTLM only; skips Kerberos (no KDC in direct-IP scenarios).
        args += "/auth-pkg-list:ntlm"
        // Bumped to TRACE for the rdpecam family to debug HAL/enumeration negotiation;
        // global stays at WARN to keep noise low. Switch back to a single
        // "/log-level:WARN" once camera redirection is verified working.
        args += "/log-level:WARN"
        args += "/log-filters:com.freerdp.channels.rdpecam-enum.client:DEBUG,com.freerdp.channels.rdpecam-dev.client:DEBUG,com.freerdp.channels.rdpecam-android.client:DEBUG"
        return args.toTypedArray()
    }

    @Suppress("unused")
    private suspend fun <T> awaitCallback(block: ((T) -> Unit) -> Unit): T =
        suspendCancellableCoroutine { cont -> block { cont.resume(it) } }

    private companion object {
        const val TAG = "AFreeRdpEngine"
    }
}
