package com.crdp.core.ui.biometric

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

sealed interface BiometricResult {
    data object Success : BiometricResult

    /** Hardware unavailable, no enrolled biometrics, or feature disabled. */
    data class Unavailable(val reason: String) : BiometricResult

    /** User cancelled or system error. */
    data class Failed(val message: String) : BiometricResult
}

/**
 * Wrapper around [BiometricPrompt] that returns Class-3 (BIOMETRIC_STRONG) or
 * device-credential confirmations only.
 *
 * Strong auth is required because the vault's auth-bound Keystore master key
 * (`crdp_vault_master_v2`, see DeviceKeyVaultCrypto) refuses to release for
 * any prompt that doesn't satisfy `KEYGUARD_SECURE | BIOMETRIC_STRONG` — using
 * `BIOMETRIC_WEAK` here would mean the prompt succeeds but the subsequent
 * decrypt still throws `UserNotAuthenticatedException`.
 */
object BiometricPrompter {

    fun canAuthenticate(context: Context): Boolean {
        val manager = BiometricManager.from(context)
        return manager.canAuthenticate(allowedAuthenticators()) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    /**
     * Allowed authenticators. Strong biometric is always required so the prompt
     * can release the Keystore-resident vault master key. Device credential
     * (PIN/pattern/password) is allowed as a fallback on API 30+. On older
     * Android releases the AndroidX biometric library forbids combining
     * DEVICE_CREDENTIAL with a strong biometric in the same prompt, so we drop
     * the device-credential bit and let the system show the screen-lock
     * fallback via its "Use PIN" button when no biometric is enrolled.
     */
    private fun allowedAuthenticators(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        } else {
            BIOMETRIC_STRONG
        }

    suspend fun prompt(
        activity: FragmentActivity,
        title: String,
        subtitle: String? = null,
    ): BiometricResult {
        val auth = allowedAuthenticators()
        val canAuth = BiometricManager.from(activity).canAuthenticate(auth) ==
            BiometricManager.BIOMETRIC_SUCCESS
        if (!canAuth) {
            return BiometricResult.Unavailable(
                "No strong biometric / device credential available on this device",
            )
        }
        return suspendCancellableCoroutine { cont ->
            val executor = ContextCompat.getMainExecutor(activity)
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (cont.isActive) cont.resume(BiometricResult.Success)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (cont.isActive) cont.resume(BiometricResult.Failed(errString.toString()))
                }

                override fun onAuthenticationFailed() {
                    // Single failed attempt; let the user retry — only resume on error/cancel.
                }
            }
            val prompt = BiometricPrompt(activity, executor, callback)
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .apply { if (subtitle != null) setSubtitle(subtitle) }
                .setAllowedAuthenticators(auth)
                .apply {
                    // setNegativeButtonText is mutually exclusive with
                    // DEVICE_CREDENTIAL — the system supplies its own "Use PIN"
                    // button in that case. Setting both raises IllegalArgumentException.
                    if ((auth and DEVICE_CREDENTIAL) == 0) {
                        setNegativeButtonText("Cancel")
                    }
                }
                .build()
            prompt.authenticate(info)
        }
    }
}
