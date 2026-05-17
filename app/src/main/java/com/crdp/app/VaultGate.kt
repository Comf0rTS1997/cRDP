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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.crdp.app.prefs.AppSettings
import com.crdp.app.prefs.AutoLockVault
import com.crdp.core.rdp.model.VaultProtection
import com.crdp.core.rdp.repository.UnlockOutcome
import com.crdp.core.ui.biometric.BiometricPrompter
import com.crdp.core.ui.biometric.BiometricResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun VaultGate(
    appSettings: AppSettings,
    mainViewModel: MainViewModel,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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

    when (appSettings.vaultProtection) {
        VaultProtection.None -> content() // unreachable, [unlocked] is true above
        VaultProtection.DeviceKey -> DeviceKeyUnlockGate(
            mainViewModel = mainViewModel,
            unreadableError = vaultStatus.error,
        )
        VaultProtection.Password -> PasswordUnlockGate(
            mainViewModel = mainViewModel,
            unreadableError = vaultStatus.error,
        )
    }
}

@Composable
private fun DeviceKeyUnlockGate(
    mainViewModel: MainViewModel,
    unreadableError: String?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var promptError by remember { mutableStateOf<String?>(null) }
    var prompting by remember { mutableStateOf(false) }

    fun launchPrompt() {
        val activity = context as? FragmentActivity ?: run {
            promptError = "Biometric not available"
            return
        }
        prompting = true
        scope.launch {
            val result = BiometricPrompter.prompt(
                activity = activity,
                title = "Unlock Vault",
                subtitle = "Authenticate to view saved credentials",
            )
            when (result) {
                is BiometricResult.Success -> {
                    val outcome = mainViewModel.completeDeviceKeyUnlock()
                    if (outcome != UnlockOutcome.Success) {
                        promptError = "Unlock succeeded but vault couldn't be decrypted. " +
                            "The device key may have been invalidated."
                    } else {
                        promptError = null
                    }
                }
                is BiometricResult.Failed -> promptError = result.message
                is BiometricResult.Unavailable -> promptError = result.reason
            }
            prompting = false
        }
    }

    LaunchedEffect(Unit) {
        if (BiometricPrompter.canAuthenticate(context)) {
            launchPrompt()
        } else {
            promptError = "No strong biometric or device credential enrolled. " +
                "Switch the vault to Password protection in Settings."
        }
    }

    GateScaffold(
        icon = Icons.Default.Fingerprint,
        title = "Vault locked",
        subtitle = "Authenticate with biometrics or device credential to view saved credentials.",
        error = unreadableError ?: promptError,
        actionLabel = if (prompting) "Authenticating…" else "Unlock",
        actionEnabled = !prompting,
        onAction = ::launchPrompt,
    )
}

@Composable
private fun PasswordUnlockGate(
    mainViewModel: MainViewModel,
    unreadableError: String?,
) {
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var inFlight by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        if (password.isEmpty() || inFlight) return
        inFlight = true
        val arr = password.toCharArray()
        password = ""
        scope.launch {
            val outcome = mainViewModel.unlockWithPassword(arr)
            inFlight = false
            error = when (outcome) {
                UnlockOutcome.Success -> null
                UnlockOutcome.WrongPassword -> "Wrong password."
                UnlockOutcome.NotConfigured ->
                    "The on-disk vault is not in password format. " +
                        "Open Settings → Vault protection and switch modes."
                UnlockOutcome.Failed ->
                    "Could not decrypt the vault. " +
                        (unreadableError ?: "")
            }
        }
    }

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
                Icons.Default.Lock,
                contentDescription = null,
                modifier = Modifier.padding(24.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = "Vault locked",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Enter your vault password to decrypt saved credentials.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it; error = null },
            label = { Text("Password") },
            singleLine = true,
            enabled = !inFlight,
            isError = error != null,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        val combinedError = error ?: unreadableError
        if (combinedError != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = combinedError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = ::submit,
            enabled = password.isNotEmpty() && !inFlight,
        ) {
            Text(if (inFlight) "Unlocking…" else "Unlock")
        }
    }
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
