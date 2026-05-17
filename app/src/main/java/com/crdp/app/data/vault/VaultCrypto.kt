package com.crdp.app.data.vault

/**
 * Per-mode encryption strategy for the credential vault. Implementations are
 * responsible for the full at-rest representation — file format, key handling,
 * IV/salt generation, and authentication. The repository sits above this and
 * only sees plaintext JSON.
 *
 * Errors are split into three categories:
 *  - [VaultLockedException]: the unlock material isn't available yet (no recent
 *    biometric, or no password supplied). Caller should prompt the user.
 *  - [VaultWrongPasswordException]: a password was supplied but it didn't
 *    decrypt the file. Caller should re-prompt.
 *  - [VaultUnreadableException]: the file exists but cannot be decrypted with
 *    any expected key material — likely corruption, OEM keystore reset, or
 *    on-disk format mismatch. Refuse to overwrite; surface to the user.
 *
 * Returning null from [read] always means "file does not exist". A file that
 * exists but won't decrypt MUST throw — never silently return empty, or the
 * next upsert overwrites real data with whatever the UI thinks is there.
 */
internal interface VaultCrypto {
    /** Reads and decrypts the vault. Returns null when the backing file does not exist. */
    suspend fun read(): String?

    /** Encrypts and atomically writes the vault. */
    suspend fun write(plaintext: String)
}

internal class VaultLockedException(message: String) : Exception(message)
internal class VaultWrongPasswordException(message: String = "Wrong password") : Exception(message)
internal class VaultUnreadableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Magic prefix identifying a [PasswordVaultCrypto] file on disk. Lets us
 * distinguish password-encrypted vs device-key-encrypted vs plaintext content
 * without consulting the prefs (which can be out of sync with the actual file
 * after a crash between disk write and pref commit).
 */
internal val PASSWORD_MAGIC = byteArrayOf(
    'C'.code.toByte(), 'R'.code.toByte(), 'D'.code.toByte(), 'P'.code.toByte(),
    'P'.code.toByte(), 'W'.code.toByte(), '0'.code.toByte(), '1'.code.toByte(),
)
