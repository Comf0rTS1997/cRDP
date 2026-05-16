package com.crdp.feature.session

import android.view.KeyEvent

/**
 * Contract a host Activity implements so an active RDP session can install an
 * Activity-level `dispatchKeyEvent` hook. This is the termux-x11 pattern:
 * keys are intercepted before the IME / Compose focus system can swallow them,
 * which is the only way KEYCODE_F4 with META_ALT_ON reliably reaches the
 * remote desktop (Alt+F4) and the only way special-purpose hotkeys like
 * Ctrl+Alt+Esc can be caught.
 *
 * The session screen registers a hook via [setKeyEventHook] in its
 * `DisposableEffect` while connected, and clears it on dispose. Setting null
 * restores default key dispatch.
 */
interface KeyEventHost {
    /**
     * Install or clear the hook. Hook returns `true` to consume the event.
     * Implementations MUST call this hook from their `dispatchKeyEvent`
     * override before deferring to `super`.
     */
    fun setKeyEventHook(hook: ((KeyEvent) -> Boolean)?)
}
