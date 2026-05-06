package com.crdp.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.crdp.core.rdp.model.SessionHistoryEntry
import com.crdp.core.rdp.repository.SessionHistoryRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.sessionHistoryDataStore: DataStore<Preferences> by preferencesDataStore("crdp_session_history")

@Singleton
class SessionHistoryRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : SessionHistoryRepository {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val listSerializer = ListSerializer(SessionHistoryEntry.serializer())
    private val entriesKey = stringPreferencesKey("session_entries_json")

    override val entries: Flow<List<SessionHistoryEntry>> =
        context.sessionHistoryDataStore.data.map { prefs ->
            prefs[entriesKey]?.let { raw ->
                runCatching { json.decodeFromString(listSerializer, raw) }.getOrNull()
            } ?: emptyList()
        }

    override suspend fun append(entry: SessionHistoryEntry) {
        context.sessionHistoryDataStore.edit { prefs ->
            val current = prefs[entriesKey]?.let { raw ->
                runCatching { json.decodeFromString(listSerializer, raw) }.getOrNull()
            } ?: emptyList()
            val next = listOf(entry) + current
            val capped = next.take(MAX_ENTRIES)
            prefs[entriesKey] = json.encodeToString(listSerializer, capped)
        }
    }

    override suspend fun clear() {
        context.sessionHistoryDataStore.edit { prefs ->
            prefs.remove(entriesKey)
        }
    }

    companion object {
        private const val MAX_ENTRIES = 100
    }
}
