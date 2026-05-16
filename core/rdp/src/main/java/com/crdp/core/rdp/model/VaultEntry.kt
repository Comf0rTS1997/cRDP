package com.crdp.core.rdp.model

import kotlinx.serialization.Serializable

/**
 * A single credential record in the user's credential vault. Decoupled from
 * [ConnectionProfile] so the same identity can be reused across multiple connections.
 *
 * The on-disk representation is governed by the app-wide "vault encryption" toggle:
 * encrypted (MasterKey-backed EncryptedFile + UI biometric gate) or plaintext (no
 * gate, no encryption). See [com.crdp.app.prefs.AppSettings.vaultEncryption].
 */
@Serializable
data class VaultEntry(
    val id: String,
    val displayName: String,
    val username: String,
    val password: String,
    val domain: String? = null,
)
