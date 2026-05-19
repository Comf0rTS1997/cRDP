package com.crdp.app

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.crdp.app.permissions.AppPermission
import com.crdp.app.permissions.AppPermissions
import com.crdp.app.permissions.PermissionStatus
import com.crdp.app.permissions.rememberPermissionLauncher
import com.crdp.app.prefs.AppSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsSettingsScreen(
    appSettings: AppSettings,
    onEnableKeyInterceptor: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    // Status map is recomputed on every resume so returning from the OS app-info
    // or Accessibility-settings page reflects the user's choice without a manual
    // refresh. Runtime grants/revokes via rememberPermissionLauncher also bump
    // this via their onResult callback.
    var statuses by remember {
        mutableStateOf(AppPermission.ALL.associateWith { AppPermissions.status(context, it) })
    }
    fun refresh() {
        statuses = AppPermission.ALL.associateWith { AppPermissions.status(context, it) }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notifLauncher = rememberPermissionLauncher(AppPermission.Notifications) { refresh() }
    val micLauncher = rememberPermissionLauncher(AppPermission.Microphone) { refresh() }
    val camLauncher = rememberPermissionLauncher(AppPermission.Camera) { refresh() }

    var showAccessibilityDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text("Permissions") },
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
            Text(
                text = "cRDP only requests these permissions when the matching feature needs them. " +
                    "You can grant or revoke them here at any time.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )

            PermissionRow(
                icon = Icons.Default.Notifications,
                permission = AppPermission.Notifications,
                status = statuses[AppPermission.Notifications] ?: PermissionStatus.Denied,
                onGrant = { notifLauncher() },
                onRevoke = { AppPermissions.openAppDetailsSettings(context) },
            )
            PermissionRow(
                icon = Icons.Default.Mic,
                permission = AppPermission.Microphone,
                status = statuses[AppPermission.Microphone] ?: PermissionStatus.Denied,
                onGrant = { micLauncher() },
                onRevoke = { AppPermissions.openAppDetailsSettings(context) },
            )
            PermissionRow(
                icon = Icons.Default.Camera,
                permission = AppPermission.Camera,
                status = statuses[AppPermission.Camera] ?: PermissionStatus.Denied,
                onGrant = { camLauncher() },
                onRevoke = { AppPermissions.openAppDetailsSettings(context) },
            )
            KeyInterceptorRow(
                osStatus = statuses[AppPermission.KeyInterceptor] ?: PermissionStatus.Denied,
                switchOn = appSettings.enableKeyInterceptor,
                onSwitchChange = { enabled ->
                    onEnableKeyInterceptor(enabled)
                    if (enabled &&
                        AppPermissions.status(context, AppPermission.KeyInterceptor) !=
                        PermissionStatus.Granted
                    ) {
                        showAccessibilityDialog = true
                    }
                },
                onOpenSettings = { AppPermissions.openAccessibilitySettings(context) },
            )

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showAccessibilityDialog) {
        AccessibilityNeededDialog(
            onOpenSettings = {
                AppPermissions.openAccessibilitySettings(context)
                showAccessibilityDialog = false
            },
            onDismiss = { showAccessibilityDialog = false },
        )
    }
}

/**
 * Status row + Grant/Revoke action for a runtime permission. Layout mirrors
 * [SettingRow] (icon tile, title, subtitle) but trailing slot carries a
 * status chip and a TextButton instead of a switch.
 */
@Composable
private fun PermissionRow(
    icon: ImageVector,
    permission: AppPermission,
    status: PermissionStatus,
    onGrant: () -> Unit,
    onRevoke: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
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
            Text(permission.featureLabel, style = MaterialTheme.typography.bodyLarge)
            Text(
                permission.featureDescription,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChip(status)
                Spacer(Modifier.width(8.dp))
                when (status) {
                    PermissionStatus.Granted ->
                        TextButton(onClick = onRevoke) { Text("Revoke") }
                    PermissionStatus.Denied ->
                        TextButton(onClick = onGrant) { Text("Grant") }
                    PermissionStatus.NotApplicable -> Unit
                }
            }
        }
    }
}

/**
 * Hardware-key capture is special: it has both an in-app preference (the
 * user's intent) and an OS-side Accessibility-service state (the actual
 * capability). The row shows both — a switch for the pref and a chip for
 * the OS state — so the user can tell whether they need to follow up in
 * system settings after flipping the switch on.
 */
@Composable
private fun KeyInterceptorRow(
    osStatus: PermissionStatus,
    switchOn: Boolean,
    onSwitchChange: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(36.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Icon(
                imageVector = Icons.Default.Accessibility,
                contentDescription = null,
                modifier = Modifier
                    .padding(8.dp)
                    .size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(AppPermission.KeyInterceptor.featureLabel, style = MaterialTheme.typography.bodyLarge)
            Text(
                AppPermission.KeyInterceptor.featureDescription,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChip(osStatus)
                if (osStatus == PermissionStatus.Denied) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onOpenSettings) { Text("Open settings") }
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = switchOn, onCheckedChange = onSwitchChange)
    }
}

@Composable
private fun StatusChip(status: PermissionStatus) {
    val (label, color) = when (status) {
        PermissionStatus.Granted -> "Granted" to MaterialTheme.colorScheme.primary
        PermissionStatus.Denied -> "Denied" to MaterialTheme.colorScheme.error
        PermissionStatus.NotApplicable -> "N/A" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = AssistChipDefaults.shape,
        color = color.copy(alpha = 0.12f),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
internal fun AccessibilityNeededDialog(
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enable Accessibility access") },
        text = {
            Text(
                "Hardware-key capture relies on Android's Accessibility service to intercept " +
                    "system shortcuts like Alt+F4 before they close the app. cRDP only filters " +
                    "key events while a session is active — it does not observe screen content " +
                    "or other apps.\n\n" +
                    "Open Accessibility settings and turn on \"cRDP\" to finish enabling this feature.",
            )
        },
        confirmButton = {
            TextButton(onClick = onOpenSettings) { Text("Open Accessibility settings") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
