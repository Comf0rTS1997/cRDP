package com.crdp.core.ui.biometric

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
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

object BiometricPrompter {

    fun canAuthenticate(context: Context): Boolean {
        val manager = BiometricManager.from(context)
        return manager.canAuthenticate(BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
    }

    suspend fun prompt(
        activity: FragmentActivity,
        title: String,
        subtitle: String? = null,
    ): BiometricResult {
        val canAuth = canAuthenticate(activity)
        if (!canAuth) {
            return BiometricResult.Unavailable("No biometric credentials enrolled")
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
                .setAllowedAuthenticators(BIOMETRIC_WEAK)
                .setNegativeButtonText("Cancel")
                .build()
            prompt.authenticate(info)
        }
    }
}
