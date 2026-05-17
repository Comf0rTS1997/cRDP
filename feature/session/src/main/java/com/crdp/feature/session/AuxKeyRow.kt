package com.crdp.feature.session

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crdp.core.rdp.input.KeyAction

/**
 * Horizontal scrollable row of auxiliary keys shown above the soft keyboard.
 *
 * Each key behaves like a physical key on a hardware keyboard:
 *   - finger DOWN on a key → emit `KeyAction.Down` for that key,
 *   - finger UP / cancel → emit `KeyAction.Up`.
 *
 * Combos work via multi-touch: hold Ctrl with one finger, tap C with another.
 * The remote desktop tracks modifier state from these explicit Down/Up events,
 * so a soft-keyboard letter pressed while Ctrl is held is also seen as Ctrl+letter.
 */
@Composable
fun AuxKeyRow(
    enabledIds: Set<String>,
    onKey: (entry: AuxKeys.Entry, action: KeyAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries = remember(enabledIds) {
        // Preserve registry order rather than Set iteration order so users
        // toggling individual keys see a stable layout.
        AuxKeys.ALL.filter { it.id in enabledIds }
    }
    if (entries.isEmpty()) return

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            // Reserve the row's area from the system back gesture so dragging
            // horizontally from the left/right edge to scroll the row doesn't
            // trigger Android's edge-back. Capped at 200dp/edge by the system;
            // our row is ~40dp tall so it fits comfortably within the budget.
            .systemGestureExclusion(),
        color = Color(0xE6202020),
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            entries.forEach { entry ->
                AuxKeyButton(
                    label = entry.label,
                    onDown = { onKey(entry, KeyAction.Down) },
                    onUp = { onKey(entry, KeyAction.Up) },
                )
                Spacer(Modifier.width(4.dp))
            }
        }
    }
}

@Composable
private fun AuxKeyButton(
    label: String,
    onDown: () -> Unit,
    onUp: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val bg = if (pressed) Color(0xFF3D5AFE) else Color(0xFF3A3A3A)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .height(32.dp)
            .width(if (label.length <= 2) 38.dp else 52.dp)
            // detectTapGestures.onPress gives us press + release callbacks,
            // including a `tryAwaitRelease` that returns when the finger lifts
            // OR the gesture is cancelled (e.g., user drags off). We always
            // fire onUp in `finally` so a cancelled press still releases the
            // key on the remote — otherwise a modifier could get stuck down.
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        onDown()
                        try {
                            tryAwaitRelease()
                        } finally {
                            pressed = false
                            onUp()
                        }
                    },
                )
            }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = if (pressed) FontWeight.Bold else FontWeight.Medium,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
