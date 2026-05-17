package com.crdp.app.data.vault

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore

/**
 * Vault stored as a Tink AES-256-GCM-HKDF-4KB [EncryptedFile] wrapped by an
 * AndroidKeystore-resident master key that requires recent user authentication
 * (biometric strong / device credential). The key is created on first use; any
 * decrypt/encrypt outside the [validitySeconds] window after a successful
 * auth raises [UserNotAuthenticatedException], which surfaces here as
 * [VaultLockedException] so the UI can prompt and retry.
 *
 * Uses a distinct alias from the always-on profile-store master key so that
 * gating the vault doesn't also gate the (non-credential) profile blob.
 */
internal class DeviceKeyVaultCrypto(
    private val appContext: Context,
    private val target: File,
    private val tmp: File,
    /**
     * Seconds the OS will let the auth-bound key be used after a successful
     * BiometricPrompt / device-credential confirmation. Picked at master-key
     * creation time and immutable thereafter; changing it requires destroying
     * and re-creating the key (which loses access to data encrypted under the
     * old one). Matches the longest "auto-lock vault" preset (5 min) so the
     * vault doesn't re-prompt mid-window.
     */
    private val validitySeconds: Int = DEFAULT_VALIDITY_SECONDS,
) : VaultCrypto {

    companion object {
        const val ALIAS = "crdp_vault_master_v2"
        const val DEFAULT_VALIDITY_SECONDS = 300
        private const val KEYSTORE = "AndroidKeyStore"
    }

    private val masterKey: MasterKey by lazy { buildMasterKey() }

    private fun buildMasterKey(): MasterKey {
        return MasterKey.Builder(appContext, ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .setUserAuthenticationRequired(true, validitySeconds)
            .build()
    }

    private fun encryptedFile(target: File) = EncryptedFile.Builder(
        appContext,
        target,
        masterKey,
        EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
    ).build()

    override suspend fun read(): String? = withContext(Dispatchers.IO) {
        if (!target.exists()) return@withContext null
        try {
            encryptedFile(target).openFileInput().bufferedReader().use { it.readText() }
        } catch (e: UserNotAuthenticatedException) {
            throw VaultLockedException("Vault locked: biometric/device-credential required")
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw VaultUnreadableException(
                "Vault key was invalidated (biometric enrollment changed or device reset). " +
                    "The vault cannot be recovered with this device's keystore.",
                e,
            )
        } catch (e: Exception) {
            // Tink wraps UserNotAuthenticatedException inside GeneralSecurityException.
            // Walk the cause chain so we don't mistake a lock for unreadable corruption.
            if (e.causedByUserNotAuthenticated()) {
                throw VaultLockedException("Vault locked: biometric/device-credential required")
            }
            if (e.causedByKeyInvalidated()) {
                throw VaultUnreadableException(
                    "Vault key was invalidated. Decryption is no longer possible with this device key.",
                    e,
                )
            }
            throw VaultUnreadableException("Vault file could not be decrypted: ${e.message}", e)
        }
    }

    override suspend fun write(plaintext: String) = withContext(Dispatchers.IO) {
        if (tmp.exists()) tmp.delete()
        try {
            encryptedFile(tmp).openFileOutput().use { it.write(plaintext.toByteArray(Charsets.UTF_8)) }
        } catch (e: UserNotAuthenticatedException) {
            tmp.delete()
            throw VaultLockedException("Vault locked: biometric/device-credential required")
        } catch (e: Exception) {
            tmp.delete()
            if (e.causedByUserNotAuthenticated()) {
                throw VaultLockedException("Vault locked: biometric/device-credential required")
            }
            throw e
        }
        Files.move(
            tmp.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
        Unit
    }

    /**
     * Best-effort removal of the auth-bound master key from the AndroidKeystore.
     * Called when the user switches away from [com.crdp.core.rdp.model.VaultProtection.DeviceKey]
     * so the (now-unused) hardware key doesn't linger.
     */
    fun deleteKey() {
        runCatching {
            KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(ALIAS)
        }
    }
}

private fun Throwable.causedByUserNotAuthenticated(): Boolean {
    var c: Throwable? = this
    while (c != null) {
        if (c is UserNotAuthenticatedException) return true
        c = c.cause
    }
    return false
}

private fun Throwable.causedByKeyInvalidated(): Boolean {
    var c: Throwable? = this
    while (c != null) {
        if (c is KeyPermanentlyInvalidatedException) return true
        c = c.cause
    }
    return false
}
