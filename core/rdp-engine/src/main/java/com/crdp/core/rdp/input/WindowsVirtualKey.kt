package com.crdp.core.rdp.input

import android.view.KeyEvent

/**
 * Translates an Android `KeyEvent.keyCode` to a Windows Virtual Key Code (`VK_*`).
 *
 * FreeRDP's `freerdp_send_key_event(inst, vk, down)` JNI shim feeds its arg into
 * `GetVirtualScanCodeFromVirtualKeyCode(vk, 4)` — i.e., it expects a Windows
 * VK, not an Android keyCode and not a Linux/HID scancode. Without this
 * translation, function keys (F1–F12), modifier combos (Alt+F4, Ctrl+Esc),
 * arrows, and OEM punctuation reach the remote desktop as garbage scancodes.
 *
 * Returns 0 for keys we don't map (system keys like HOME/BACK/POWER, plus
 * unmapped media keys). Callers should drop key events with VK == 0 instead of
 * forwarding them — sending VK 0 to FreeRDP would still translate via the
 * VK→scancode table and produce noise.
 *
 * Table is US-101 layout. International / regional layouts will need their own
 * mapping, but the canonical RDP scancodes are layout-independent for the keys
 * here; only the OEM punctuation block (≥0xBA) is locale-sensitive.
 */
object WindowsVirtualKey {

    /**
     * KBDEXT bit (0x100). WinPR's `GetVirtualScanCodeFromVirtualKeyCode` uses
     * this bit on the input VK to decide which scancode table to search:
     *
     *   - Bit clear → KBD4T (non-extended) table. Returns 0 for any key that
     *     only exists in the extended set, including LWIN, RWIN, arrows,
     *     Insert/Delete, Home/End, PgUp/PgDn, RCtrl, RAlt, numpad-divide,
     *     numpad-enter. The wire payload ends up `flags=0, code=0` and the
     *     server silently drops it.
     *   - Bit set → KBD4X (extended) table. Returns the proper scancode AND
     *     re-sets KBDEXT so the JNI applies KBD_FLAGS_EXTENDED on the wire.
     *
     * Every VK below that originates from an extended PS/2 scancode must be
     * ORed with this bit. aFreeRDP's KeyboardMapper does the same thing via
     * its keymapExt table. (Symptom of missing this: Win key, arrows, Home,
     * End, PgUp/Dn, Insert, Del all silently fail.)
     */
    private const val EXT = 0x100

