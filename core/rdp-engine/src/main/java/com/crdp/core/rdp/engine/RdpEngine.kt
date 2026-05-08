package com.crdp.core.rdp.engine

import android.view.Surface
import com.crdp.core.rdp.input.KeyAction
import com.crdp.core.rdp.input.PointerAction
import com.crdp.core.rdp.input.TouchContact
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
    fun sendPointer(x: Int, y: Int, buttons: Int, action: PointerAction, wheel: Int, wheelH: Int = 0)

    /**
     * Sends multitouch contacts to the server (Windows RDPEI when available).
     * @return false if native touch is unavailable or submission failed for any contact
     */
    fun sendTouchContacts(contacts: List<TouchContact>): Boolean
}
