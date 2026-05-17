package com.crdp.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.crdp.app.prefs.AppSettings
import com.crdp.app.prefs.AutoLockVault
import com.crdp.core.rdp.model.VaultProtection
import com.crdp.core.ui.vault.LocalVaultGatekeeper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun VaultGate(
    appSettings: AppSettings,
    mainViewModel: MainViewModel,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val gatekeeper = LocalVaultGatekeeper.current
    val unlockedAt by mainViewModel.vaultUnlockedAt.collectAsStateWithLifecycle()
    val vaultStatus by mainViewModel.vaultStatus.collectAsStateWithLifecycle()
    val autoLockMinutes = appSettings.autoLockVaultMinutes
    val unlocked = when {
        // For an unprotected vault there's no gate. Distinct from "auth window
        // expired" — None mode doesn't have an auth window at all.
        appSettings.vaultProtection == VaultProtection.None -> true
        unlockedAt == null -> false
        autoLockMinutes == AutoLockVault.NEVER -> true
        autoLockMinutes == AutoLockVault.IMMEDIATELY -> false
        else -> System.currentTimeMillis() - unlockedAt!! < autoLockMinutes * 60_000L
    }

    LaunchedEffect(unlockedAt, autoLockMinutes) {
        if (unlockedAt == null) return@LaunchedEffect
        if (autoLockMinutes <= 0) return@LaunchedEffect
        val expiresAt = unlockedAt!! + autoLockMinutes * 60_000L
        val remaining = expiresAt - System.currentTimeMillis()
        if (remaining > 0) {
            delay(remaining)
            mainViewModel.lockVault()
        } else {
            mainViewModel.lockVault()
        }
    }

    if (unlocked) {
        content()
        return
    }

    var prompting by remember { mutableStateOf(false) }
    var promptError by remember { mutableStateOf<String?>(null) }

    fun launchPrompt() {
        if (prompting) return
        prompting = true
        promptError = null
        scope.launch {
            val ok = gatekeeper.ensureUnlocked(
                title = "Unlock Vault",
                subtitle = "Authenticate to view saved credentials",
            )
            prompting = false
            if (!ok) {
                promptError = "Unlock cancelled or failed."
            }
        }
    }

    // First-time entry: auto-launch the prompt so the user doesn't need to tap
    // a button just to be asked for biometrics. If they cancel, the gate
    // stays on screen with the Unlock button so they can retry manually.
    LaunchedEffect(Unit) { launchPrompt() }

    GateScaffold(
        icon = Icons.Default.Fingerprint,
        title = "Vault locked",
        subtitle = "Authenticate to view saved credentials.",
        error = vaultStatus.error ?: promptError,
        actionLabel = if (prompting) "Authenticating…" else "Unlock",
        actionEnabled = !prompting,
        onAction = ::launchPrompt,
    )
}

@Composable
private fun GateScaffold(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    error: String?,
    actionLabel: String,
    actionEnabled: Boolean,
    onAction: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            modifier = Modifier.size(96.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.padding(24.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAction, enabled = actionEnabled) { Text(actionLabel) }
    }
}
