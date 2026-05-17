package com.crdp.core.ui.vault

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Single point where "user wants to read or write the vault and it might be
 * locked" is resolved. Implementations are responsible for:
 *
 *  - Returning true immediately when the vault doesn't need a gate
 *    ([com.crdp.core.rdp.model.VaultProtection.None]) or is already unlocked
 *    inside the auto-lock window.
 *  - Otherwise driving the user-visible auth UI — BiometricPrompt for
 *    DeviceKey, an in-app password dialog for Password — and resolving with
 *    true when unlock succeeded, false on cancel/failure.
 *
 * Concentrating the policy here means every callsite that touches the vault
 * (editor's credential picker, connect path, vault settings screen, future
 * screens) shares the same prompt/UX rules and we can't silently no-op a
 * locked write because someone forgot to call the unlocker.
 */
interface VaultGatekeeper {
    /**
     * Suspends until the vault is unlocked (or the user dismisses the prompt).
     *
     * @param title primary string on the BiometricPrompt / password dialog.
     * @param subtitle secondary line (e.g. the profile name) or null.
     * @param notifyPromptOnPhone true when the caller is on an external
     *   display (DeX / tablet expanded window) and the biometric sheet will
     *   render on the phone's built-in screen — surface a toast so the user
     *   knows where to look.
     */
    suspend fun ensureUnlocked(
        title: String = "Unlock vault",
        subtitle: String? = null,
        notifyPromptOnPhone: Boolean = false,
    ): Boolean
}

val LocalVaultGatekeeper = staticCompositionLocalOf<VaultGatekeeper> {
    error("VaultGatekeeper not provided in composition")
}
