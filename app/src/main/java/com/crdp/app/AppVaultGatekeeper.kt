package com.crdp.app

import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.crdp.core.rdp.model.VaultProtection
import com.crdp.core.rdp.repository.UnlockOutcome
import com.crdp.core.ui.biometric.BiometricPrompter
import com.crdp.core.ui.biometric.BiometricResult
import com.crdp.core.ui.vault.VaultGatekeeper

/**
 * App-side [VaultGatekeeper] impl. Concrete enough to be constructed inside
 * MainActivity's composable tree (where the FragmentActivity, MainViewModel,
 * and the password-prompt deferred all live) and abstract enough that feature
 * modules can call it through [com.crdp.core.ui.vault.LocalVaultGatekeeper]
 * without taking a dependency on the app module.
 *
 * All vault-auth UX flows go through here. Don't inline BiometricPrompter or
 * the password dialog at any other call site — if a new flow needs to read or
 * write the vault, it should call [ensureUnlocked] and trust the result.
 */
class AppVaultGatekeeper(
    private val activity: FragmentActivity,
    private val getProtection: () -> VaultProtection,
    private val isUnlocked: () -> Boolean,
    private val completeDeviceKeyUnlock: suspend () -> UnlockOutcome,
    private val unlockWithPassword: suspend (CharArray) -> UnlockOutcome,
    private val requestPassword: suspend () -> CharArray?,
) : VaultGatekeeper {

    override suspend fun ensureUnlocked(
        title: String,
        subtitle: String?,
        notifyPromptOnPhone: Boolean,
    ): Boolean {
        val protection = getProtection()
        if (protection == VaultProtection.None) return true
        if (isUnlocked()) return true

        return when (protection) {
            VaultProtection.None -> true
            VaultProtection.DeviceKey -> {
                if (!BiometricPrompter.canAuthenticate(activity)) return false
                if (notifyPromptOnPhone) {
                    Toast.makeText(
                        activity,
                        "Check your phone to confirm fingerprint / device verification",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                val result = BiometricPrompter.prompt(
                    activity = activity,
                    title = title,
                    subtitle = subtitle,
                )
                if (result !is BiometricResult.Success) return false
                completeDeviceKeyUnlock() == UnlockOutcome.Success
            }
            VaultProtection.Password -> {
                val pw = requestPassword() ?: return false
                unlockWithPassword(pw) == UnlockOutcome.Success
            }
        }
    }
}
