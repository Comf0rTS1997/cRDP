package com.crdp.core.rdp.repository

import com.crdp.core.rdp.model.VaultEntry
import kotlinx.coroutines.flow.Flow

/**
 * CRUD store for credential records. The vault is the single source of truth for
 * username/password/domain; [com.crdp.core.rdp.model.DirectConnectionProfile.vaultEntryId]
 * is the only credential reference connections hold.
 *
 * Encryption-on-disk follows the user's "vault encryption" toggle; switching the
 * toggle re-serialises the same content in the other mode via [setEncryption].
 */
interface VaultRepository {
    val entries: Flow<List<VaultEntry>>

    suspend fun getById(id: String): VaultEntry?

    suspend fun upsert(entry: VaultEntry)

    suspend fun delete(id: String)

    /**
     * Re-persists every entry in the new encryption mode. No-op if the mode is
     * unchanged. Returns true on success, false if the on-disk file could not be
     * migrated (e.g. read failed under the previous mode).
     */
    suspend fun setEncryption(encrypted: Boolean): Boolean
}
