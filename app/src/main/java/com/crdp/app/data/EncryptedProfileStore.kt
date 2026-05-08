package com.crdp.app.data

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Persists the profile JSON blob in an AES-256-GCM encrypted file backed by the AndroidKeystore.
 * The MasterKey has no user-authentication requirement — biometric gating is handled at the UI
 * layer (VaultGate) and, optionally, at the per-password key layer (PasswordCryptoManager).
 */
internal class EncryptedProfileStore(context: Context) {

    private val appContext = context.applicationContext

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val storeFile = File(appContext.filesDir, "crdp_profiles_enc.json")

    private val mutex = Mutex()

    suspend fun readAll(): String? = mutex.withLock {
        if (!storeFile.exists()) return@withLock null
        runCatching {
            buildEncryptedFile().openFileInput().use { it.bufferedReader().readText() }
        }.getOrNull()
    }

    suspend fun writeAll(json: String): Unit = mutex.withLock {
        // EncryptedFile cannot overwrite; delete and re-create.
        if (storeFile.exists()) storeFile.delete()
        buildEncryptedFile().openFileOutput().use { out ->
            out.write(json.toByteArray(Charsets.UTF_8))
        }
    }

    private fun buildEncryptedFile() = EncryptedFile.Builder(
        appContext,
        storeFile,
        masterKey,
        EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
    ).build()
}
