package com.crdp.core.rdp.repository

import com.crdp.core.rdp.model.SessionHistoryEntry
import kotlinx.coroutines.flow.Flow

interface SessionHistoryRepository {
    val entries: Flow<List<SessionHistoryEntry>>

    suspend fun append(entry: SessionHistoryEntry)

    suspend fun clear()
}
