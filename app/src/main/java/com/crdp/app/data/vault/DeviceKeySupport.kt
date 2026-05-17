package com.crdp.app.data.vault

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import java.security.KeyStore
import javax.crypto.KeyGenerator

/**
 * Runtime probe for "can this device actually host an auth-bound Keystore key?".
 *
 * Two things need to be true:
 *  1. [BiometricManager] reports that either strong biometrics OR a device
 *     credential (PIN/pattern/password) is set up. Without one of those, the
 *     OS refuses to create a key with `setUserAuthenticationRequired(true)`.
 *  2. We can actually generate such a key. Some OEM keystores have rejected
 *     auth-bound key creation even when (1) reports SUCCESS (e.g. older Huawei
 *     firmware). Doing a one-time test generation catches those.
 *
 * The check is conservative — false positives (claiming "supported" when it
 * isn't) would land the user in a broken DeviceKey setup, so we test for real.
 */
object DeviceKeySupport {

    private const val PROBE_ALIAS = "crdp_vault_devicekey_probe"
    private const val KEYSTORE = "AndroidKeyStore"

    /**
     * Cheap check that an auth method capable of releasing an auth-bound key
     * is configured on the device. Use this for UX hints ("device key
     * unavailable, use password instead") without triggering a full Keystore
     * round-trip.
     */
    fun authConfigured(context: Context): Boolean {
        val bm = BiometricManager.from(context)
        return bm.canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    /**
     * Best-effort end-to-end check: actually try to generate an auth-bound
     * key, then delete it. Returns true only if the keystore accepts the
     * configuration we'd use at production time. Performs disk I/O so it
     * should be called off the main thread.
     */
    fun canHostAuthBoundKey(context: Context): Boolean {
        if (!authConfigured(context)) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        return runCatching {
            val spec = KeyGenParameterSpec.Builder(
                PROBE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        setUserAuthenticationParameters(
                            DeviceKeyVaultCrypto.DEFAULT_VALIDITY_SECONDS,
                            KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        setUserAuthenticationValidityDurationSeconds(
                            DeviceKeyVaultCrypto.DEFAULT_VALIDITY_SECONDS,
                        )
                    }
                }
                .build()
            val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            kg.init(spec)
            kg.generateKey()
            // Clean up immediately; the probe key is single-use.
            runCatching {
                KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(PROBE_ALIAS)
            }
            true
        }.getOrDefault(false)
    }
}
