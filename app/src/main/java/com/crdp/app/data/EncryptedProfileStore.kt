package com.crdp.app.data

import android.content.Context
import android.util.Log
import androidx.security.crypto.MasterKey
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Persists the profile JSON blob in an AES-256-GCM-HKDF stream wrapped by an
 * AndroidKeystore-resident master key. No user-authentication requirement — the
 * biometric prompt at the UI layer (VaultGate) is the user-visible gate for
 * credentials; profile metadata itself isn't credential material.
 *
 * Uses Tink directly rather than [androidx.security.crypto.EncryptedFile] for the
 * reasons described in [com.crdp.app.data.vault.DeviceKeyVaultCrypto]: EncryptedFile
 * uses `mFile.getName()` as the AEAD Associated Data, which is incompatible with the
 * tmp-file + atomic-rename pattern. Files written by the prior EncryptedFile-based
 * version are recovered on read via a one-shot legacy-AD fallback and rewritten.
 */
internal class EncryptedProfileStore(context: Context) {

    private val appContext = context.applicationContext

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val streamingAead: StreamingAead by lazy {
        StreamingAeadConfig.register()
        // Touch masterKey so the Keystore entry exists before AndroidKeysetManager looks it up.
        masterKey
        val handle = AndroidKeysetManager.Builder()
            .withKeyTemplate(KeyTemplates.get(KEY_TEMPLATE))
            .withSharedPref(appContext, KEYSET_ALIAS, KEYSET_PREF_NAME)
            .withMasterKeyUri("android-keystore://" + MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .build()
            .keysetHandle
        handle.getPrimitive(StreamingAead::class.java)
    }

    private val storeFile = File(appContext.filesDir, FILE_NAME)
    private val tmpFile = File(appContext.filesDir, "$FILE_NAME.tmp")

    private val mutex = Mutex()

    private companion object {
        const val TAG = "ProfileStore"
        const val FILE_NAME = "crdp_profiles_enc.json"
        const val KEYSET_PREF_NAME = "__crdp_profile_store_keyset_pref__"
        const val KEYSET_ALIAS = "__crdp_profile_store_keyset__"
        const val KEY_TEMPLATE = "AES256_GCM_HKDF_4KB"
    }

    suspend fun readAll(): String? = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!storeFile.exists()) return@withContext null
            val ciphertext = storeFile.readBytes()
            try {
                decryptBytes(ciphertext, adFor(storeFile.name))
            } catch (_: Exception) {
                // Legacy fallback: the prior EncryptedFile-based code sealed ciphertext under
                // the tmp filename as AD. Recover and rewrite under the correct AD.
                val recovered = try {
                    decryptBytes(ciphertext, adFor(tmpFile.name))
                } catch (_: Exception) {
                    return@withContext null
                }
                Log.w(TAG, "recovered with legacy AD; rewriting under correct AD")
                writeInternal(recovered)
                recovered
            }
        }
    }

    suspend fun writeAll(json: String): Unit = mutex.withLock {
        withContext(Dispatchers.IO) { writeInternal(json) }
    }

    private fun writeInternal(json: String) {
        val ad = adFor(storeFile.name)
        val baos = ByteArrayOutputStream()
        streamingAead.newEncryptingStream(baos, ad).use { os ->
            os.write(json.toByteArray(Charsets.UTF_8))
        }
        if (tmpFile.exists()) tmpFile.delete()
        tmpFile.writeBytes(baos.toByteArray())
        Files.move(
            tmpFile.toPath(),
            storeFile.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }

    private fun decryptBytes(ciphertext: ByteArray, ad: ByteArray): String =
        streamingAead.newDecryptingStream(ByteArrayInputStream(ciphertext), ad).use { stream ->
            stream.bufferedReader().readText()
        }

    private fun adFor(name: String): ByteArray = name.toByteArray(Charsets.UTF_8)
}
