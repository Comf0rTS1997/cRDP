package com.crdp.app.data

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Persists the profile JSON blob in an AES-256-GCM encrypted file backed by the AndroidKeystore.
 * Writes go through a `.tmp` companion + atomic rename so a kill mid-write cannot leave a
 * half-truncated profile list. The MasterKey has no user-authentication requirement — the
 * biometric prompt at the UI layer (VaultGate) is the user-visible gate.
 */
internal class EncryptedProfileStore(context: Context) {

    private val appContext = context.applicationContext

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val storeFile = File(appContext.filesDir, "crdp_profiles_enc.json")
    private val tmpFile = File(appContext.filesDir, "crdp_profiles_enc.json.tmp")

    private val mutex = Mutex()

    private companion object {
        // Per-store Tink keyset slot. Tink stores the data-encryption key in a
        // SharedPreferences slot identified by (prefName, alias). The defaults
        // are shared across every EncryptedFile in the app, so two EncryptedFile
        // instances built with different MasterKey aliases would stomp each
        // other and crash on the second-to-init's first read/write with
        // AEADBadTagException — the persisted DEK was wrapped by the OTHER
        // master key. Scope these names to this store.
        const val KEYSET_PREF_NAME = "__crdp_profile_store_keyset_pref__"
        const val KEYSET_ALIAS = "__crdp_profile_store_keyset__"
    }

    suspend fun readAll(): String? = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!storeFile.exists()) return@withContext null
            runCatching {
                buildEncryptedFile(storeFile).openFileInput().use { it.bufferedReader().readText() }
            }.getOrNull()
        }
    }

    suspend fun writeAll(json: String): Unit = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (tmpFile.exists()) tmpFile.delete()
            buildEncryptedFile(tmpFile).openFileOutput().use { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
            }
            Files.move(
                tmpFile.toPath(),
                storeFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }
    }

    private fun buildEncryptedFile(target: File) = EncryptedFile.Builder(
        appContext,
        target,
        masterKey,
        EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
    )
        .setKeysetPrefName(KEYSET_PREF_NAME)
        .setKeysetAlias(KEYSET_ALIAS)
        .build()
}
