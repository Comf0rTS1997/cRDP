package com.crdp.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.crdp.app.prefs.AppSettings
import com.crdp.app.prefs.AudioModes
import com.crdp.app.prefs.AudioQualities
import com.crdp.app.prefs.CameraModes
import com.crdp.app.prefs.AutoLockVault
import com.crdp.app.prefs.BandwidthProfiles
import com.crdp.app.prefs.DpiScales
import com.crdp.app.prefs.KeyboardLayouts
import com.crdp.app.prefs.RenderBackends
import com.crdp.app.prefs.RenderSamplingOptions
import com.crdp.app.prefs.Resolutions
import com.crdp.app.R
import kotlinx.coroutines.launch

private sealed interface ChooserKind {
    data object Bandwidth : ChooserKind
    data object Resolution : ChooserKind
    data object Keyboard : ChooserKind
    data object AutoLock : ChooserKind
    data object RenderBackend : ChooserKind
    data object RenderSampling : ChooserKind
    data object DefaultDpi : ChooserKind
    data object DexDpi : ChooserKind
    data object AudioPlayback : ChooserKind
    data object AudioQuality : ChooserKind
    data object Camera : ChooserKind
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    dynamicColor: Boolean,
    onDynamicColor: (Boolean) -> Unit,
    appSettings: AppSettings,
    onTouchAsMouse: (Boolean) -> Unit,
    onHapticFeedback: (Boolean) -> Unit,
    onBiometricUnlock: (Boolean) -> Unit,
    onRequireBiometricToDecrypt: (Boolean) -> Unit,
    onAutoDisconnectIdle: (Boolean) -> Unit,
    onBandwidthProfile: (String) -> Unit,
    onDefaultResolution: (String) -> Unit,
    onKeyboardLayout: (String) -> Unit,
    onAutoLockVaultMinutes: (Int) -> Unit,
    onRenderBackend: (String) -> Unit,
    onRenderSampling: (String) -> Unit,
    onAddCustomResolution: (String) -> Unit,
    onRemoveCustomResolution: (String) -> Unit,
    onDefaultDpiScale: (Int) -> Unit,
    onDexDpiScale: (Int) -> Unit,
    onDefaultAudioMode: (String) -> Unit,
    onDefaultMicrophoneEnabled: (Boolean) -> Unit,
    onDefaultAudioQuality: (String) -> Unit,
    onDefaultCameraMode: (String) -> Unit,
    onDefaultClipboardSync: (Boolean) -> Unit,
    onOpenVault: () -> Unit,
    onOpenLicenses: () -> Unit,
    versionLabel: String,
    onBack: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    var chooser by remember { mutableStateOf<ChooserKind?>(null) }

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text("Settings") },
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
            SectionHeader("Appearance")
            SettingRow(
                icon = Icons.Default.Bolt,
                title = "Dynamic color",
                subtitle = "Use wallpaper colors (Android 12+)",
                trailing = {
                    Switch(checked = dynamicColor, onCheckedChange = onDynamicColor)
                },
            )

            SectionHeader("Connection")
            SettingRow(
                icon = Icons.Default.Wifi,
                title = "Bandwidth profile",
                value = appSettings.bandwidthProfile,
                onClick = { chooser = ChooserKind.Bandwidth },
            )
            SettingRow(
                icon = Icons.Default.Monitor,
                title = "Default resolution",
                value = appSettings.defaultResolution,
                onClick = { chooser = ChooserKind.Resolution },
            )
            SettingRow(
                icon = Icons.Default.Keyboard,
                title = "Keyboard layout",
                value = appSettings.keyboardLayout,
                onClick = { chooser = ChooserKind.Keyboard },
            )
            SettingRow(
                icon = Icons.Default.Monitor,
                title = "Default DPI scaling",
                subtitle = "Server-side desktop scale for new connections",
                value = DpiScales.label(appSettings.defaultDpiScale),
                onClick = { chooser = ChooserKind.DefaultDpi },
            )
            SettingRow(
                icon = Icons.Default.Monitor,
                title = "DeX DPI scaling",
                subtitle = "Used while the device is in Samsung DeX",
                value = DpiScales.label(appSettings.dexDpiScale),
                onClick = { chooser = ChooserKind.DexDpi },
            )
            SettingRow(
                icon = Icons.Default.ContentPaste,
                title = stringResource(R.string.settings_clipboard_sync_title),
                subtitle = stringResource(R.string.settings_clipboard_sync_subtitle),
                trailing = {
                    Switch(
                        checked = appSettings.defaultClipboardSync,
                        onCheckedChange = onDefaultClipboardSync,
                    )
                },
            )

            SectionHeader("Audio")
            SettingRow(
                icon = Icons.AutoMirrored.Filled.VolumeUp,
                title = "Default playback",
                subtitle = "Where remote audio plays for new connections",
                value = appSettings.defaultAudioMode,
                onClick = { chooser = ChooserKind.AudioPlayback },
            )
            SettingRow(
                icon = Icons.Default.Mic,
                title = "Default microphone",
                subtitle = "Pass local mic input to the remote session",
                trailing = {
                    Switch(
                        checked = appSettings.defaultMicrophoneEnabled,
                        onCheckedChange = onDefaultMicrophoneEnabled,
                    )
                },
            )
            SettingRow(
                icon = Icons.Default.Tune,
                title = "Audio quality",
                subtitle = "Dynamic adapts to the link; High prefers fidelity",
                value = appSettings.defaultAudioQuality,
                onClick = { chooser = ChooserKind.AudioQuality },
            )

            SectionHeader("Camera")
            SettingRow(
                icon = Icons.Default.Videocam,
                title = "Default camera",
                subtitle = "Forwarded to the remote desktop via rdpecam",
                value = appSettings.defaultCameraMode,
                onClick = { chooser = ChooserKind.Camera },
            )

            SectionHeader("Input")
            SettingRow(
                icon = Icons.Default.Mouse,
                title = "Touch as mouse",
                subtitle = "Tap, drag, two-finger scroll",
                trailing = { Switch(checked = appSettings.touchAsMouse, onCheckedChange = onTouchAsMouse) },
            )
            SettingRow(
                icon = Icons.Default.Vibration,
                title = "Haptic feedback",
                subtitle = "On taps and key presses",
                trailing = { Switch(checked = appSettings.hapticFeedback, onCheckedChange = onHapticFeedback) },
            )

            SectionHeader("Vault")
            SettingRow(
                icon = Icons.Default.Lock,
                title = "Saved credentials",
                subtitle = "View and manage stored passwords",
                onClick = onOpenVault,
            )

            SectionHeader("Security")
            SettingRow(
                icon = Icons.Default.Lock,
                title = "Biometric unlock",
                trailing = { Switch(checked = appSettings.biometricUnlock, onCheckedChange = onBiometricUnlock) },
            )
            SettingRow(
                icon = Icons.Default.Lock,
                title = "Require biometric to decrypt credentials",
                subtitle = "Biometric gates key access, not just the UI",
                trailing = {
                    Switch(
                        checked = appSettings.requireBiometricToDecrypt,
                        onCheckedChange = onRequireBiometricToDecrypt,
                    )
                },
            )
            SettingRow(
                icon = Icons.Default.Lock,
                title = "Auto-lock vault",
                value = AutoLockVault.label(appSettings.autoLockVaultMinutes),
                onClick = { chooser = ChooserKind.AutoLock },
            )
            SettingRow(
                icon = Icons.Default.Bolt,
                title = "Auto-disconnect idle",
                subtitle = "After 30 minutes",
                trailing = { Switch(checked = appSettings.autoDisconnectIdle, onCheckedChange = onAutoDisconnectIdle) },
            )

            SectionHeader("Display")
            SettingRow(
                icon = Icons.Default.Monitor,
                title = "Render backend",
                subtitle = "Auto picks GLES on supported devices",
                value = appSettings.renderBackend,
                onClick = { chooser = ChooserKind.RenderBackend },
            )
            SettingRow(
                icon = Icons.Default.Bolt,
                title = "Render quality",
                subtitle = "Sampling filter used by GLES backend",
                value = appSettings.renderSampling,
                onClick = { chooser = ChooserKind.RenderSampling },
            )

            SectionHeader("About")
            SettingRow(icon = Icons.Default.Info, title = "cRDP", subtitle = versionLabel)
            SettingRow(
                icon = Icons.Default.Folder,
                title = "Open source licenses",
                onClick = onOpenLicenses,
            )

            Spacer(Modifier.height(24.dp))
        }
    }

    val activeChooser = chooser
    if (activeChooser != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val scope = rememberCoroutineScope()
        fun dismiss() {
            scope.launch {
                sheetState.hide()
                chooser = null
            }
        }
        ModalBottomSheet(
            onDismissRequest = { chooser = null },
            sheetState = sheetState,
        ) {
            when (activeChooser) {
                ChooserKind.Bandwidth -> ChooserList(
                    title = "Bandwidth profile",
                    options = BandwidthProfiles.OPTIONS,
                    selected = appSettings.bandwidthProfile,
                    onSelect = { onBandwidthProfile(it); dismiss() },
                )
                ChooserKind.Resolution -> ResolutionChooser(
                    builtIn = Resolutions.BUILT_IN,
                    custom = appSettings.customResolutions,
                    selected = appSettings.defaultResolution,
                    onSelect = { onDefaultResolution(it); dismiss() },
                    onAdd = onAddCustomResolution,
                    onRemove = onRemoveCustomResolution,
                )
                ChooserKind.Keyboard -> ChooserList(
                    title = "Keyboard layout",
                    options = KeyboardLayouts.OPTIONS,
                    selected = appSettings.keyboardLayout,
                    onSelect = { onKeyboardLayout(it); dismiss() },
                )
                ChooserKind.AutoLock -> ChooserList(
                    title = "Auto-lock vault",
                    options = AutoLockVault.OPTIONS.map { AutoLockVault.label(it) },
                    selected = AutoLockVault.label(appSettings.autoLockVaultMinutes),
                    onSelect = { label ->
                        val value = AutoLockVault.OPTIONS.first { AutoLockVault.label(it) == label }
                        onAutoLockVaultMinutes(value)
                        dismiss()
                    },
                )
                ChooserKind.RenderBackend -> ChooserList(
                    title = "Render backend",
                    options = RenderBackends.OPTIONS,
                    selected = appSettings.renderBackend,
                    onSelect = { onRenderBackend(it); dismiss() },
                )
                ChooserKind.RenderSampling -> ChooserList(
                    title = "Render quality",
                    options = RenderSamplingOptions.OPTIONS,
                    selected = appSettings.renderSampling,
                    onSelect = { onRenderSampling(it); dismiss() },
                )
                ChooserKind.DefaultDpi -> DpiChooser(
                    title = "Default DPI scaling",
                    selected = appSettings.defaultDpiScale,
                    onSelect = { onDefaultDpiScale(it); dismiss() },
                )
                ChooserKind.DexDpi -> DpiChooser(
                    title = "DeX DPI scaling",
                    selected = appSettings.dexDpiScale,
                    onSelect = { onDexDpiScale(it); dismiss() },
                )
                ChooserKind.AudioPlayback -> ChooserList(
                    title = "Default playback",
                    options = AudioModes.OPTIONS,
                    selected = appSettings.defaultAudioMode,
                    onSelect = { onDefaultAudioMode(it); dismiss() },
                )
                ChooserKind.AudioQuality -> ChooserList(
                    title = "Audio quality",
                    options = AudioQualities.OPTIONS,
                    selected = appSettings.defaultAudioQuality,
                    onSelect = { onDefaultAudioQuality(it); dismiss() },
                )
                ChooserKind.Camera -> ChooserList(
                    title = "Default camera",
                    options = CameraModes.OPTIONS,
                    selected = appSettings.defaultCameraMode,
                    onSelect = { onDefaultCameraMode(it); dismiss() },
                )
            }
        }
    }
}

