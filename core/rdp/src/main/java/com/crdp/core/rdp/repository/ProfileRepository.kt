package com.crdp.core.rdp.repository

import com.crdp.core.rdp.model.ConnectionProfile
import kotlinx.coroutines.flow.Flow

interface ProfileRepository {
    val profiles: Flow<List<ConnectionProfile>>

    suspend fun getById(id: String): ConnectionProfile?

    /**
     * Persists a profile. If the profile carries a password, [requireUserAuth] controls whether
     * the AndroidKeystore key wrapping that password requires recent biometric authentication.
     * Callers should pass the current value of the user's "Require biometric to decrypt
     * credentials" preference so the on-disk security level matches the toggle.
     */
    suspend fun upsert(profile: ConnectionProfile, requireUserAuth: Boolean)

    suspend fun delete(id: String)

    /**
     * Re-encrypts all stored passwords under keys with the new [requireUserAuth] setting.
     * Must be called only when the current biometric auth window is still valid (so existing
     * keys with `requireUserAuth=true` can be decrypted before re-wrapping).
     * Returns false if any password could not be re-keyed (e.g. expired auth window).
     */
    suspend fun rekeyAllPasswords(requireUserAuth: Boolean): Boolean
}
