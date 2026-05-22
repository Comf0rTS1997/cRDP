package com.crdp.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.crdp.app.prefs.AppSettings
import com.crdp.app.prefs.PointerSpeeds
import com.crdp.app.prefs.ScrollSpeeds
import kotlinx.coroutines.launch

private sealed interface MousePicker {
    data object WheelSpeed : MousePicker
    data object TouchpadSpeed : MousePicker
    data object MousePointerSpeed : MousePicker
    data object TouchpadPointerSpeed : MousePicker
}

/**
 * Dedicated sub-page for pointer / scroll behavior. Grouped together because
 * "reverse direction", "wheel speed", and "touchpad sensitivity" all interact
 * with the same scroll-emit pipeline in SessionScreen and are typically tuned
 * together. Lifted out of the main Settings list so the row isn't three lines
 * deep.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MouseSettingsScreen(
    appSettings: AppSettings,
    onReverseScroll: (Boolean) -> Unit,
    onMouseWheelSpeed: (Int) -> Unit,
    onTouchpadScrollSpeed: (Int) -> Unit,
    onMousePointerSpeed: (Int) -> Unit,
    onTouchpadPointerSpeed: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    var picker by remember { mutableStateOf<MousePicker?>(null) }

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text("Mouse and scrolling") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader("Direction")
            SettingRow(
                icon = Icons.Default.SwapVert,
                title = "Reverse scroll direction",
                subtitle = "Off: natural scrolling (content follows finger). " +
                    "On: traditional Windows-style. Applies to mouse wheel and " +
                    "two-finger touchpad scroll.",
                trailing = {
                    Switch(
                        checked = appSettings.reverseScroll,
                        onCheckedChange = onReverseScroll,
                    )
                },
            )

            SectionHeader("Scroll speed")
            SettingRow(
                icon = Icons.Default.Mouse,
                title = "Mouse wheel speed",
                subtitle = "Multiplier for physical mouse / external trackpad wheel",
                value = ScrollSpeeds.label(appSettings.mouseWheelSpeed),
                onClick = { picker = MousePicker.WheelSpeed },
            )
            SettingRow(
                icon = Icons.Default.Tune,
                title = "Touchpad scroll speed",
                subtitle = "Sensitivity of two-finger scroll in trackpad mode",
                value = ScrollSpeeds.label(appSettings.touchpadScrollSpeed),
                onClick = { picker = MousePicker.TouchpadSpeed },
            )

            SectionHeader("Pointer speed")
            SettingRow(
                icon = Icons.Default.Speed,
                title = "Mouse pointer speed",
                subtitle = "100% matches Android system feel (honors OS pointer-speed setting + acceleration)",
                value = PointerSpeeds.label(appSettings.mousePointerSpeed),
                onClick = { picker = MousePicker.MousePointerSpeed },
            )
            SettingRow(
                icon = Icons.Default.TouchApp,
                title = "Touchpad pointer speed",
                subtitle = "Cursor-movement multiplier when dragging on the on-screen trackpad",
                value = PointerSpeeds.label(appSettings.touchpadPointerSpeed),
                onClick = { picker = MousePicker.TouchpadPointerSpeed },
            )
        }
    }

    val active = picker
    if (active != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val scope = rememberCoroutineScope()
        fun dismiss() {
            scope.launch {
                sheetState.hide()
                picker = null
            }
        }
        ModalBottomSheet(
            onDismissRequest = { picker = null },
            sheetState = sheetState,
        ) {
            when (active) {
                MousePicker.WheelSpeed -> SpeedPicker(
                    title = "Mouse wheel speed",
                    options = ScrollSpeeds.OPTIONS,
                    labelFor = ScrollSpeeds::label,
                    selected = appSettings.mouseWheelSpeed,
                    onSelect = { onMouseWheelSpeed(it); dismiss() },
                )
                MousePicker.TouchpadSpeed -> SpeedPicker(
                    title = "Touchpad scroll speed",
                    options = ScrollSpeeds.OPTIONS,
                    labelFor = ScrollSpeeds::label,
                    selected = appSettings.touchpadScrollSpeed,
                    onSelect = { onTouchpadScrollSpeed(it); dismiss() },
                )
                MousePicker.MousePointerSpeed -> SpeedPicker(
                    title = "Mouse pointer speed",
                    options = PointerSpeeds.OPTIONS,
                    labelFor = PointerSpeeds::label,
                    selected = appSettings.mousePointerSpeed,
                    onSelect = { onMousePointerSpeed(it); dismiss() },
                )
                MousePicker.TouchpadPointerSpeed -> SpeedPicker(
                    title = "Touchpad pointer speed",
                    options = PointerSpeeds.OPTIONS,
                    labelFor = PointerSpeeds::label,
                    selected = appSettings.touchpadPointerSpeed,
                    onSelect = { onTouchpadPointerSpeed(it); dismiss() },
                )
            }
        }
    }
}

@Composable
private fun SpeedPicker(
    title: String,
    options: List<Int>,
    labelFor: (Int) -> String,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = 16.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        )
        options.forEach { value ->
            ListItem(
                headlineContent = { Text(labelFor(value)) },
                trailingContent = {
                    if (value == selected) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                modifier = Modifier.clickable { onSelect(value) },
            )
        }
    }
}
