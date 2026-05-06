package com.crdp.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.crdp.core.rdp.model.ConnectionProfile
import com.crdp.core.rdp.repository.ProfileRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.profileDataStore: DataStore<Preferences> by preferencesDataStore("crdp_profiles")

@Singleton
class ProfileRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : ProfileRepository {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val idsKey = stringSetPreferencesKey("profile_ids")
    private fun profileKey(id: String) = stringPreferencesKey("profile_$id")

    override val profiles: Flow<List<ConnectionProfile>> =
        context.profileDataStore.data.map { prefs ->
            val ids = prefs[idsKey] ?: emptySet()
            ids.mapNotNull { id ->
                prefs[profileKey(id)]?.let { raw ->
                    runCatching {
                        json.decodeFromString(ConnectionProfile.serializer(), raw)
                    }.getOrNull()
                }
            }.sortedBy { it.displayName.lowercase() }
        }

    override suspend fun getById(id: String): ConnectionProfile? {
        val prefs = context.profileDataStore.data.first()
        return prefs[profileKey(id)]?.let { raw ->
            runCatching { json.decodeFromString(ConnectionProfile.serializer(), raw) }.getOrNull()
        }
    }

    override suspend fun upsert(profile: ConnectionProfile) {
        context.profileDataStore.edit { prefs ->
            val next = (prefs[idsKey] ?: emptySet()).toMutableSet()
            next.add(profile.id)
            prefs[idsKey] = next
            prefs[profileKey(profile.id)] = json.encodeToString(ConnectionProfile.serializer(), profile)
        }
    }

    override suspend fun delete(id: String) {
        context.profileDataStore.edit { prefs ->
            val next = (prefs[idsKey] ?: emptySet()).toMutableSet()
            next.remove(id)
            prefs[idsKey] = next
            prefs.remove(profileKey(id))
        }
    }
}
