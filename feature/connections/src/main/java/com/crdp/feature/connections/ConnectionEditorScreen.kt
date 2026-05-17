package com.crdp.feature.connections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.crdp.core.rdp.model.AudioMode
import com.crdp.core.rdp.model.AudioQuality
import com.crdp.core.ui.vault.LocalVaultGatekeeper
import kotlinx.coroutines.launch

/**
 * App-level defaults applied to a brand-new profile on first composition. Used
 * to seed [EditorUiState.width]/[EditorUiState.height]/[EditorUiState.autoResolution]
 * from the user's "Default resolution" setting so the editor stops always
 * defaulting to 1280x720.
 */
data class NewProfileDefaults(
    val autoResolution: Boolean = false,
    val width: Int = 1280,
    val height: Int = 720,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ConnectionEditorRoute(
    viewModel: ConnectionEditorViewModel,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    newProfileDefaults: NewProfileDefaults = NewProfileDefaults(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(state.isNew, newProfileDefaults) {
        if (state.isNew) {
            viewModel.applyNewProfileDefaults(
                autoResolution = newProfileDefaults.autoResolution,
                width = newProfileDefaults.width,
                height = newProfileDefaults.height,
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isNew) "New connection" else "Edit connection") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        bottomBar = {
            Surface(
                shadowElevation = 4.dp,
                tonalElevation = 2.dp,
            ) {
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Single primary action: save the profile. Connecting is one tap
                    // away from the connections list, so the dual-action bottom bar
                    // didn't earn its width. Pinned to the brand blue (the theme
                    // seed) so dynamic-color devices don't recolor it.
                    Button(
                        onClick = { viewModel.save(onSaved) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1565C0),
                            contentColor = Color.White,
                        ),
                    ) { Text("Save") }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Transport ─────────────────────────────────────────────
            SectionLabel("Transport")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.mode == EditorMode.Direct,
                    onClick = { viewModel.setMode(EditorMode.Direct) },
                    label = { Text("Direct TCP") },
                )
                FilterChip(
                    selected = state.mode == EditorMode.Gateway,
                    onClick = { viewModel.setMode(EditorMode.Gateway) },
                    label = { Text("Gateway") },
                )
            }

            // ── Identity ──────────────────────────────────────────────
            SectionLabel("Identity")
            OutlinedTextField(
                value = state.displayName,
                onValueChange = viewModel::updateDisplayName,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Display name") },
                singleLine = true,
            )

            when (state.mode) {
                EditorMode.Direct -> {
                    OutlinedTextField(
                        value = state.host,
                        onValueChange = viewModel::updateHost,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Host or IP") },
                        placeholder = { Text("hostname, IPv4/IPv6") },
                        supportingText = { Text("hostname, IPv4/IPv6, or .ts.net address") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = state.port,
                        onValueChange = viewModel::updatePort,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Port") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                }
                EditorMode.Gateway -> {
                    OutlinedTextField(
                        value = state.gatewayBaseUrl,
                        onValueChange = viewModel::updateGatewayBaseUrl,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Gateway base URL") },
                        singleLine = true,
                        placeholder = { Text("https://api.example.com") },
                    )
                    OutlinedTextField(
                        value = state.targetHost,
                        onValueChange = viewModel::updateTargetHost,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Target host") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = state.targetPort,
                        onValueChange = viewModel::updateTargetPort,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Target port") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = state.bearerToken,
                        onValueChange = viewModel::updateBearer,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Bearer token (optional)") },
                        singleLine = true,
                    )
                }
            }

            // ── Resolution ────────────────────────────────────────────
            SectionLabel("Resolution")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.autoResolution,
                    onClick = { viewModel.updateAutoResolution(true) },
                    label = { Text("Auto (window size)") },
                )
                FilterChip(
                    selected = !state.autoResolution,
                    onClick = { viewModel.updateAutoResolution(false) },
                    label = { Text("Manual") },
                )
            }
            if (!state.autoResolution) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = state.width,
                        onValueChange = viewModel::updateWidth,
                        modifier = Modifier.weight(1f),
                        label = { Text("Width") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = state.height,
                        onValueChange = viewModel::updateHeight,
                        modifier = Modifier.weight(1f),
                        label = { Text("Height") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                }
            }

            // ── DPI scaling ───────────────────────────────────────────
            SectionLabel("DPI scaling")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.desktopScaleFactor.isBlank() ||
                        state.desktopScaleFactor == "0",
                    onClick = { viewModel.updateDesktopScaleFactor("") },
                    label = { Text("Use app default") },
                )
                listOf(100, 125, 140, 150, 175, 200).forEach { preset ->
                    FilterChip(
                        selected = state.desktopScaleFactor.toIntOrNull() == preset,
                        onClick = { viewModel.updateDesktopScaleFactor(preset.toString()) },
                        label = { Text("$preset%") },
                    )
                }
            }
            OutlinedTextField(
                value = state.desktopScaleFactor,
                onValueChange = viewModel::updateDesktopScaleFactor,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Custom scale (100–500%)") },
                supportingText = {
                    Text("Blank = follow the app default. Server-side desktop scale.")
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )

            // ── Color depth ───────────────────────────────────────────
            if (state.mode == EditorMode.Direct) {
                SectionLabel("Color depth")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(8, 15, 16, 24, 32).forEach { bpp ->
                        FilterChip(
                            selected = state.colorDepth == bpp,
                            onClick = { viewModel.updateColorDepth(bpp) },
                            label = { Text("${bpp}-bit") },
                        )
                    }
                }
            }

            // ── Audio ─────────────────────────────────────────────────
            SectionLabel("Audio")
            Text(
                "Playback",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.audioMode == AudioMode.UseAppDefault,
                    onClick = { viewModel.updateAudioMode(AudioMode.UseAppDefault) },
                    label = { Text("App default") },
                )
                FilterChip(
                    selected = state.audioMode == AudioMode.LocalDevice,
                    onClick = { viewModel.updateAudioMode(AudioMode.LocalDevice) },
                    label = { Text("Local device") },
                )
                FilterChip(
                    selected = state.audioMode == AudioMode.RemoteConsole,
                    onClick = { viewModel.updateAudioMode(AudioMode.RemoteConsole) },
                    label = { Text("Remote PC") },
                )
                FilterChip(
                    selected = state.audioMode == AudioMode.Disabled,
                    onClick = { viewModel.updateAudioMode(AudioMode.Disabled) },
                    label = { Text("Off") },
                )
            }
            Text(
                "Microphone",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.microphoneOverride == null,
                    onClick = { viewModel.updateMicrophoneOverride(null) },
                    label = { Text("App default") },
                )
                FilterChip(
                    selected = state.microphoneOverride == true,
                    onClick = { viewModel.updateMicrophoneOverride(true) },
                    label = { Text("On") },
                )
                FilterChip(
                    selected = state.microphoneOverride == false,
                    onClick = { viewModel.updateMicrophoneOverride(false) },
                    label = { Text("Off") },
                )
            }
            Text(
                stringResource(R.string.connections_clipboard_section),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.connections_clipboard_caption),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.clipboardSyncOverride == null,
                    onClick = { viewModel.updateClipboardSyncOverride(null) },
                    label = { Text("App default") },
                )
                FilterChip(
                    selected = state.clipboardSyncOverride == true,
                    onClick = { viewModel.updateClipboardSyncOverride(true) },
                    label = { Text("On") },
                )
                FilterChip(
                    selected = state.clipboardSyncOverride == false,
                    onClick = { viewModel.updateClipboardSyncOverride(false) },
                    label = { Text("Off") },
                )
            }
            Text(
                "Printer share",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Expose a virtual printer to the remote session. Print jobs land in this app's external files dir.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.printerShareOverride == null,
                    onClick = { viewModel.updatePrinterShareOverride(null) },
                    label = { Text("App default") },
                )
                FilterChip(
                    selected = state.printerShareOverride == true,
                    onClick = { viewModel.updatePrinterShareOverride(true) },
                    label = { Text("On") },
                )
                FilterChip(
                    selected = state.printerShareOverride == false,
                    onClick = { viewModel.updatePrinterShareOverride(false) },
                    label = { Text("Off") },
                )
            }
            VisualToggleRow(
                label = "Desktop background",
                value = state.showDesktopBackgroundOverride,
                onChange = viewModel::updateShowDesktopBackgroundOverride,
            )
            VisualToggleRow(
                label = "Window contents while dragging",
                value = state.windowContentsWhileDraggingOverride,
                onChange = viewModel::updateWindowContentsWhileDraggingOverride,
            )
            VisualToggleRow(
                label = "Menu animations",
                value = state.menuAnimationsOverride,
                onChange = viewModel::updateMenuAnimationsOverride,
            )
            Text(
                "Quality",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.audioQuality == AudioQuality.UseAppDefault,
                    onClick = { viewModel.updateAudioQuality(AudioQuality.UseAppDefault) },
                    label = { Text("App default") },
                )
                FilterChip(
                    selected = state.audioQuality == AudioQuality.Dynamic,
                    onClick = { viewModel.updateAudioQuality(AudioQuality.Dynamic) },
                    label = { Text("Dynamic") },
                )
                FilterChip(
                    selected = state.audioQuality == AudioQuality.Medium,
                    onClick = { viewModel.updateAudioQuality(AudioQuality.Medium) },
                    label = { Text("Medium") },
                )
                FilterChip(
                    selected = state.audioQuality == AudioQuality.High,
                    onClick = { viewModel.updateAudioQuality(AudioQuality.High) },
                    label = { Text("High") },
                )
            }

            // ── Camera ────────────────────────────────────────────────
            SectionLabel("Camera")
            Text(
                "Redirect",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.cameraMode == com.crdp.core.rdp.model.CameraMode.UseAppDefault,
                    onClick = { viewModel.updateCameraMode(com.crdp.core.rdp.model.CameraMode.UseAppDefault) },
                    label = { Text("App default") },
                )
                FilterChip(
                    selected = state.cameraMode == com.crdp.core.rdp.model.CameraMode.Disabled,
                    onClick = { viewModel.updateCameraMode(com.crdp.core.rdp.model.CameraMode.Disabled) },
                    label = { Text("Off") },
                )
                FilterChip(
                    selected = state.cameraMode == com.crdp.core.rdp.model.CameraMode.BuiltInFront,
                    onClick = { viewModel.updateCameraMode(com.crdp.core.rdp.model.CameraMode.BuiltInFront) },
                    label = { Text("Front") },
                )
                FilterChip(
                    selected = state.cameraMode == com.crdp.core.rdp.model.CameraMode.BuiltInBack,
                    onClick = { viewModel.updateCameraMode(com.crdp.core.rdp.model.CameraMode.BuiltInBack) },
                    label = { Text("Back") },
                )
                FilterChip(
                    selected = state.cameraMode == com.crdp.core.rdp.model.CameraMode.External,
                    onClick = { viewModel.updateCameraMode(com.crdp.core.rdp.model.CameraMode.External) },
                    label = { Text("External (USB)") },
                )
            }
            if (state.cameraMode == com.crdp.core.rdp.model.CameraMode.Specific) {
                OutlinedTextField(
                    value = state.cameraDeviceId.orEmpty(),
                    onValueChange = viewModel::updateCameraDeviceId,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Native device id (e.g. usb:1234:5678)") },
                    singleLine = true,
                )
            }

            // ── Credentials ───────────────────────────────────────────
            if (state.mode == EditorMode.Direct) {
                SectionLabel("Credentials")
                val entries by viewModel.vaultEntries.collectAsStateWithLifecycle()
                VaultEntryPicker(
                    entries = entries,
                    selectedId = state.vaultEntryId,
                    onSelect = viewModel::updateVaultEntryId,
                    onCreateNew = { entry -> viewModel.saveAndSelectVaultEntry(entry) },
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VaultEntryPicker(
    entries: List<com.crdp.core.rdp.model.VaultEntry>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onCreateNew: (com.crdp.core.rdp.model.VaultEntry) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val gatekeeper = LocalVaultGatekeeper.current
    val scope = rememberCoroutineScope()

    val selected = entries.firstOrNull { it.id == selectedId }
    val label = when {
        selected != null -> selected.displayName
        selectedId != null -> "(missing — pick another)"
        else -> "No credentials saved"
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            label = { Text("Credential") },
            leadingIcon = { Icon(Icons.Default.Lock, null) },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            supportingText = {
                val sub = selected?.let {
                    buildString {
                        if (!it.domain.isNullOrBlank()) append(it.domain).append('\\')
                        append(it.username.ifBlank { "(no username)" })
                    }
                } ?: "You'll be prompted at connect time"
                Text(sub)
            },
        )
        ExposedDropdownMenuBoxScope_ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("None (prompt at connect)") },
                onClick = {
                    expanded = false
                    onSelect(null)
                },
            )
            if (entries.isNotEmpty()) {
                HorizontalDivider()
            }
            entries.forEach { e ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(e.displayName)
                            Text(
                                buildString {
                                    if (!e.domain.isNullOrBlank()) append(e.domain).append('\\')
                                    append(e.username.ifBlank { "(no username)" })
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(e.id)
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("+ New credential…") },
                onClick = {
                    expanded = false
                    creating = true
                },
            )
        }
    }

    if (creating) {
        VaultEntryDialog(
            initial = null,
            onCancel = { creating = false },
            onSave = { entry ->
                // Vault writes go through the single gatekeeper. For encrypted
                // protections (DeviceKey / Password) that aren't already
                // unlocked, this shows the biometric / password prompt and
                // only proceeds with the upsert on success. Without this the
                // editor would silently no-op a locked write — VaultRepository
                // returns VaultOpResult.Locked, and the user just sees the
                // credential vanish when they reopen the editor.
                scope.launch {
                    if (gatekeeper.ensureUnlocked(
                            title = "Unlock vault",
                            subtitle = "Authenticate to save this credential",
                        )
                    ) {
                        saveError = null
                        onCreateNew(entry)
                        creating = false
                    } else {
                        saveError = "Vault is locked — credential not saved."
                    }
                }
            },
        )
    }

    if (saveError != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { saveError = null },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { saveError = null }) {
                    Text("OK")
                }
            },
            title = { Text("Credential not saved") },
            text = { Text(saveError!!) },
        )
    }
}

/**
 * `ExposedDropdownMenuBoxScope.ExposedDropdownMenu` is an extension on the box's
 * scope; declaring this thin helper composable lets the caller use it without
 * referencing the scope by name (which lives inside the lambda receiver).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun androidx.compose.material3.ExposedDropdownMenuBoxScope.ExposedDropdownMenuBoxScope_ExposedDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) = ExposedDropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest, content = content)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun VisualToggleRow(
    label: String,
    value: Boolean?,
    onChange: (Boolean?) -> Unit,
) {
    Text(
        label,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = value == null,
            onClick = { onChange(null) },
            label = { Text("App default") },
        )
        FilterChip(
            selected = value == true,
            onClick = { onChange(true) },
            label = { Text("On") },
        )
        FilterChip(
            selected = value == false,
            onClick = { onChange(false) },
            label = { Text("Off") },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = androidx.compose.ui.unit.TextUnit(0.4f, androidx.compose.ui.unit.TextUnitType.Sp),
        modifier = Modifier.padding(top = 8.dp, bottom = 0.dp),
    )
}
