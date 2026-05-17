package com.crdp.core.rdp.repository

import com.crdp.core.rdp.model.VaultEntry
import com.crdp.core.rdp.model.VaultProtection
import kotlinx.coroutines.flow.Flow

/**
 * CRUD store for credential records. The vault is the single source of truth
 * for username/password/domain; [com.crdp.core.rdp.model.DirectConnectionProfile.vaultEntryId]
 * is the only credential reference connections hold.
 *
 * The vault has two orthogonal axes of state:
 *  - [VaultProtection]: how the file is protected at rest. Persisted across
 *    runs. Changing it triggers a re-encryption migration via [setProtection].
 *  - [VaultStatus]: whether the active protection's unlock material is
 *    currently available in this process. Transient; clears on [lock] or
 *    process restart. For [VaultProtection.None] always reports `Unlocked`.
 */
interface VaultRepository {

    val entries: Flow<List<VaultEntry>>

    /**
     * Combined runtime state — current protection + whether we can currently
     * read/write. Drives UI gating (biometric prompt, password input).
     */
    val status: Flow<VaultStatus>

    suspend fun getById(id: String): VaultEntry?

    /** Upsert; fails with [VaultOpResult.Locked] if the vault isn't currently unlocked. */
    suspend fun upsert(entry: VaultEntry): VaultOpResult

    suspend fun delete(id: String): VaultOpResult

    /**
     * Attempts to read the vault under the current protection. For
     * [VaultProtection.DeviceKey] this requires a recent successful biometric
     * prompt (the prompt itself happens in the UI layer via BiometricPrompter).
     * Returns true when the vault is now usable.
     */
    suspend fun tryUnlockWithDeviceKey(): UnlockOutcome

    /**
     * Derives a key from [password] and tries to decrypt the vault. Returns
     * [UnlockOutcome.WrongPassword] when the file exists but the password
     * doesn't decrypt it. The supplied char array is zeroed after use.
     */
    suspend fun tryUnlockWithPassword(password: CharArray): UnlockOutcome

    /**
     * Drops any cached unlock material (password-derived key, in-memory
     * decrypted cache). Subsequent reads/writes require re-unlock.
     */
    suspend fun lock()

    /**
     * Changes the on-disk protection. Requires the vault to be currently
     * unlocked (so we can read existing entries before re-encrypting).
     *
     * [newPassword] is mandatory when [target] is [VaultProtection.Password]
     * and ignored otherwise. The supplied char array is zeroed after use.
     */
    suspend fun setProtection(
        target: VaultProtection,
        newPassword: CharArray? = null,
    ): SetProtectionOutcome
}

/**
 * Runtime state of the vault. The repository emits this so the UI can react
 * without polling.
 */
data class VaultStatus(
    val protection: VaultProtection,
    val unlocked: Boolean,
    /** True when an existing file exists on disk under the active protection. */
    val hasData: Boolean,
    /** Non-null when a previous read failed unrecoverably — UI should warn. */
    val error: String? = null,
)

enum class UnlockOutcome {
    Success,
    WrongPassword,
    NotConfigured,
    Failed,
}

enum class VaultOpResult {
    Success,
    Locked,
    Failed,
}

enum class SetProtectionOutcome {
    Success,
    NeedsUnlock,
    NeedsPassword,
    Failed,
}
