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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.crdp.core.rdp.model.AudioMode
import com.crdp.core.rdp.model.AudioQuality
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ConnectionEditorRoute(
    viewModel: ConnectionEditorViewModel,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    onSaveAndConnect: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

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
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                val error = when {
                                    state.mode == EditorMode.Direct && state.host.isBlank() ->
                                        "Host or IP is required"
                                    state.mode == EditorMode.Direct &&
                                        (state.port.toIntOrNull() ?: -1) !in 1..65535 ->
                                        "Port must be 1–65535"
                                    state.mode == EditorMode.Gateway && state.gatewayBaseUrl.isBlank() ->
                                        "Gateway URL is required"
                                    state.mode == EditorMode.Gateway &&
                                        !state.gatewayBaseUrl.startsWith("http") ->
                                        "URL must start with http:// or https://"
                                    else -> null
                                }
                                snackbarHostState.showSnackbar(
                                    error ?: "Details look valid — tap Save & connect to test live.",
                                )
                            }
                        },
                    ) { Text("Test") }
                    Spacer(modifier = Modifier.weight(1f))
                    FilledTonalButton(
                        onClick = { viewModel.save(onSaved) },
                        modifier = Modifier.padding(end = 8.dp),
                    ) { Text("Save") }
                    Button(onClick = { viewModel.save(onSaveAndConnect) }) {
                        Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                        Text("Save & connect", modifier = Modifier.padding(start = 4.dp))
                    }
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
                OutlinedTextField(
                    value = state.username,
                    onValueChange = viewModel::updateUsername,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Username") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = state.domain,
                    onValueChange = viewModel::updateDomain,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Domain (optional)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = state.password,
                    onValueChange = viewModel::updatePassword,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    supportingText = { Text("Saved with the connection profile") },
                    leadingIcon = { Icon(Icons.Default.Lock, null) },
                    singleLine = true,
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Fingerprint,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 12.dp),
                    ) {
                        Text("Use biometric unlock", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(
                            "Require biometric before connect",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.requireBiometric,
                        onCheckedChange = viewModel::updateRequireBiometric,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
        }
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
