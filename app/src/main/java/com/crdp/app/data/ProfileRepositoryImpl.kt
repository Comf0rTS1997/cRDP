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
import com.crdp.core.rdp.repository.ProfileRepository
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
import javax.inject.Inject
import javax.inject.Singleton

// Used only during the one-time migration; kept alive so DataStore doesn't complain about
// multiple instances, but never written to after migration completes.
private val Context.legacyProfileDataStore: DataStore<Preferences>
        by preferencesDataStore("crdp_profiles")

// Stores a single boolean flag that records whether the v2 migration has run.
private val Context.migrationPrefs: DataStore<Preferences>
        by preferencesDataStore("crdp_migration")

@Serializable
private data class StoredProfiles(val profiles: List<ConnectionProfile> = emptyList())

@Singleton
class ProfileRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : ProfileRepository {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val encryptedStore = EncryptedProfileStore(context)
    private val passwordCrypto = PasswordCryptoManager()

    private val migrationDoneKey = booleanPreferencesKey("profiles_v2_encrypted")

    private val mutex = Mutex()
    @Volatile private var initialized = false

    // In-memory cache. Values have passwords stored as ciphertext (or empty string).
    private val cache = MutableStateFlow<Map<String, ConnectionProfile>>(emptyMap())

    // ------------------------------------------------------------------
    // Initialization & migration
    // ------------------------------------------------------------------

    private suspend fun ensureInitialized() {
        if (initialized) return
        mutex.withLock {
            if (initialized) return
            val migPrefs = context.migrationPrefs.data.first()
            if (migPrefs[migrationDoneKey] == true) {
                loadCache()
            } else {
                migrateFromLegacyDataStore()
            }
            initialized = true
        }
    }

    /** One-time migration: reads the old plain DataStore, re-encrypts passwords, persists to EncryptedFile. */
    private suspend fun migrateFromLegacyDataStore() {
        val legacyPrefs = context.legacyProfileDataStore.data.first()
        val ids = legacyPrefs[stringSetPreferencesKey("profile_ids")] ?: emptySet()
        val migrated = ids.mapNotNull { id ->
            val raw = legacyPrefs[stringPreferencesKey("profile_$id")] ?: return@mapNotNull null
            runCatching { json.decodeFromString(ConnectionProfile.serializer(), raw) }.getOrNull()
        }.map { profile ->
            // Passwords were plaintext in the old store — wrap them in a Keystore key now.
            if (profile is DirectConnectionProfile && profile.password.isNotEmpty()) {
                val ct = runCatching {
                    passwordCrypto.encrypt(profile.id, profile.password, requireUserAuth = false)
                }.getOrDefault(profile.password)
                profile.copy(password = ct)
            } else {
                profile
            }
        }.associateBy { it.id }

        persistCache(migrated)
        deleteLegacyFile()
        context.migrationPrefs.edit { it[migrationDoneKey] = true }
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

    /** Emits profiles sorted by display name. Passwords are ciphertext; use [getById] for plaintext. */
    override val profiles: Flow<List<ConnectionProfile>> = flow {
        ensureInitialized()
        emitAll(cache.map { it.values.sortedBy { p -> p.displayName.lowercase() } })
    }

    /**
     * Returns the profile with its password decrypted. Returns null if the profile doesn't exist.
     * If the password key requires recent biometric authentication and the window has elapsed,
     * the returned profile will have an empty password — the UI should prompt the user to
     * re-authenticate or enter the password manually.
     */
    override suspend fun getById(id: String): ConnectionProfile? {
        ensureInitialized()
        val profile = cache.value[id] ?: return null
        return decryptPassword(profile)
    }

    /**
     * Saves a profile, encrypting its password under the profile's AndroidKeystore key.
     * [requireUserAuth] controls whether that key requires a recent biometric unlock;
     * pass the current value of [com.crdp.app.prefs.AppSettings.requireBiometricToDecrypt].
     */
    override suspend fun upsert(profile: ConnectionProfile, requireUserAuth: Boolean) {
        ensureInitialized()
        mutex.withLock {
            val stored = encryptPassword(profile, requireUserAuth)
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

    /**
     * Re-keys all stored passwords when the [requireBiometricToDecrypt] preference changes.
     * Must be called only when the current biometric auth window is still valid (so that
     * existing keys with [requireUserAuth=true] can be decrypted before re-wrapping).
     * Returns false if any password could not be re-keyed (auth window expired).
     */
    override suspend fun rekeyAllPasswords(requireUserAuth: Boolean): Boolean {
        ensureInitialized()
        var allOk = true
        mutex.withLock {
            val updated = cache.value.toMutableMap()
            for ((id, profile) in updated) {
                if (profile !is DirectConnectionProfile || profile.password.isEmpty()) continue
                val newCt = passwordCrypto.rekey(id, profile.password, requireUserAuth)
                if (newCt != null) {
                    updated[id] = profile.copy(password = newCt)
                } else {
                    allOk = false
                }
            }
            persistCache(updated)
        }
        return allOk
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun encryptPassword(profile: ConnectionProfile, requireUserAuth: Boolean): ConnectionProfile {
        if (profile !is DirectConnectionProfile || profile.password.isEmpty()) return profile
        val ct = runCatching {
            passwordCrypto.encrypt(profile.id, profile.password, requireUserAuth)
        }.getOrDefault(profile.password)
        return profile.copy(password = ct)
    }

    private fun decryptPassword(profile: ConnectionProfile): ConnectionProfile {
        if (profile !is DirectConnectionProfile || profile.password.isEmpty()) return profile
        val plain = passwordCrypto.decrypt(profile.id, profile.password) ?: ""
        return profile.copy(password = plain)
    }
}
