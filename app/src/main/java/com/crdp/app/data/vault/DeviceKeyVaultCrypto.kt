package com.crdp.app.data.vault

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import android.util.Log
import androidx.security.crypto.MasterKey
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore

/**
 * Vault stored as a Tink AES-256-GCM-HKDF-4KB stream wrapped by an
 * AndroidKeystore-resident master key that requires recent user authentication
 * (biometric strong / device credential). The key is created on first use; any
 * decrypt/encrypt outside the [validitySeconds] window after a successful
 * auth raises [UserNotAuthenticatedException], which surfaces here as
 * [VaultLockedException] so the UI can prompt and retry.
 *
 * Talks to Tink directly rather than going through [androidx.security.crypto.EncryptedFile]
 * because EncryptedFile bakes `mFile.getName()` into the AEAD's Associated Data on both
 * encrypt and decrypt. Combined with the temp-file + atomic-rename pattern used here for
 * crash safety, that meant ciphertext was sealed with AD `"crdp_vault.json.tmp"` at write
 * time but verified with AD `"crdp_vault.json"` at read time — every cold-start read failed
 * with Tink's "No matching key found for the ciphertext in the stream". Using the Tink
 * primitive directly lets us pin AD to [target]'s name regardless of where the bytes
 * physically lived during the write.
 *
 * Files written by the buggy EncryptedFile-based version are still recoverable: on a primary
 * decrypt failure [read] retries with the legacy AD and, if successful, immediately rewrites
 * the file under the correct AD.
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
        private const val TAG = "VaultCrypto"
        // Per-store Tink keyset slot. The default slot is shared across every
        // EncryptedFile/AndroidKeysetManager built in the process, so two stores
        // wrapping different master keys would stomp each other.
        private const val KEYSET_PREF_NAME = "__crdp_vault_keyset_pref__"
        private const val KEYSET_ALIAS = "__crdp_vault_keyset__"
        private const val KEY_TEMPLATE = "AES256_GCM_HKDF_4KB"
    }

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(appContext, ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .setUserAuthenticationRequired(true, validitySeconds)
            .build()
    }

    private val streamingAead: StreamingAead by lazy {
        // Tink's StreamingAead registry is not auto-populated when using the Tink
        // primitives directly (EncryptedFile registers it internally). Register
        // here so KeyTemplates.get(AES256_GCM_HKDF_4KB) resolves.
        StreamingAeadConfig.register()
        // Touch masterKey first so the auth-bound Keystore entry exists before
        // AndroidKeysetManager tries to unwrap the keyset with it.
        masterKey
        val handle = AndroidKeysetManager.Builder()
            .withKeyTemplate(KeyTemplates.get(KEY_TEMPLATE))
            .withSharedPref(appContext, KEYSET_ALIAS, KEYSET_PREF_NAME)
            .withMasterKeyUri("android-keystore://$ALIAS")
            .build()
            .keysetHandle
        handle.getPrimitive(StreamingAead::class.java)
    }

    private fun adFor(name: String): ByteArray = name.toByteArray(Charsets.UTF_8)

    override suspend fun read(): String? = withContext(Dispatchers.IO) {
        if (!target.exists()) return@withContext null
        val ciphertext = target.readBytes()
        try {
            decryptBytes(ciphertext, adFor(target.name))
        } catch (e: UserNotAuthenticatedException) {
            throw VaultLockedException("Vault locked: biometric/device-credential required")
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw VaultUnreadableException(
                "Vault key was invalidated (biometric enrollment changed or device reset). " +
                    "The vault cannot be recovered with this device's keystore.",
                e,
            )
        } catch (e: Exception) {
            if (e.causedByUserNotAuthenticated()) {
                throw VaultLockedException("Vault locked: biometric/device-credential required")
            }
            if (e.causedByKeyInvalidated()) {
                throw VaultUnreadableException(
                    "Vault key was invalidated. Decryption is no longer possible with this device key.",
                    e,
                )
            }
            // Legacy fallback: data written by the prior EncryptedFile-based code used the tmp
            // filename as AD. Try that once; if it succeeds, rewrite the file under the correct
            // AD so the fallback isn't needed on the next read.
            val recovered = try {
                decryptBytes(ciphertext, adFor(tmp.name))
            } catch (_: Exception) {
                throw VaultUnreadableException("Vault file could not be decrypted: ${e.message}", e)
            }
            Log.w(TAG, "recovered with legacy AD; rewriting under correct AD")
            writeInternal(recovered)
            recovered
        }
    }

    override suspend fun write(plaintext: String) = withContext(Dispatchers.IO) {
        writeInternal(plaintext)
    }

    private fun writeInternal(plaintext: String) {
        val ad = adFor(target.name)
        val baos = ByteArrayOutputStream()
        try {
            streamingAead.newEncryptingStream(baos, ad).use { os ->
                os.write(plaintext.toByteArray(Charsets.UTF_8))
            }
        } catch (e: UserNotAuthenticatedException) {
            throw VaultLockedException("Vault locked: biometric/device-credential required")
        } catch (e: Exception) {
            if (e.causedByUserNotAuthenticated()) {
                throw VaultLockedException("Vault locked: biometric/device-credential required")
            }
            throw e
        }
        if (tmp.exists()) tmp.delete()
        tmp.writeBytes(baos.toByteArray())
        Files.move(
            tmp.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }

    private fun decryptBytes(ciphertext: ByteArray, ad: ByteArray): String =
        streamingAead.newDecryptingStream(ByteArrayInputStream(ciphertext), ad).use { stream ->
            stream.bufferedReader().readText()
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
