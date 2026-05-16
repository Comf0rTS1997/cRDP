package com.crdp.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.crdp.core.rdp.model.ConnectionProfile
import com.crdp.core.rdp.model.DirectConnectionProfile
import com.crdp.core.rdp.model.VaultEntry
import com.crdp.core.rdp.repository.ProfileRepository
import com.crdp.core.rdp.repository.VaultRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// Used only during the one-time migration; kept alive so DataStore doesn't complain about
// multiple instances, but never written to after migration completes.
private val Context.legacyProfileDataStore: DataStore<Preferences>
        by preferencesDataStore("crdp_profiles")

// Stores migration flags (v2 = encrypted profile blob; v3 = credentials extracted to vault).
private val Context.migrationPrefs: DataStore<Preferences>
        by preferencesDataStore("crdp_migration")

@Serializable
private data class StoredProfiles(val profiles: List<ConnectionProfile> = emptyList())

@Singleton
class ProfileRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vaultRepository: VaultRepository,
) : ProfileRepository {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val encryptedStore = EncryptedProfileStore(context)
    private val passwordCrypto = PasswordCryptoManager()

    private val profilesV2Key = booleanPreferencesKey("profiles_v2_encrypted")
    private val credentialsV3Key = booleanPreferencesKey("credentials_v3_in_vault")

    private val mutex = Mutex()
    @Volatile private var initialized = false

    /**
     * In-memory cache. Profiles here are STORAGE-form: any password the user typed
     * inline (legacy path) lives here as ciphertext under [PasswordCryptoManager],
     * and [DirectConnectionProfile.vaultEntryId] is the modern reference. Either of
     * those is resolved into plaintext credentials by [getById].
     */
    private val cache = MutableStateFlow<Map<String, ConnectionProfile>>(emptyMap())

    // ------------------------------------------------------------------
    // Initialization & migration
    // ------------------------------------------------------------------

    private suspend fun ensureInitialized() {
        if (initialized) return
        mutex.withLock {
            if (initialized) return
            val migPrefs = context.migrationPrefs.data.first()
            if (migPrefs[profilesV2Key] == true) {
                loadCache()
            } else {
                migrateFromLegacyDataStore()
            }
            if (migPrefs[credentialsV3Key] != true) {
                migrateInlineCredentialsToVault()
            }
            initialized = true
        }
    }

    /** v1 → v2: legacy plain DataStore → EncryptedFile JSON blob. Inline passwords wrapped in keystore key. */
    private suspend fun migrateFromLegacyDataStore() {
        val legacyPrefs = context.legacyProfileDataStore.data.first()
        val ids = legacyPrefs[stringSetPreferencesKey("profile_ids")] ?: emptySet()
        val migrated = ids.mapNotNull { id ->
            val raw = legacyPrefs[stringPreferencesKey("profile_$id")] ?: return@mapNotNull null
            runCatching { json.decodeFromString(ConnectionProfile.serializer(), raw) }.getOrNull()
        }.map { profile ->
            if (profile is DirectConnectionProfile && profile.password.isNotEmpty()) {
                val ct = passwordCrypto.encrypt(profile.id, profile.password)
                profile.copy(password = ct)
            } else {
                profile
            }
        }.associateBy { it.id }

        persistCache(migrated)
        deleteLegacyFile()
        context.migrationPrefs.edit { it[profilesV2Key] = true }
    }

    /**
     * v2 → v3: lift any inline credentials (username/password/domain on a
     * DirectConnectionProfile that does NOT yet reference a vault entry) into a new
     * [VaultEntry] and rewrite the profile to point at it. Idempotent — profiles
     * that already carry a vaultEntryId are left alone.
     */
    private suspend fun migrateInlineCredentialsToVault() {
        if (cache.value.isEmpty() && encryptedStore.readAll() == null) {
            context.migrationPrefs.edit { it[credentialsV3Key] = true }
            return
        }
        val updated = cache.value.toMutableMap()
        var changed = false
        for ((id, profile) in cache.value) {
            if (profile !is DirectConnectionProfile) continue
            if (profile.vaultEntryId != null) continue
            // Decrypt any inline ciphertext so we can lift the real plaintext.
            val plainPw = if (profile.password.isNotEmpty()) {
                passwordCrypto.decrypt(profile.id, profile.password) ?: ""
            } else ""
            val hasAnyCred = profile.username.isNotBlank() ||
                !profile.domain.isNullOrBlank() ||
                plainPw.isNotEmpty()
            if (!hasAnyCred) continue
            val entry = VaultEntry(
                id = UUID.randomUUID().toString(),
                displayName = buildString {
                    if (profile.username.isNotBlank()) append(profile.username)
                    if (!profile.domain.isNullOrBlank()) {
                        if (isNotEmpty()) append('@')
                        append(profile.domain)
                    }
                    if (isEmpty()) append(profile.displayName)
                },
                username = profile.username,
                password = plainPw,
                domain = profile.domain,
            )
            vaultRepository.upsert(entry)
            // Delete the no-longer-used per-profile keystore key.
            passwordCrypto.deleteKey(profile.id)
            updated[id] = profile.copy(
                vaultEntryId = entry.id,
                username = "",
                domain = null,
                password = "",
            )
            changed = true
        }
        if (changed) persistCache(updated)
        context.migrationPrefs.edit { it[credentialsV3Key] = true }
    }

    private fun deleteLegacyFile() {
        File(File(context.filesDir, "datastore"), "crdp_profiles.preferences_pb").delete()
    }

    private suspend fun loadCache() {
        val raw = encryptedStore.readAll() ?: return
        val stored = runCatching {
            json.decodeFromString(StoredProfiles.serializer(), raw)
        }.getOrNull() ?: return
        cache.value = stored.profiles.associateBy { it.id }
    }

    private suspend fun persistCache(map: Map<String, ConnectionProfile>) {
        val raw = json.encodeToString(StoredProfiles.serializer(), StoredProfiles(map.values.toList()))
        encryptedStore.writeAll(raw)
        cache.value = map
    }

    // ------------------------------------------------------------------
    // ProfileRepository
    // ------------------------------------------------------------------

    /**
     * Emits the storage-form profile list. The list view doesn't need credentials,
     * so we skip the vault lookup; callers that need credentials use [getById].
     */
    override val profiles: Flow<List<ConnectionProfile>> = flow {
        ensureInitialized()
        emitAll(cache.map { it.values.sortedBy { p -> p.displayName.lowercase() } })
    }

    /**
     * Returns the profile with credentials resolved: when a vault entry is referenced,
     * its username/domain/password are merged in; otherwise legacy ciphertext is decrypted.
     */
    override suspend fun getById(id: String): ConnectionProfile? {
        ensureInitialized()
        val profile = cache.value[id] ?: return null
        return resolveCredentials(profile)
    }

    override suspend fun upsert(profile: ConnectionProfile) {
        ensureInitialized()
        mutex.withLock {
            val stored = storageForm(profile)
            val updated = cache.value.toMutableMap().apply { put(profile.id, stored) }
            persistCache(updated)
        }
    }

    override suspend fun delete(id: String) {
        ensureInitialized()
        mutex.withLock {
            passwordCrypto.deleteKey(id)
            val updated = cache.value.toMutableMap().apply { remove(id) }
            persistCache(updated)
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Strip plaintext credentials before storage when a vault entry handles them.
     * The vault is the source of truth — leaving copies in the profile blob would
     * mean two places to keep in sync on every credential change.
     */
    private fun storageForm(profile: ConnectionProfile): ConnectionProfile {
        if (profile !is DirectConnectionProfile) return profile
        if (profile.vaultEntryId != null) {
            return profile.copy(username = "", domain = null, password = "")
        }
        // No vault reference — keep legacy inline encryption working for now so
        // existing data isn't lost mid-rollout. Any future "save" call through the
        // editor will pass a vaultEntryId, so this branch is only hit by legacy code.
        if (profile.password.isEmpty()) return profile
        val ct = runCatching {
            passwordCrypto.encrypt(profile.id, profile.password)
        }.getOrNull() ?: return profile.copy(password = "")
        return profile.copy(password = ct)
    }

    private suspend fun resolveCredentials(profile: ConnectionProfile): ConnectionProfile {
        if (profile !is DirectConnectionProfile) return profile
        val entryId = profile.vaultEntryId
        if (entryId != null) {
            val entry = vaultRepository.getById(entryId) ?: return profile.copy(
                username = "",
                domain = null,
                password = "",
            )
            return profile.copy(
                username = entry.username,
                domain = entry.domain,
                password = entry.password,
            )
        }
        // Legacy inline path: decrypt under the per-profile keystore key.
        if (profile.password.isEmpty()) return profile
        val plain = passwordCrypto.decrypt(profile.id, profile.password) ?: ""
        return profile.copy(password = plain)
    }
}
