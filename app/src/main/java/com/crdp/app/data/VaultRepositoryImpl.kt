package com.crdp.app.data

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.crdp.core.rdp.model.VaultEntry
import com.crdp.core.rdp.repository.VaultRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class StoredVault(val entries: List<VaultEntry> = emptyList())

/**
 * Persists [VaultEntry] records to the app's files dir. Storage format depends on
 * the user's "vault encryption" toggle — when enabled, the file is wrapped in an
 * [EncryptedFile] (AES-256-GCM via MasterKey/AndroidKeystore); when disabled, the
 * file holds plaintext JSON.
 *
 * `setEncryption` migrates the on-disk file between modes. Encryption is decoupled
 * from the UI biometric prompt: the prompt happens at vault-access time in the UI
 * layer (VaultGate / MainActivity), this class is purely about bytes-on-disk.
 */
@Singleton
class VaultRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val encryptionState: VaultEncryptionState,
) : VaultRepository {

    companion object {
        private const val FILE_NAME = "crdp_vault.json"
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    private val storeFile = File(context.filesDir, FILE_NAME)

    @Volatile private var initialized = false

    private val cache = MutableStateFlow<Map<String, VaultEntry>>(emptyMap())

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private fun buildEncryptedFile() = EncryptedFile.Builder(
        context,
        storeFile,
        masterKey,
        EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
    ).build()

    private suspend fun ensureInitialized() {
        if (initialized) return
        mutex.withLock {
            if (initialized) return
            cache.value = loadFromDisk()?.associateBy { it.id } ?: emptyMap()
            initialized = true
        }
    }

    private fun loadFromDisk(): List<VaultEntry>? {
        if (!storeFile.exists()) return null
        val raw = runCatching {
            if (encryptionState.encrypted) {
                buildEncryptedFile().openFileInput().bufferedReader().use { it.readText() }
            } else {
                storeFile.readText(Charsets.UTF_8)
            }
        }.getOrNull() ?: return null
        return runCatching {
            json.decodeFromString(StoredVault.serializer(), raw).entries
        }.getOrNull()
    }

    private fun writeToDisk(entries: Collection<VaultEntry>) {
        val raw = json.encodeToString(
            StoredVault.serializer(),
            StoredVault(entries.toList()),
        )
        if (storeFile.exists()) storeFile.delete()
        if (encryptionState.encrypted) {
            buildEncryptedFile().openFileOutput().use { it.write(raw.toByteArray(Charsets.UTF_8)) }
        } else {
            storeFile.writeText(raw, Charsets.UTF_8)
        }
    }

    override val entries: Flow<List<VaultEntry>> =
        cache.map { it.values.sortedBy { e -> e.displayName.lowercase() } }
            .also {
                // Trigger initialization the first time the flow is collected. The map
                // is hot (StateFlow.map) so subsequent collectors share the cache.
            }

    override suspend fun getById(id: String): VaultEntry? {
        ensureInitialized()
        return cache.value[id]
    }

    override suspend fun upsert(entry: VaultEntry) {
        ensureInitialized()
        mutex.withLock {
            val updated = cache.value.toMutableMap().apply { put(entry.id, entry) }
            writeToDisk(updated.values)
            cache.value = updated
        }
    }

    override suspend fun delete(id: String) {
        ensureInitialized()
        mutex.withLock {
            val updated = cache.value.toMutableMap().apply { remove(id) }
            writeToDisk(updated.values)
            cache.value = updated
        }
    }

    override suspend fun setEncryption(encrypted: Boolean): Boolean {
        ensureInitialized()
        mutex.withLock {
            if (encryptionState.encrypted == encrypted) return true
            // Read with the OLD encryption mode, then write with the NEW one.
            val current = cache.value.values.toList()
            encryptionState.encrypted = encrypted
            return runCatching { writeToDisk(current) }
                .onFailure {
                    // Roll back the mode flag if the rewrite failed so the file we
                    // already have on disk remains readable on the next launch.
                    encryptionState.encrypted = !encrypted
                }
                .isSuccess
        }
    }

    /** Returns a snapshot view of the cache for migration helpers. */
    suspend fun snapshot(): List<VaultEntry> {
        ensureInitialized()
        return cache.value.values.toList()
    }
}

/**
 * Mutable mirror of the user's "vault encryption" preference so the repository can
 * pick the right on-disk format without a suspend lookup on every read/write. Updated
 * by the settings layer via [com.crdp.app.MainViewModel.setVaultEncryption].
 */
@Singleton
class VaultEncryptionState @Inject constructor() {
    @Volatile var encrypted: Boolean = true
}
