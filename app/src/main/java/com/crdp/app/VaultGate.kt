package com.crdp.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.crdp.app.prefs.AppSettings
import com.crdp.app.prefs.AutoLockVault
import com.crdp.core.ui.biometric.BiometricPrompter
import com.crdp.core.ui.biometric.BiometricResult
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
    val unlocked = remember(unlockedAt, appSettings.autoLockVaultMinutes) {
        if (unlockedAt == null) false
        else when (val limit = appSettings.autoLockVaultMinutes) {
            AutoLockVault.NEVER -> true
            AutoLockVault.IMMEDIATELY -> false
            else -> System.currentTimeMillis() - unlockedAt!! < limit * 60_000L
        }
    }

    // Vault encryption off → no gate (plaintext storage was the user's explicit choice).
    if (!appSettings.vaultEncryption || unlocked) {
        content()
        return
    }

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
            prompting = false
            when (result) {
                is BiometricResult.Success -> mainViewModel.markVaultUnlocked()
                is BiometricResult.Failed -> promptError = result.message
                is BiometricResult.Unavailable -> promptError = result.reason
            }
        }
    }

    LaunchedEffect(Unit) {
        if (BiometricPrompter.canAuthenticate(context)) {
            launchPrompt()
        } else {
            promptError = "No biometric credentials enrolled on this device"
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
                Icons.Default.Fingerprint,
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
            text = "Authenticate with biometrics to view saved credentials.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (promptError != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = promptError!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = ::launchPrompt, enabled = !prompting) {
            Text(if (prompting) "Authenticating…" else "Unlock")
        }
    }
}
