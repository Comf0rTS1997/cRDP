package com.crdp.feature.session

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText

/**
 * Hidden EditText that exists only to host an [InputConnection] for the soft
 * keyboard. The bundled IC ([RemoteIc]) does NOT keep an editable buffer —
 * instead it converts every IME action into a stream of typed characters,
 * raw key events, and backspace counts that the session forwards directly
 * to the remote desktop.
 *
 * Why not use a plain EditText + TextWatcher?  Samsung Keyboard / GBoard
 * keep a *composing region*: as the user types, the IME sends
 * `setComposingText("a")`, then `setComposingText("ab")`, then "abc", etc.
 * A TextWatcher sees the full growing string each time and would re-emit
 * earlier characters. We instead diff against the last composition and only
 * emit the suffix that changed — and the standard "kill composition" hack
 * (TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) is avoided so the IME doesn't think
 * this is a password field (no password autofill suggestions, no masked dots).
 */
class RemoteImeEditText(ctx: Context) : EditText(ctx) {

    /** Sequence of characters the user typed (may include composition diffs). */
    var onTextInput: (String) -> Unit = {}

    /** Raw KeyEvent the IME sent via InputConnection.sendKeyEvent (Enter, arrows…). */
    var onSpecialKey: (KeyEvent) -> Unit = {}

    /** IME asked to delete N characters before the cursor — forward as Backspace presses. */
    var onBackspace: (Int) -> Unit = {}

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // Configure the EditorInfo ourselves; the host code keeps these fields
        // declarative in one place rather than spreading them across setters.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_ENTER_ACTION or
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or
            EditorInfo.IME_ACTION_NONE
        return RemoteIc(this)
    }

    private inner class RemoteIc(view: View) : BaseInputConnection(view, false) {
        /** Last composing string the IME asked us to display. Used to compute diffs. */
        private var composing: String = ""

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            applyDiff(text?.toString() ?: "")
            composing = ""
            return true
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val newText = text?.toString() ?: ""
            applyDiff(newText)
            composing = newText
            return true
        }

        override fun finishComposingText(): Boolean {
            composing = ""
            return true
        }

        override fun setComposingRegion(start: Int, end: Int): Boolean = true

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            if (beforeLength > 0) onBackspace(beforeLength)
            composing = ""
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            // IME hardware-key passthrough (Enter, Backspace, arrows on some IMEs).
            onSpecialKey(event)
            return true
        }

        /**
         * Diff the IME's new composing/committed text against our last-known
         * composing string and emit only the change:
         *   - delete `composing.length - prefix` characters of suffix that
         *     used to be composing but is gone now,
         *   - then type the new suffix.
         *
         * Common case for sequential typing ("a" → "ab" → "abc") is zero
         * deletions and one new character per call.
         */
        private fun applyDiff(newText: String) {
            val prefixLen = commonPrefixLen(composing, newText)
            val toDelete = composing.length - prefixLen
            val toType = newText.substring(prefixLen)
            if (toDelete > 0) onBackspace(toDelete)
            if (toType.isNotEmpty()) onTextInput(toType)
        }

        private fun commonPrefixLen(a: String, b: String): Int {
            val max = minOf(a.length, b.length)
            var i = 0
            while (i < max && a[i] == b[i]) i++
            return i
        }
    }
}