    private val MAP: IntArray = IntArray(KEYCODE_MAX + 1).also { m ->
        // Letters A–Z → VK 0x41..0x5A
        for (i in 0 until 26) m[KeyEvent.KEYCODE_A + i] = 0x41 + i
        // Digits 0–9 (top row) → VK 0x30..0x39
        for (i in 0 until 10) m[KeyEvent.KEYCODE_0 + i] = 0x30 + i
        // Function keys F1–F12 → VK 0x70..0x7B
        for (i in 0 until 12) m[KeyEvent.KEYCODE_F1 + i] = 0x70 + i
        // Numpad digits → VK_NUMPAD0..9 (0x60..0x69)
        for (i in 0 until 10) m[KeyEvent.KEYCODE_NUMPAD_0 + i] = 0x60 + i

        // Editing / navigation
        m[KeyEvent.KEYCODE_ESCAPE]       = 0x1B           // VK_ESCAPE
        m[KeyEvent.KEYCODE_TAB]          = 0x09           // VK_TAB
        m[KeyEvent.KEYCODE_SPACE]        = 0x20           // VK_SPACE
        m[KeyEvent.KEYCODE_ENTER]        = 0x0D           // VK_RETURN
        m[KeyEvent.KEYCODE_NUMPAD_ENTER] = 0x0D or EXT    // numpad Enter is extended
        m[KeyEvent.KEYCODE_DEL]          = 0x08           // VK_BACK  (Android DEL = backspace)
        m[KeyEvent.KEYCODE_FORWARD_DEL]  = 0x2E or EXT    // VK_DELETE (extended)
        m[KeyEvent.KEYCODE_INSERT]       = 0x2D or EXT    // VK_INSERT (extended)
        m[KeyEvent.KEYCODE_MOVE_HOME]    = 0x24 or EXT    // VK_HOME   (extended)
        m[KeyEvent.KEYCODE_MOVE_END]     = 0x23 or EXT    // VK_END    (extended)
        m[KeyEvent.KEYCODE_PAGE_UP]      = 0x21 or EXT    // VK_PRIOR  (extended)
        m[KeyEvent.KEYCODE_PAGE_DOWN]    = 0x22 or EXT    // VK_NEXT   (extended)

        // Arrows (all extended on PS/2)
        m[KeyEvent.KEYCODE_DPAD_UP]    = 0x26 or EXT  // VK_UP
        m[KeyEvent.KEYCODE_DPAD_DOWN]  = 0x28 or EXT  // VK_DOWN
        m[KeyEvent.KEYCODE_DPAD_LEFT]  = 0x25 or EXT  // VK_LEFT
        m[KeyEvent.KEYCODE_DPAD_RIGHT] = 0x27 or EXT  // VK_RIGHT

        // Modifiers — Right Ctrl / Right Alt / both Win keys are extended.
        m[KeyEvent.KEYCODE_SHIFT_LEFT]  = 0xA0           // VK_LSHIFT
        m[KeyEvent.KEYCODE_SHIFT_RIGHT] = 0xA1           // VK_RSHIFT
        m[KeyEvent.KEYCODE_CTRL_LEFT]   = 0xA2           // VK_LCONTROL
        m[KeyEvent.KEYCODE_CTRL_RIGHT]  = 0xA3 or EXT    // VK_RCONTROL (extended)
        m[KeyEvent.KEYCODE_ALT_LEFT]    = 0xA4           // VK_LMENU
        m[KeyEvent.KEYCODE_ALT_RIGHT]   = 0xA5 or EXT    // VK_RMENU (extended)
        m[KeyEvent.KEYCODE_META_LEFT]   = 0x5B or EXT    // VK_LWIN  (extended)
        m[KeyEvent.KEYCODE_META_RIGHT]  = 0x5C or EXT    // VK_RWIN  (extended)

        // Locks
        m[KeyEvent.KEYCODE_CAPS_LOCK]   = 0x14  // VK_CAPITAL
        m[KeyEvent.KEYCODE_NUM_LOCK]    = 0x90  // VK_NUMLOCK
        m[KeyEvent.KEYCODE_SCROLL_LOCK] = 0x91  // VK_SCROLL
        m[KeyEvent.KEYCODE_BREAK]       = 0x13  // VK_PAUSE
        m[KeyEvent.KEYCODE_SYSRQ]       = 0x2A  // VK_PRINT

        // US-layout OEM punctuation
        m[KeyEvent.KEYCODE_COMMA]         = 0xBC  // VK_OEM_COMMA
        m[KeyEvent.KEYCODE_PERIOD]        = 0xBE  // VK_OEM_PERIOD
        m[KeyEvent.KEYCODE_SLASH]         = 0xBF  // VK_OEM_2  '/'
        m[KeyEvent.KEYCODE_BACKSLASH]     = 0xDC  // VK_OEM_5  '\'
        m[KeyEvent.KEYCODE_SEMICOLON]     = 0xBA  // VK_OEM_1  ';'
        m[KeyEvent.KEYCODE_APOSTROPHE]    = 0xDE  // VK_OEM_7  '\''
        m[KeyEvent.KEYCODE_LEFT_BRACKET]  = 0xDB  // VK_OEM_4  '['
        m[KeyEvent.KEYCODE_RIGHT_BRACKET] = 0xDD  // VK_OEM_6  ']'
        m[KeyEvent.KEYCODE_GRAVE]         = 0xC0  // VK_OEM_3  '`'
        m[KeyEvent.KEYCODE_MINUS]         = 0xBD  // VK_OEM_MINUS
        m[KeyEvent.KEYCODE_EQUALS]        = 0xBB  // VK_OEM_PLUS

        // Numpad operators — divide is extended.
        m[KeyEvent.KEYCODE_NUMPAD_ADD]      = 0x6B          // VK_ADD
        m[KeyEvent.KEYCODE_NUMPAD_SUBTRACT] = 0x6D          // VK_SUBTRACT
        m[KeyEvent.KEYCODE_NUMPAD_MULTIPLY] = 0x6A          // VK_MULTIPLY
        m[KeyEvent.KEYCODE_NUMPAD_DIVIDE]   = 0x6F or EXT   // VK_DIVIDE (extended)
        m[KeyEvent.KEYCODE_NUMPAD_DOT]      = 0x6E          // VK_DECIMAL
    }

    private const val KEYCODE_MAX = 287 // generous; Android KeyEvent ceiling grows over time

    /** Returns the Windows VK for the given Android keyCode, or 0 if unmapped. */
    fun fromAndroidKeyCode(keyCode: Int): Int {
        if (keyCode < 0 || keyCode > KEYCODE_MAX) return 0
        return MAP[keyCode]
    }
}
