package com.crdp.app.data

import android.content.Context
import com.crdp.core.rdp.model.ConnectionProfile
import com.crdp.core.rdp.model.DirectConnectionProfile
import com.crdp.core.rdp.repository.ProfileRepository
import com.crdp.core.rdp.repository.VaultRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class StoredProfiles(val profiles: List<ConnectionProfile> = emptyList())

@Singleton
class ProfileRepositoryImpl @Inject constructor(
    @ApplicationContext context: Context,
    private val vaultRepository: VaultRepository,
) : ProfileRepository {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val encryptedStore = EncryptedProfileStore(context)

    private val mutex = Mutex()
    @Volatile private var initialized = false

    private val cache = MutableStateFlow<Map<String, ConnectionProfile>>(emptyMap())

    private suspend fun ensureInitialized() {
        if (initialized) return
        mutex.withLock {
            if (initialized) return
            cache.value = loadFromDisk()
            initialized = true
        }
    }

    private suspend fun loadFromDisk(): Map<String, ConnectionProfile> {
        val raw = encryptedStore.readAll() ?: return emptyMap()
        val stored = runCatching {
            json.decodeFromString(StoredProfiles.serializer(), raw)
        }.getOrNull() ?: return emptyMap()
        return stored.profiles.associateBy { it.id }
    }

    private suspend fun persistCache(map: Map<String, ConnectionProfile>) {
        val raw = json.encodeToString(StoredProfiles.serializer(), StoredProfiles(map.values.toList()))
        encryptedStore.writeAll(raw)
        cache.value = map
    }

    override val profiles: Flow<List<ConnectionProfile>> = flow {
        ensureInitialized()
        emitAll(cache.map { it.values.sortedBy { p -> p.displayName.lowercase() } })
    }

    /**
     * Returns the profile with credentials resolved from the vault when referenced.
     * Profiles never store inline credentials — the vault is the only source of truth.
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
            val updated = cache.value.toMutableMap().apply { remove(id) }
            persistCache(updated)
        }
    }

    /**
     * Strip any plaintext credentials before persisting — the vault is the canonical
     * store, so the profile blob should only carry the [DirectConnectionProfile.vaultEntryId]
     * reference.
     */
    private fun storageForm(profile: ConnectionProfile): ConnectionProfile {
        if (profile !is DirectConnectionProfile) return profile
        return profile.copy(username = "", domain = null, password = "")
    }

    private suspend fun resolveCredentials(profile: ConnectionProfile): ConnectionProfile {
        if (profile !is DirectConnectionProfile) return profile
        val entryId = profile.vaultEntryId ?: return profile
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
}
