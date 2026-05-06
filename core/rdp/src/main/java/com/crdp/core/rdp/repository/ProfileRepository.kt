package com.crdp.core.rdp.repository

import com.crdp.core.rdp.model.ConnectionProfile
import kotlinx.coroutines.flow.Flow

interface ProfileRepository {
    val profiles: Flow<List<ConnectionProfile>>

    suspend fun getById(id: String): ConnectionProfile?

    suspend fun upsert(profile: ConnectionProfile)

    suspend fun delete(id: String)
}
