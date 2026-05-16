package com.crdp.core.rdp.engine

import android.view.Surface
import com.crdp.core.rdp.input.KeyAction
import com.crdp.core.rdp.input.PointerAction
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The replaceable boundary between session orchestration ([com.crdp.core.rdp.RdpSessionPort])
 * and an actual RDP transport implementation. No third-party RDP types may cross this seam.
 */
interface RdpEngine {
    val state: StateFlow<EngineState>
    val frames: SharedFlow<FrameUpdate>
    val challenges: SharedFlow<EngineChallenge>

    /**
     * Most recent server cursor signal. Null until the engine reports anything;
     * thereafter holds a [CursorFrame] (bitmap, default, or hidden).
     */
    val cursor: StateFlow<CursorFrame?>

    suspend fun connect(params: RdpConnectParams): Result<Unit>
    suspend fun disconnect()

    fun attachSurface(surface: Surface, width: Int, height: Int, options: RenderOptions = RenderOptions())
    fun detachSurface()

    fun resolveChallenge(id: String, response: ChallengeResponse)

    fun sendKey(scancode: Int, action: KeyAction, meta: Int)
    fun sendPointer(x: Int, y: Int, buttons: Int, action: PointerAction, wheel: Int, hWheel: Int = 0)

    /**
     * Ask the engine to renegotiate the remote desktop size on the existing session,
     * without a reconnect. Implementations are expected to use the DisplayControl
     * virtual channel (DispClientContext::SendMonitorLayout) or an equivalent.
     *
     * Returns `true` if the request was dispatched to the server; `false` when the
     * engine has no live session, the channel is not negotiated, or the
     * implementation simply does not support live resize (e.g. the stub engine).
     * Callers should fall back to a full reconnect when `false` is returned.
     *
     * @param dpiScale percent (100..500). When zero, the server's last-known scale
     *   is preserved on its side.
     */
    fun requestResolution(width: Int, height: Int, dpiScale: Int = 0): Boolean = false
}
