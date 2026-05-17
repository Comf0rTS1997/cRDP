package com.crdp.feature.session

import android.view.KeyEvent

/**
 * Registry of every key that can appear in the in-session aux row above the
 * soft keyboard. Each entry maps a stable string id (persisted in DataStore)
 * to an Android KeyEvent.KEYCODE_* value and a short display label.
 *
 * Lives in `feature:session` (not `app`) so the in-session UI can reference it
 * directly without inverting the module dependency direction.
 */
object AuxKeys {
    data class Entry(
        val id: String,
        val label: String,
        val keyCode: Int,
        /**
         * Modifier keys (Ctrl/Alt/Shift/Win) are sticky in the aux row: tapping
         * arms the modifier; the next non-modifier tap includes the meta bit and
         * the modifier auto-disarms. Non-modifier keys send a single Down+Up pair.
         */
        val isModifier: Boolean = false,
    )

    val ALL: List<Entry> = listOf(
        Entry("esc", "Esc", KeyEvent.KEYCODE_ESCAPE),
        Entry("tab", "Tab", KeyEvent.KEYCODE_TAB),
        Entry("ctrl", "Ctrl", KeyEvent.KEYCODE_CTRL_LEFT, isModifier = true),
        Entry("alt", "Alt", KeyEvent.KEYCODE_ALT_LEFT, isModifier = true),
        Entry("shift", "Shift", KeyEvent.KEYCODE_SHIFT_LEFT, isModifier = true),
        Entry("win", "Win", KeyEvent.KEYCODE_META_LEFT, isModifier = true),
        Entry("up", "↑", KeyEvent.KEYCODE_DPAD_UP),
        Entry("down", "↓", KeyEvent.KEYCODE_DPAD_DOWN),
        Entry("left", "←", KeyEvent.KEYCODE_DPAD_LEFT),
        Entry("right", "→", KeyEvent.KEYCODE_DPAD_RIGHT),
        Entry("home", "Home", KeyEvent.KEYCODE_MOVE_HOME),
        Entry("end", "End", KeyEvent.KEYCODE_MOVE_END),
        Entry("pgup", "PgUp", KeyEvent.KEYCODE_PAGE_UP),
        Entry("pgdn", "PgDn", KeyEvent.KEYCODE_PAGE_DOWN),
        Entry("ins", "Ins", KeyEvent.KEYCODE_INSERT),
        Entry("del", "Del", KeyEvent.KEYCODE_FORWARD_DEL),
        Entry("f1", "F1", KeyEvent.KEYCODE_F1),
        Entry("f2", "F2", KeyEvent.KEYCODE_F2),
        Entry("f3", "F3", KeyEvent.KEYCODE_F3),
        Entry("f4", "F4", KeyEvent.KEYCODE_F4),
        Entry("f5", "F5", KeyEvent.KEYCODE_F5),
        Entry("f6", "F6", KeyEvent.KEYCODE_F6),
        Entry("f7", "F7", KeyEvent.KEYCODE_F7),
        Entry("f8", "F8", KeyEvent.KEYCODE_F8),
        Entry("f9", "F9", KeyEvent.KEYCODE_F9),
        Entry("f10", "F10", KeyEvent.KEYCODE_F10),
        Entry("f11", "F11", KeyEvent.KEYCODE_F11),
        Entry("f12", "F12", KeyEvent.KEYCODE_F12),
    )

    val BY_ID: Map<String, Entry> = ALL.associateBy { it.id }

    /** Conservative defaults: modifiers + Esc/Tab + arrows. F-keys off by default to keep the bar tidy. */
    val DEFAULT_ENABLED: Set<String> = setOf(
        "esc", "tab", "ctrl", "alt", "shift", "win",
        "up", "down", "left", "right",
    )

    /** Maps a modifier entry id to its Android `KeyEvent.META_*_ON` bit. */
    fun metaBit(id: String): Int = when (id) {
        "ctrl" -> KeyEvent.META_CTRL_ON
        "alt" -> KeyEvent.META_ALT_ON
        "shift" -> KeyEvent.META_SHIFT_ON
        "win" -> KeyEvent.META_META_ON
        else -> 0
    }
}
