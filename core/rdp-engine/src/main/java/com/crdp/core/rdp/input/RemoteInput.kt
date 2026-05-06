package com.crdp.core.rdp.input

data class PointerEvent(
    val x: Float,
    val y: Float,
    val action: PointerAction,
    val buttons: Int,
    val wheelDelta: Float = 0f,
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
