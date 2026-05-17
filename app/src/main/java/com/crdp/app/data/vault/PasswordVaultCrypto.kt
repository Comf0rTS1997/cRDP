package com.crdp.app.data.vault

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Vault encrypted with AES-256-GCM under a key derived from a user-supplied
 * passphrase via PBKDF2-HMAC-SHA256. Designed as a fallback for devices where
 * an auth-bound Keystore key is not available (no screen lock, no biometrics,
 * or hardware that rejects auth-bound key creation).
 *
 * File layout (single file, no companion):
 * ```
 *   [8 bytes ] magic               "CRDPPW01"
 *   [1 byte  ] kdf algorithm id    0 = PBKDF2WithHmacSHA256
 *   [4 bytes ] kdf iterations      big-endian int
 *   [1 byte  ] salt length         currently 32
 *   [N bytes ] salt
 *   [1 byte  ] iv length           currently 12
 *   [N bytes ] iv                  AES-GCM nonce
 *   [4 bytes ] ciphertext length   big-endian int, includes 16-byte GCM tag
 *   [N bytes ] ciphertext || tag
 * ```
 * Iterations and lengths are stored alongside the data so future tuning (e.g.
 * stronger KDF, longer salt) doesn't break existing files.
 *
 * The derived key is held in memory by the caller (typically the repository)
 * and zeroed when the vault is locked. It is NOT persisted anywhere.
 */
internal class PasswordVaultCrypto(
    private val target: File,
    private val tmp: File,
    /**
     * Provides the active derived key. Returns null when the vault is locked
     * (no recent password unlock). Surfacing this as a getter rather than a
     * constructor argument lets a single repository instance survive across
     * lock/unlock cycles without rebuilding the crypto strategy.
     */
    private val keyProvider: () -> ByteArray?,
) : VaultCrypto {

    companion object {
        private const val KDF_PBKDF2_SHA256: Byte = 0
        /** Per OWASP 2023 PBKDF2-HMAC-SHA256 minimum. */
        const val DEFAULT_ITERATIONS = 600_000
        const val SALT_LEN = 32
        const val IV_LEN = 12
        const val KEY_LEN = 32 // AES-256
        const val GCM_TAG_BITS = 128

        /**
         * Derives a 256-bit key from [password] using PBKDF2-HMAC-SHA256.
         * Caller owns the returned byte array and is responsible for zeroing
         * it when no longer needed.
         */
        fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
            val spec = PBEKeySpec(password, salt, iterations, KEY_LEN * 8)
            return try {
                val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                factory.generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        }

        fun newSalt(): ByteArray = ByteArray(SALT_LEN).also { SecureRandom().nextBytes(it) }
        fun newIv(): ByteArray = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }

        /**
         * Reads the salt + iterations from an existing password-protected vault
         * file so the unlock UI can derive the right key without first opening
         * the whole file. Returns null when the file is missing or doesn't
         * carry the password magic.
         */
        fun readKdfParams(target: File): KdfParams? {
            if (!target.exists()) return null
            target.inputStream().use { fis ->
                val magic = ByteArray(PASSWORD_MAGIC.size)
                if (fis.read(magic) != magic.size) return null
                if (!magic.contentEquals(PASSWORD_MAGIC)) return null
                val dis = DataInputStream(fis)
                val alg = dis.readByte()
                if (alg != KDF_PBKDF2_SHA256) return null
                val iterations = dis.readInt()
                val saltLen = dis.readUnsignedByte()
                val salt = ByteArray(saltLen)
                dis.readFully(salt)
                return KdfParams(alg = alg, iterations = iterations, salt = salt)
            }
        }
    }

    data class KdfParams(val alg: Byte, val iterations: Int, val salt: ByteArray)

    override suspend fun read(): String? = withContext(Dispatchers.IO) {
        if (!target.exists()) return@withContext null
        val key = keyProvider() ?: throw VaultLockedException("Vault locked: password required")
        target.inputStream().use { fis ->
            val magic = ByteArray(PASSWORD_MAGIC.size)
            if (fis.read(magic) != magic.size || !magic.contentEquals(PASSWORD_MAGIC)) {
                throw VaultUnreadableException("Vault file is not in password format")
            }
            val dis = DataInputStream(fis)
            val alg = dis.readByte()
            if (alg != KDF_PBKDF2_SHA256) {
                throw VaultUnreadableException("Unsupported KDF algorithm: $alg")
            }
            dis.readInt() // iterations — informational on read
            val saltLen = dis.readUnsignedByte()
            dis.skipBytes(saltLen)
            val ivLen = dis.readUnsignedByte()
            val iv = ByteArray(ivLen)
            dis.readFully(iv)
            val ctLen = dis.readInt()
            val ct = ByteArray(ctLen)
            dis.readFully(ct)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES") as SecretKey,
                GCMParameterSpec(GCM_TAG_BITS, iv),
            )
            try {
                String(cipher.doFinal(ct), Charsets.UTF_8)
            } catch (e: AEADBadTagException) {
                throw VaultWrongPasswordException()
            }
        }
    }

    override suspend fun write(plaintext: String) = withContext(Dispatchers.IO) {
        val key = keyProvider() ?: throw VaultLockedException("Vault locked: password required")
        // Reuse existing salt + iterations so the user's existing unlocked key keeps working,
        // unless this is a fresh file (no existing params) — in that case mint new ones.
        val existing = readKdfParams(target)
        val salt = existing?.salt ?: newSalt()
        val iterations = existing?.iterations ?: DEFAULT_ITERATIONS

        val iv = newIv()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES") as SecretKey,
            GCMParameterSpec(GCM_TAG_BITS, iv),
        )
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        if (tmp.exists()) tmp.delete()
        DataOutputStream(tmp.outputStream()).use { dos ->
            dos.write(PASSWORD_MAGIC)
            dos.writeByte(KDF_PBKDF2_SHA256.toInt())
            dos.writeInt(iterations)
            dos.writeByte(salt.size)
            dos.write(salt)
            dos.writeByte(iv.size)
            dos.write(iv)
            dos.writeInt(ct.size)
            dos.write(ct)
        }
        Files.move(
            tmp.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
        Unit
    }
}
