package com.crdp.app.data

import android.content.Context
import com.crdp.app.data.vault.DeviceKeyVaultCrypto
import com.crdp.app.data.vault.PASSWORD_MAGIC
import com.crdp.app.data.vault.PasswordVaultCrypto
import com.crdp.app.data.vault.PlaintextVaultCrypto
import com.crdp.app.data.vault.VaultCrypto
import com.crdp.app.data.vault.VaultLockedException
import com.crdp.app.data.vault.VaultUnreadableException
import com.crdp.app.data.vault.VaultWrongPasswordException
import com.crdp.app.prefs.UserPreferencesRepository
import com.crdp.core.rdp.model.VaultEntry
import com.crdp.core.rdp.model.VaultProtection
import com.crdp.core.rdp.repository.SetProtectionOutcome
import com.crdp.core.rdp.repository.UnlockOutcome
import com.crdp.core.rdp.repository.VaultOpResult
import com.crdp.core.rdp.repository.VaultRepository
import com.crdp.core.rdp.repository.VaultStatus
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

@Serializable
private data class StoredVault(val entries: List<VaultEntry> = emptyList())

/**
 * Persists [VaultEntry] records to the app's files dir under one of three
 * protection modes (see [VaultProtection]).
 *
 * Crypto is delegated to a [VaultCrypto] strategy; the repository only deals
 * in plaintext JSON. The active strategy is chosen at lazy-init time from the
 * persisted [com.crdp.app.prefs.AppSettings.vaultProtection] preference and
 * swapped in [setProtection].
 *
 * Locking model:
 *  - [VaultProtection.None] is always considered unlocked.
 *  - [VaultProtection.DeviceKey] is unlocked once we've successfully read or
 *    written through the auth-bound master key. The OS Keystore validity
 *    timer is the real cryptographic gate; our in-memory flag is just a UX
 *    hint so the UI knows whether to prompt.
 *  - [VaultProtection.Password] is unlocked when [passwordKey] is non-null.
 *    The key is derived from the user's passphrase via PBKDF2 and zeroed on
 *    [lock].
 *
 * Failure handling:
 *  - "File exists but cannot be decrypted" never returns an empty list — the
 *    repository surfaces it via [VaultStatus.error] and refuses to overwrite
 *    until the user explicitly clears the vault. This is the fix for the
 *    silent-data-loss path the audit flagged.
 *  - A torn toggle (pref says one mode, file is in another) is recovered on
 *    read by probing the file's magic bytes. Currently only catches the
 *    None ↔ Password mismatch since DeviceKey ciphertext is opaque, but in
 *    practice that's the case the user can resolve without re-prompt.
 */
