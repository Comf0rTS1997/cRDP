package com.crdp.core.rdp

import android.view.Surface
import com.crdp.core.rdp.engine.ChallengeResponse
import com.crdp.core.rdp.engine.CursorFrame
import com.crdp.core.rdp.engine.EngineChallenge
import com.crdp.core.rdp.engine.RenderOptions
import com.crdp.core.rdp.input.KeyEventPayload
import com.crdp.core.rdp.input.PointerEvent
import com.crdp.core.rdp.input.TouchContact
import com.crdp.core.rdp.model.ConnectionProfile
import com.crdp.core.rdp.model.SessionState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface RdpSessionPort {
    val sessionState: StateFlow<SessionState>

    /**
     * Engine-issued interactive prompts (cert acceptance, auth, etc.).
     * Transports without an interactive engine (e.g., gateway) emit nothing.
     */
    val challenges: SharedFlow<EngineChallenge>
        get() = EmptyChallenges

    /** Latest server cursor signal. Null until the engine emits anything. */
    val cursor: StateFlow<CursorFrame?>
        get() = EmptyCursor

    suspend fun connect(profile: ConnectionProfile): Result<Unit>

    suspend fun disconnect()

    fun onPointerEvent(event: PointerEvent)

    /**
     * Multitouch path for direct FreeRDP. Default: no-op, reports success.
     * @return false when the transport could not send native touch (caller may fall back to mouse).
     */
    fun onTouchContacts(contacts: List<TouchContact>): Boolean = false

    fun onKeyEvent(event: KeyEventPayload)

    fun attachSurface(surface: Surface, width: Int, height: Int, options: RenderOptions = RenderOptions()) {}

    fun detachSurface() {}

    fun resolveChallenge(id: String, response: ChallengeResponse) {}

    /**
     * Live resize. Returns true if the underlying engine accepted the request and
     * dispatched a DisplayControl monitor-layout update to the server; false when
     * the transport cannot resize on the fly (caller should fall back to reconnect).
     */
    fun requestResolution(width: Int, height: Int, dpiScale: Int = 0): Boolean = false
}

private val EmptyChallenges: SharedFlow<EngineChallenge> =
    MutableSharedFlow(extraBufferCapacity = 0)

private val EmptyCursor: StateFlow<CursorFrame?> =
    MutableStateFlow<CursorFrame?>(null).asStateFlow()
