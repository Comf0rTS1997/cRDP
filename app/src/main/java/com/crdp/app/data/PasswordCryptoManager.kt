package com.crdp.app.data

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts and decrypts per-profile passwords using AndroidKeystore AES-256-GCM keys.
 *
 * Each profile gets its own key (`crdp_pw_<profileId>`). When [requireUserAuth] is true the key
 * is bound to biometric authentication with a 30-second validity window, so decryption silently
 * fails (returns null) if the user has not authenticated recently enough — the caller is expected
 * to surface a re-auth prompt or fall back to manual password entry.
 *
 * Key invalidation (user removes biometrics): [KeyPermanentlyInvalidatedException] is caught,
 * the stale key is deleted, and null is returned so the repository can trigger recovery.
 */
internal class PasswordCryptoManager {

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_PREFIX = "crdp_pw_"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }

    private fun alias(profileId: String) = "$KEY_PREFIX$profileId"

    private fun getOrCreateKey(profileId: String, requireUserAuth: Boolean): SecretKey {
        val ks = keyStore()
        val a = alias(profileId)
        ks.getKey(a, null)?.let { return it as SecretKey }

        val spec = KeyGenParameterSpec.Builder(
            a,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        ).apply {
            setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            setKeySize(256)
            if (requireUserAuth) {
                setUserAuthenticationRequired(true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(30, KeyProperties.AUTH_BIOMETRIC_STRONG)
                } else {
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(30)
                }
            }
        }.build()

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(spec)
            generateKey()
        }
    }

    /** Returns base64-encoded "ivB64:ciphertextB64". */
    fun encrypt(profileId: String, plaintext: String, requireUserAuth: Boolean): String {
        val key = getOrCreateKey(profileId, requireUserAuth)
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key) }
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val ivB64 = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val ctB64 = Base64.encodeToString(ct, Base64.NO_WRAP)
        return "$ivB64:$ctB64"
    }

    /**
     * Returns the decrypted password, or null if the key is missing, invalidated, or the
     * biometric authentication window has expired.
     */
    fun decrypt(profileId: String, encoded: String): String? = runCatching {
        val ks = keyStore()
        val key = ks.getKey(alias(profileId), null) as? SecretKey ?: return null
        val parts = encoded.split(":")
        if (parts.size != 2) return null
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ct = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        String(cipher.doFinal(ct), Charsets.UTF_8)
    }.recover { e ->
        if (e is KeyPermanentlyInvalidatedException) deleteKey(profileId)
        null
    }.getOrNull()

    fun deleteKey(profileId: String) {
        runCatching { keyStore().deleteEntry(alias(profileId)) }
    }

    /**
     * Re-encrypts a password under a key with updated [newRequireUserAuth].
     * Returns null if decryption of [encoded] fails (e.g. expired auth window).
     */
    fun rekey(profileId: String, encoded: String, newRequireUserAuth: Boolean): String? {
        val plaintext = decrypt(profileId, encoded) ?: return null
        deleteKey(profileId)
        return encrypt(profileId, plaintext, newRequireUserAuth)
    }

    private fun keyStore() = KeyStore.getInstance(KEYSTORE).apply { load(null) }
}