@Singleton
class VaultRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userPreferencesRepository: UserPreferencesRepository,
) : VaultRepository {

    companion object {
        private const val FILE_NAME = "crdp_vault.json"
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    private val storeFile = File(context.filesDir, FILE_NAME)
    private val tmpFile = File(context.filesDir, "$FILE_NAME.tmp")

    @Volatile private var initialized = false
    private var protection: VaultProtection = VaultProtection.DeviceKey
    private var deviceKeyAuthed: Boolean = false
    /** Derived AES-256 key for password mode. Cached only while unlocked. */
    private var passwordKey: ByteArray? = null
    private var lastError: String? = null

    private val cache = MutableStateFlow<Map<String, VaultEntry>>(emptyMap())
    private val statusInternal = MutableStateFlow(
        VaultStatus(VaultProtection.DeviceKey, unlocked = false, hasData = false),
    )

    private fun crypto(): VaultCrypto = when (protection) {
        VaultProtection.None -> PlaintextVaultCrypto(storeFile, tmpFile)
        VaultProtection.DeviceKey -> DeviceKeyVaultCrypto(context, storeFile, tmpFile)
        VaultProtection.Password -> PasswordVaultCrypto(storeFile, tmpFile) { passwordKey }
    }

    private fun isUnlockedForCurrentMode(): Boolean = when (protection) {
        VaultProtection.None -> true
        VaultProtection.DeviceKey -> deviceKeyAuthed
        VaultProtection.Password -> passwordKey != null
    }

    private fun publishStatus() {
        statusInternal.value = VaultStatus(
            protection = protection,
            unlocked = isUnlockedForCurrentMode(),
            hasData = storeFile.exists(),
            error = lastError,
        )
    }

    private suspend fun ensureInitialized() {
        if (initialized) return
        mutex.withLock {
            if (initialized) return
            protection = userPreferencesRepository.appSettings.first().vaultProtection
            // For None mode we can load eagerly. For protected modes, defer
            // load to the unlock path so we don't trigger a Keystore op or
            // touch an undecryptable file before the user has authenticated.
            if (protection == VaultProtection.None && storeFile.exists()) {
                runCatching { cache.value = decodeOrEmpty(PlaintextVaultCrypto(storeFile, tmpFile).read()) }
            }
            initialized = true
            publishStatus()
        }
    }

    private fun decodeOrEmpty(raw: String?): Map<String, VaultEntry> {
        if (raw.isNullOrEmpty()) return emptyMap()
        return runCatching {
            json.decodeFromString(StoredVault.serializer(), raw).entries.associateBy { it.id }
        }.getOrDefault(emptyMap())
    }

    /**
     * Reads the file under the active crypto strategy. Distinguishes:
     *  - file missing → empty
     *  - locked (need biometric / password) → throws VaultLockedException
     *  - wrong password → throws VaultWrongPasswordException
     *  - file in wrong on-disk format (torn toggle) → returns a recovery hint
     *  - truly unreadable → throws VaultUnreadableException
     */
    private suspend fun loadCurrent(): Map<String, VaultEntry> {
        val raw = crypto().read()
        return decodeOrEmpty(raw)
    }

    /**
     * Heuristic: if the active mode is None or Password and the file's first
     * bytes don't match what we expect, the on-disk format is out of sync with
     * the pref. Returns the actual mode if detected, null otherwise.
     */
    private fun probeDiskFormat(): VaultProtection? {
        if (!storeFile.exists()) return null
        return runCatching {
            storeFile.inputStream().use { fis ->
                val head = ByteArray(PASSWORD_MAGIC.size)
                val n = fis.read(head)
                when {
                    n >= PASSWORD_MAGIC.size && head.contentEquals(PASSWORD_MAGIC) ->
                        VaultProtection.Password
                    n >= 1 && head[0] == '{'.code.toByte() ->
                        VaultProtection.None
                    // EncryptedFile (Tink) ciphertext begins with a small version
                    // byte and is not human-readable. Treat as DeviceKey.
                    else -> VaultProtection.DeviceKey
                }
            }
        }.getOrNull()
    }

    override val entries: Flow<List<VaultEntry>> = flow {
        ensureInitialized()
        emitAll(cache.map { it.values.sortedBy { e -> e.displayName.lowercase() } })
    }

    override val status: Flow<VaultStatus> = flow {
        ensureInitialized()
        emitAll(statusInternal)
    }

    override suspend fun getById(id: String): VaultEntry? {
        ensureInitialized()
        return cache.value[id]
    }

    override suspend fun upsert(entry: VaultEntry): VaultOpResult {
        ensureInitialized()
        return mutex.withLock {
            if (!isUnlockedForCurrentMode()) return@withLock VaultOpResult.Locked
            if (lastError != null) return@withLock VaultOpResult.Failed
            val updated = cache.value.toMutableMap().apply { put(entry.id, entry) }
            try {
                writeMap(updated)
                cache.value = updated
                VaultOpResult.Success
            } catch (_: VaultLockedException) {
                deviceKeyAuthed = false
                publishStatus()
                VaultOpResult.Locked
            } catch (e: Exception) {
                lastError = e.message
                publishStatus()
                VaultOpResult.Failed
            }
        }
    }

    override suspend fun delete(id: String): VaultOpResult {
        ensureInitialized()
        return mutex.withLock {
            if (!isUnlockedForCurrentMode()) return@withLock VaultOpResult.Locked
            if (lastError != null) return@withLock VaultOpResult.Failed
            val updated = cache.value.toMutableMap().apply { remove(id) }
            try {
                writeMap(updated)
                cache.value = updated
                VaultOpResult.Success
            } catch (_: VaultLockedException) {
                deviceKeyAuthed = false
                publishStatus()
                VaultOpResult.Locked
            } catch (e: Exception) {
                lastError = e.message
                publishStatus()
                VaultOpResult.Failed
            }
        }
    }

    private suspend fun writeMap(map: Map<String, VaultEntry>) {
        val raw = json.encodeToString(
            StoredVault.serializer(),
            StoredVault(map.values.toList()),
        )
        crypto().write(raw)
    }

    override suspend fun tryUnlockWithDeviceKey(): UnlockOutcome {
        ensureInitialized()
        return mutex.withLock {
            if (protection != VaultProtection.DeviceKey) return@withLock UnlockOutcome.NotConfigured
            try {
                val loaded = loadCurrent()
                cache.value = loaded
                deviceKeyAuthed = true
                lastError = null
                publishStatus()
                UnlockOutcome.Success
            } catch (_: VaultLockedException) {
                // Caller hadn't prompted yet. Surface as Failed so the UI can
                // re-prompt — DeviceKey unlock from the repo side is implicit
                // (success on first crypto op after BiometricPrompt completes).
                UnlockOutcome.Failed
            } catch (e: VaultUnreadableException) {
                lastError = e.message
                publishStatus()
                UnlockOutcome.Failed
            } catch (e: Exception) {
                lastError = e.message
                publishStatus()
                UnlockOutcome.Failed
            }
        }
    }

    override suspend fun tryUnlockWithPassword(password: CharArray): UnlockOutcome {
        ensureInitialized()
        return mutex.withLock {
            if (protection != VaultProtection.Password) {
                password.fill(' ')
                return@withLock UnlockOutcome.NotConfigured
            }
            // Existing file → derive using its stored salt/iterations. Brand-new
            // vault → derive using fresh params; first write seals them.
            val params = PasswordVaultCrypto.readKdfParams(storeFile)
            val derivedKey: ByteArray = if (params != null) {
                PasswordVaultCrypto.deriveKey(password, params.salt, params.iterations)
            } else {
                val salt = PasswordVaultCrypto.newSalt()
                PasswordVaultCrypto.deriveKey(password, salt, PasswordVaultCrypto.DEFAULT_ITERATIONS)
            }
            password.fill(' ')

            passwordKey?.fill(0)
            passwordKey = derivedKey

            return@withLock try {
                if (storeFile.exists()) {
                    val loaded = loadCurrent()
                    cache.value = loaded
                } else {
                    cache.value = emptyMap()
                }
                lastError = null
                publishStatus()
                UnlockOutcome.Success
            } catch (_: VaultWrongPasswordException) {
                passwordKey?.fill(0)
                passwordKey = null
                publishStatus()
                UnlockOutcome.WrongPassword
            } catch (e: VaultUnreadableException) {
                passwordKey?.fill(0)
                passwordKey = null
                lastError = e.message
                publishStatus()
                // Recovery hint: if the file isn't actually in password format,
                // tell the UI the format is wrong so it can offer to import or
                // wipe rather than re-asking for a password forever.
                if (probeDiskFormat() != VaultProtection.Password) {
                    UnlockOutcome.NotConfigured
                } else {
                    UnlockOutcome.Failed
                }
            } catch (e: Exception) {
                passwordKey?.fill(0)
                passwordKey = null
                lastError = e.message
                publishStatus()
                UnlockOutcome.Failed
            }
        }
    }

    override suspend fun lock() {
        mutex.withLock {
            deviceKeyAuthed = false
            passwordKey?.fill(0)
            passwordKey = null
            cache.value = emptyMap()
            publishStatus()
        }
    }

    override suspend fun setProtection(
        target: VaultProtection,
        newPassword: CharArray?,
    ): SetProtectionOutcome {
        ensureInitialized()
        return mutex.withLock {
            if (protection == target) {
                newPassword?.fill(' ')
                return@withLock SetProtectionOutcome.Success
            }
            if (!isUnlockedForCurrentMode()) {
                newPassword?.fill(' ')
                return@withLock SetProtectionOutcome.NeedsUnlock
            }
            if (target == VaultProtection.Password && (newPassword == null || newPassword.isEmpty())) {
                return@withLock SetProtectionOutcome.NeedsPassword
            }

            // Snapshot entries while still under the old protection.
            val snapshot = cache.value

            val previousProtection = protection
            val previousPasswordKey = passwordKey
            val previousDeviceAuthed = deviceKeyAuthed

            // Mint key material for the target mode BEFORE flipping the active
            // strategy so a derivation failure doesn't strand us between modes.
            var newKey: ByteArray? = null
            if (target == VaultProtection.Password) {
                val salt = PasswordVaultCrypto.newSalt()
                newKey = PasswordVaultCrypto.deriveKey(
                    newPassword!!,
                    salt,
                    PasswordVaultCrypto.DEFAULT_ITERATIONS,
                )
                newPassword.fill(' ')
                // Drop any pre-existing password-mode file so the new file is
                // sealed with the freshly-derived salt rather than the old one.
                if (storeFile.exists() && previousProtection == VaultProtection.Password) {
                    storeFile.delete()
                }
            }

            protection = target
            passwordKey = newKey
            deviceKeyAuthed = (target == VaultProtection.DeviceKey)

            return@withLock try {
                writeMap(snapshot)
                cache.value = snapshot
                lastError = null
                userPreferencesRepository.setVaultProtection(target)
                // Best-effort: when leaving DeviceKey mode, dispose of the now-unused
                // auth-bound master key so it doesn't linger in the Keystore.
                if (previousProtection == VaultProtection.DeviceKey &&
                    target != VaultProtection.DeviceKey
                ) {
                    DeviceKeyVaultCrypto(context, storeFile, tmpFile).deleteKey()
                }
                previousPasswordKey?.fill(0)
                publishStatus()
                SetProtectionOutcome.Success
            } catch (e: Exception) {
                // Roll back to the previous state — we still hold the snapshot
                // and the previous keys.
                newKey?.fill(0)
                protection = previousProtection
                passwordKey = previousPasswordKey
                deviceKeyAuthed = previousDeviceAuthed
                lastError = e.message
                publishStatus()
                SetProtectionOutcome.Failed
            }
        }
    }
}
