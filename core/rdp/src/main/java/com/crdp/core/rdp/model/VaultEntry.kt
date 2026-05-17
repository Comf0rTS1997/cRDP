package com.crdp.core.rdp.model

import kotlinx.serialization.Serializable

/**
 * A single credential record in the user's credential vault. Decoupled from
 * [ConnectionProfile] so the same identity can be reused across multiple connections.
 *
 * The on-disk representation is governed by the app-wide
 * [com.crdp.core.rdp.model.VaultProtection] setting (None / DeviceKey / Password)
 * — see [com.crdp.app.prefs.AppSettings.vaultProtection].
 */
@Serializable
data class VaultEntry(
    val id: String,
    val displayName: String,
    val username: String,
    val password: String,
    val domain: String? = null,
)