@Composable
private fun ChooserList(
    title: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = 16.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        )
        options.forEach { option ->
            ListItem(
                headlineContent = { Text(option) },
                trailingContent = {
                    if (option == selected) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                modifier = Modifier.clickable { onSelect(option) },
            )
        }
    }
}

@Composable
private fun ResolutionChooser(
    builtIn: List<String>,
    custom: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    var showAdd by remember { mutableStateOf(false) }
    Column(modifier = Modifier.padding(bottom = 16.dp)) {
        Text(
            text = "Default resolution",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        )
        builtIn.forEach { option ->
            ListItem(
                headlineContent = { Text(option) },
                trailingContent = {
                    if (option == selected) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                modifier = Modifier.clickable { onSelect(option) },
            )
        }
        if (custom.isNotEmpty()) {
            Text(
                text = "Custom",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 4.dp),
            )
            custom.forEach { option ->
                ListItem(
                    headlineContent = { Text(option) },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (option == selected) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            IconButton(onClick = { onRemove(option) }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Remove $option",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    modifier = Modifier.clickable { onSelect(option) },
                )
            }
        }
        ListItem(
            leadingContent = {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            headlineContent = {
                Text("Add custom resolution", color = MaterialTheme.colorScheme.primary)
            },
            modifier = Modifier.clickable { showAdd = true },
        )
    }

    if (showAdd) {
        AddResolutionDialog(
            existing = builtIn + custom,
            onDismiss = { showAdd = false },
            onConfirm = { value ->
                onAdd(value)
                showAdd = false
            },
        )
    }
}

@Composable
private fun DpiChooser(
    title: String,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    var showCustom by remember { mutableStateOf(false) }
    val presetIncludesSelected = DpiScales.PRESETS.contains(selected)
    Column(modifier = Modifier.padding(bottom = 16.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        )
        DpiScales.PRESETS.forEach { value ->
            ListItem(
                headlineContent = { Text(DpiScales.label(value)) },
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
        if (!presetIncludesSelected) {
            ListItem(
                headlineContent = { Text("${DpiScales.label(selected)} (custom)") },
                trailingContent = {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
            )
        }
        ListItem(
            leadingContent = {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            headlineContent = {
                Text("Set custom value", color = MaterialTheme.colorScheme.primary)
            },
            modifier = Modifier.clickable { showCustom = true },
        )
    }

    if (showCustom) {
        CustomDpiDialog(
            initial = selected,
            onDismiss = { showCustom = false },
            onConfirm = {
                onSelect(it)
                showCustom = false
            },
        )
    }
}

@Composable
private fun CustomDpiDialog(
    initial: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var text by remember { mutableStateOf(initial.toString()) }
    val parsed = remember(text) { DpiScales.normalize(text) }
    val error = when {
        text.isBlank() -> null
        parsed == null -> "Enter a value between ${DpiScales.MIN}% and ${DpiScales.MAX}%"
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom DPI scale") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { c -> c.isDigit() }.take(3) },
                    label = { Text("Percent") },
                    singleLine = true,
                    suffix = { Text("%") },
                )
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = parsed != null,
                onClick = { parsed?.let(onConfirm) },
            ) { Text("Set") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun AddResolutionDialog(
    existing: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var width by remember { mutableStateOf("") }
    var height by remember { mutableStateOf("") }
    val normalized = remember(width, height) {
        Resolutions.normalize("${width}x${height}")
    }
    val duplicate = normalized != null && existing.contains(normalized)
    val error = when {
        width.isBlank() && height.isBlank() -> null
        normalized == null -> "Enter values 320–7680 × 240–4320"
        duplicate -> "Already in the list"
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add custom resolution") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = width,
                        onValueChange = { width = it.filter { c -> c.isDigit() }.take(5) },
                        label = { Text("Width") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("×")
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = height,
                        onValueChange = { height = it.filter { c -> c.isDigit() }.take(5) },
                        label = { Text("Height") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = normalized != null && !duplicate,
                onClick = { normalized?.let(onConfirm) },
            ) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    value: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val baseModifier = Modifier
        .fillMaxWidth()
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }
        .padding(horizontal = 20.dp, vertical = 8.dp)
    Row(
        modifier = baseModifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(36.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier
                    .padding(8.dp)
                    .size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (trailing != null) {
            trailing()
        } else if (value != null) {
            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
