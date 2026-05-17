package com.crdp.feature.connections

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.crdp.core.rdp.model.ConnectionProfile
import com.crdp.core.rdp.model.DirectConnectionProfile
import com.crdp.core.rdp.model.GatewayConnectionProfile
import com.crdp.core.rdp.reachability.ReachabilityProbe

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionDetailsRoute(
    viewModel: ConnectionDetailsViewModel,
    onBack: () -> Unit,
    onConnect: (String) -> Unit,
    onEdit: (String) -> Unit,
) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val reachability by viewModel.reachability.collectAsStateWithLifecycle()

    if (profile == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Loading…")
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { padding ->
        ConnectionDetailsPaneContent(
            profile = profile!!,
            reachability = reachability,
            onConnect = { onConnect(profile!!.id) },
            onEdit = { onEdit(profile!!.id) },
            onDelete = { viewModel.delete(onBack) },
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
fun ConnectionDetailsPaneContent(
    profile: ConnectionProfile,
    reachability: ReachabilityProbe?,
    onConnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val addressLine = when (profile) {
        is DirectConnectionProfile -> "${profile.username.ifBlank { "rdp" }}@${profile.host}"
        is GatewayConnectionProfile -> "${profile.targetHost}:${profile.targetPort}"
    }

    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete connection?") },
            text = { Text("\"${profile.displayName}\" will be permanently removed.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    onDelete()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
            // Header card
            Surface(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            modifier = Modifier.size(56.dp),
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = profile.displayName.take(2).uppercase(),
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = profile.displayName,
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = addressLine,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TagChip(label = when (profile) {
                            is DirectConnectionProfile -> "Direct"
                            is GatewayConnectionProfile -> "Gateway"
                        })
                    }

                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = onConnect,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) {
                        Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Connect", maxLines = 1, softWrap = false)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilledTonalButton(
                            onClick = onEdit,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.Folder, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Edit", maxLines = 1, softWrap = false)
                        }
                        FilledTonalButton(
                            onClick = { showDeleteDialog = true },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                        ) {
                            Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Delete", maxLines = 1, softWrap = false)
                        }
                    }
                }
            }

            // Session stats grid
            SectionLabel("Session")
            val resStr = resolutionLabel(profile)
            val colorDepthStr = when (profile) {
                is DirectConnectionProfile -> "${profile.colorDepth}-bit"
                is GatewayConnectionProfile -> "32-bit"
            }
            val scaleStr = when (profile) {
                is DirectConnectionProfile -> scaleLabel(profile.desktopScaleFactor)
                is GatewayConnectionProfile -> scaleLabel(profile.desktopScaleFactor)
            }
            val latencyValue = when {
                reachability == null -> "—"
                !reachability.online -> "Offline"
                else -> reachability.latencyMs?.let { "${it} ms" } ?: "—"
            }
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard(label = "Latency", value = latencyValue, modifier = Modifier.weight(1f))
                    StatCard(label = "Resolution", value = resStr, modifier = Modifier.weight(1f))
                }
                StatCard(
                    label = "Color depth",
                    value = colorDepthStr,
                    modifier = Modifier.fillMaxWidth(),
                )
                StatCard(
                    label = stringResource(R.string.connections_clipboard_details),
                    value = clipboardSyncLabel(profile),
                    modifier = Modifier.fillMaxWidth(),
                )
                StatCard(
                    label = "Printer share",
                    value = printerShareLabel(profile),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SectionLabel("Display & performance")
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column {
                    DetailRow(
                        title = "Resolution",
                        value = resStr,
                        onClick = onEdit,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    DetailRow(
                        title = "Color depth",
                        value = colorDepthStr,
                        onClick = onEdit,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    DetailRow(
                        title = "DPI scaling",
                        value = scaleStr,
                        onClick = onEdit,
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
    }
}

private fun resolutionLabel(profile: ConnectionProfile): String {
    val auto = when (profile) {
        is DirectConnectionProfile -> profile.autoResolution
        is GatewayConnectionProfile -> profile.autoResolution
    }
    if (auto) return "Auto (window size)"
    val (w, h) = when (profile) {
        is DirectConnectionProfile -> profile.width to profile.height
        is GatewayConnectionProfile -> profile.width to profile.height
    }
    return "${w}×${h}"
}

private fun scaleLabel(percent: Int): String =
    if (percent <= 0) "App default" else "$percent%"

private fun clipboardSyncLabel(profile: ConnectionProfile): String = when (profile) {
    is DirectConnectionProfile -> when (profile.clipboardSyncOverride) {
        null -> "App default"
        true -> "On"
        false -> "Off"
    }
    is GatewayConnectionProfile -> when (profile.clipboardSyncOverride) {
        null -> "App default"
        true -> "On"
        false -> "Off"
    }
}

private fun printerShareLabel(profile: ConnectionProfile): String = when (profile) {
    is DirectConnectionProfile -> when (profile.printerShareOverride) {
        null -> "App default"
        true -> "On"
        false -> "Off"
    }
    is GatewayConnectionProfile -> when (profile.printerShareOverride) {
        null -> "App default"
        true -> "On"
        false -> "Off"
    }
}

// Replacement for Material3 ListItem to avoid an intrinsic-measurement crash
// ("maxWidth(-72) must be >= minWidth(0)") that fires inside a verticalScroll
// Column on Compose BOM 2024.12.01 / Material3 1.3.x.
@Composable
private fun DetailRow(
    title: String,
    value: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun TagChip(label: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
