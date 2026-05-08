package com.crdp.core.rdp.input

data class PointerEvent(
    val x: Float,
    val y: Float,
    val action: PointerAction,
    val buttons: Int,
    val wheelDelta: Float = 0f,
    /** Horizontal wheel (maps to RDP PTR_FLAGS_HWHEEL). */
    val wheelDeltaH: Float = 0f,
)

enum class PointerAction {
    Down,
    Up,
    Move,
    Hover,
}

data class KeyEventPayload(
    val keyCode: Int,
    val metaState: Int,
    val action: KeyAction,
    /** Linux/input scan code from [android.view.KeyEvent.getScanCode]; 0 if unknown (e.g. IME). */
    val scanCode: Int = 0,
)

enum class KeyAction {
    Down,
    Up,
}

/** One contact in an RDP multitouch frame (FreeRDP RDPEI / freerdp_client_handle_touch). */
enum class RemoteTouchPhase {
    Down,
    Move,
    Up,
    Cancel,
}

data class TouchContact(
    /** Stable per-finger id (Android pointer id mapped to int). */
    val fingerId: Int,
    val x: Int,
    val y: Int,
    val phase: RemoteTouchPhase,
    val pressure: Int = 0,
)
