package com.crdp.core.rdp.repository

import com.crdp.core.rdp.model.ConnectionProfile
import kotlinx.coroutines.flow.Flow

interface ProfileRepository {
    val profiles: Flow<List<ConnectionProfile>>

    suspend fun getById(id: String): ConnectionProfile?

    /**
     * Persists a profile. Passwords are always encrypted under a per-profile AndroidKeystore
     * AES-256-GCM key; the biometric gate is enforced at the UI layer (VaultGate + per-profile
     * biometric prompt + global "require biometric to decrypt" toggle).
     */
    suspend fun upsert(profile: ConnectionProfile)

    suspend fun delete(id: String)
}
