package com.crdp.rdp.direct

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
import com.crdp.core.rdp.input.TouchContact
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * Default [RdpEngine] binding when no real engine module (e.g., :engine:afreerdp)
 * is selected at build time. Connecting fails immediately so feature code can
 * surface a clear "engine not configured" error instead of pretending to work.
 */
class StubRdpEngine @Inject constructor() : RdpEngine {

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _frames = MutableSharedFlow<FrameUpdate>(extraBufferCapacity = 0)
    override val frames: SharedFlow<FrameUpdate> = _frames.asSharedFlow()

    private val _challenges = MutableSharedFlow<EngineChallenge>(extraBufferCapacity = 0)
    override val challenges: SharedFlow<EngineChallenge> = _challenges.asSharedFlow()

    private val _cursor = MutableStateFlow<CursorFrame?>(null)
    override val cursor: StateFlow<CursorFrame?> = _cursor.asStateFlow()

    override suspend fun connect(params: RdpConnectParams): Result<Unit> {
        _state.value = EngineState.Error(MESSAGE)
        return Result.failure(IllegalStateException(MESSAGE))
    }

    override suspend fun disconnect() {
        _state.value = EngineState.Idle
    }

    override fun attachSurface(surface: Surface, width: Int, height: Int, options: RenderOptions) = Unit
    override fun detachSurface() = Unit
    override fun resolveChallenge(id: String, response: ChallengeResponse) = Unit
    override fun sendKey(scancode: Int, action: KeyAction, meta: Int) = Unit
    override fun sendPointer(x: Int, y: Int, buttons: Int, action: PointerAction, wheel: Int, wheelH: Int) = Unit

    override fun sendTouchContacts(contacts: List<TouchContact>): Boolean = false

    private companion object {
        const val MESSAGE = "RDP engine not configured (build with -Pcrdp.engine=afreerdp)"
    }
}
