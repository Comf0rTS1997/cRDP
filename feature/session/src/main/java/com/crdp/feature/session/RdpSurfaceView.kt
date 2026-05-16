package com.crdp.feature.session

import android.content.Context
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.SurfaceView

/**
 * SurfaceView subclass that mirrors termux-x11's `LorieView`:
 *
 *  - Focusable + focusableInTouchMode → receives key events directly.
 *  - `dispatchKeyEventPreIme` is the earliest View-layer hook that runs before
 *    the IME has a chance to swallow function keys / Alt+F4 / arrows.
 *  - `onWindowFocusChanged` re-asserts both key focus and pointer capture so
 *    the captured state survives transitions through immersive system bars,
 *    notification shade, and DeX desktop window switching.
 *
 * Two callbacks are exposed:
 *   [onKeyIntercept] — called from `dispatchKeyEventPreIme`. Return `true` to
 *     consume the event before it reaches IME / Compose. The session screen
 *     uses this to forward keys to FreeRDP and to special-case ESC.
 *   [onCapturedMotion] — called from `setOnCapturedPointerListener` while
 *     pointer capture is active; deltas arrive via AXIS_RELATIVE_X/Y.
 *
 * Why a custom View instead of leaning on the host ComposeView: pointer
 * capture and pre-IME key dispatch both require a focused View that is
 * focusable in touch mode AND attached at the time the request is made.
 * Compose's root view doesn't naturally satisfy this in immersive fullscreen
 * — the focus migrates briefly when system bars retract, and capture is
 * silently dropped. Owning the View lets us re-request on every focus regain.
 */
class RdpSurfaceView(context: Context) : SurfaceView(context) {

    /** Invoked from dispatchKeyEventPreIme. Return true to consume. */
    var onKeyIntercept: ((KeyEvent) -> Boolean)? = null

    /** Captured-pointer listener target; set externally via [setCapturedPointerCallback]. */
    private var capturedHandler: ((MotionEvent) -> Boolean)? = null

    /** If true, requestPointerCapture is called on every onWindowFocusChanged(true). */
    var captureOnFocus: Boolean = false

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    /**
     * Apply (or clear) the TYPE_NULL pointer icon. Only used while pointer
     * capture is active — leaving it on when capture is released hides the
     * DeX system cursor and the user can't see where they're aiming when
     * the RDP-virtual cursor isn't being driven by absolute hover coords.
     *
     * On release we explicitly set TYPE_DEFAULT (not null). Clearing the
     * override to null doesn't reliably get DeX to re-show the cursor —
     * the WindowManager appears to cache TYPE_NULL across the property
     * write and the arrow only comes back when an explicit non-null icon
     * lands.
     */
    private fun applyHiddenCursor(hidden: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        pointerIcon = PointerIcon.getSystemIcon(
            context,
            if (hidden) PointerIcon.TYPE_NULL else PointerIcon.TYPE_DEFAULT,
        )
    }

    /**
     * Called by [onTouchEvent] when a mouse button (or finger) goes down
     * while pointer capture is NOT held. Receives the local x/y of the
     * event so the session can skip clicks that landed on the floating
     * dock (otherwise dock buttons can never be reached once capture is
     * released). The session re-engages capture by setting [captureOnFocus].
     */
    var onSurfaceDown: ((Float, Float) -> Unit)? = null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            onSurfaceDown?.invoke(event.x, event.y)
        }
        return super.onTouchEvent(event)
    }

    /**
     * Called for mouse button presses delivered as ACTION_BUTTON_PRESS via
     * the generic-motion path while NOT pointer-captured. DeX delivers
     * these even when HOVER_MOVE is suppressed, so this is a reliable
     * "user wants focus back" signal on DeX.
     */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS) {
            onSurfaceDown?.invoke(event.x, event.y)
        }
        return super.onGenericMotionEvent(event)
    }

    fun setCapturedPointerCallback(cb: ((MotionEvent) -> Boolean)?) {
        capturedHandler = cb
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setOnCapturedPointerListener(
                if (cb == null) null
                else android.view.View.OnCapturedPointerListener { _, ev -> cb(ev) },
            )
        }
    }

    override fun dispatchKeyEventPreIme(event: KeyEvent): Boolean {
        val hook = onKeyIntercept
        if (hook != null) {
            val consumed = hook(event)
            Log.d(
                "cRdpKey",
                "RdpSurfaceView.preIme action=${event.action} kc=${event.keyCode} " +
                    "meta=0x${Integer.toHexString(event.metaState)} consumed=$consumed",
            )
            if (consumed) return true
        }
        return super.dispatchKeyEventPreIme(event)
    }

    override fun onCheckIsTextEditor(): Boolean = false

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        android.util.Log.d(
            "cRdpCap",
            "onWindowFocusChanged hasFocus=$hasWindowFocus hasViewFocus=$isFocused " +
                "captureOnFocus=$captureOnFocus hasPointerCapture=${
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) hasPointerCapture() else "n/a"
                }",
        )
        if (hasWindowFocus) {
            requestFocus()
            if (captureOnFocus &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !hasPointerCapture()
            ) {
                post {
                    val ok = runCatching { requestPointerCapture(); true }.getOrDefault(false)
                    android.util.Log.d("cRdpCap", "  → requestPointerCapture posted; hasCap=${hasPointerCapture()} ok=$ok")
                }
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val gotFocus = requestFocus()
        android.util.Log.d("cRdpCap", "onAttachedToWindow requestFocus()=$gotFocus hasWindowFocus=${hasWindowFocus()}")
        // onWindowFocusChanged only fires when the window's focus STATE changes.
        // If we're attached into an already-focused window (common — we're
        // created via AndroidView after the activity is up), that callback
        // never refires for us, and the capture request never lands. Mirror
        // the focus-change handler here so the initial entry path engages.
        if (hasWindowFocus() &&
            captureOnFocus &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !hasPointerCapture()
        ) {
            post {
                val ok = runCatching { requestPointerCapture(); true }.getOrDefault(false)
                android.util.Log.d("cRdpCap", "  attach→requestPointerCapture posted; hasCap=${hasPointerCapture()} ok=$ok")
            }
        }
    }

    override fun onPointerCaptureChange(hasCapture: Boolean) {
        super.onPointerCaptureChange(hasCapture)
        android.util.Log.d("cRdpCap", "onPointerCaptureChange hasCapture=$hasCapture")
        // Hide the DeX system cursor only while we own pointer capture.
        // Restore default icon on release so the user can still navigate
        // the surface to re-engage capture (tap to recapture).
        applyHiddenCursor(hasCapture)
    }
}
