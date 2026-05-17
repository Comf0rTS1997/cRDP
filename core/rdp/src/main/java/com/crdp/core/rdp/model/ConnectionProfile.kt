package com.crdp.core.rdp.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class ConnectionProfile {
    abstract val id: String
    abstract val displayName: String
}

@Serializable
enum class AudioMode { UseAppDefault, LocalDevice, RemoteConsole, Disabled }

@Serializable
enum class AudioQuality { UseAppDefault, Dynamic, Medium, High }

@Serializable
enum class CameraMode { UseAppDefault, Disabled, BuiltInFront, BuiltInBack, External, Specific }

@Serializable
@SerialName("direct")
data class DirectConnectionProfile(
    override val id: String,
    override val displayName: String,
    val host: String,
    val port: Int = 3389,
    /**
     * Reference to a [VaultEntry] in the credential vault. When non-null and the
     * entry exists, [username]/[domain]/[password] are populated from it at load
     * time; saving back to the repo nulls out the inline fields. Kept nullable
     * for the legacy inline-credentials path used during migration.
     */
    val vaultEntryId: String? = null,
    val username: String = "",
    val domain: String? = null,
    val password: String = "",
    val width: Int = 1280,
    val height: Int = 720,
    val colorDepth: Int = 32,
    /** Legacy per-profile biometric flag. Replaced by global [vaultEncryption]; kept for migration. */
    val requireBiometric: Boolean = false,
    val autoResolution: Boolean = false,
    /**
     * Server-side desktop scale factor as percent (100..500).
     * 0 means "use the app default" (resolved at connect time, with DeX-mode override).
     */
    val desktopScaleFactor: Int = 0,
    val audioMode: AudioMode = AudioMode.UseAppDefault,
    /** null = use app default; true/false = explicit override. */
    val microphoneOverride: Boolean? = null,
    /** null = use app default; sync plain text with the remote clipboard (direct RDP only). */
    val clipboardSyncOverride: Boolean? = null,
    val audioQuality: AudioQuality = AudioQuality.UseAppDefault,
    val cameraMode: CameraMode = CameraMode.UseAppDefault,
    /** Concrete native device id when [cameraMode] == Specific; null otherwise. */
    val cameraDeviceId: String? = null,
    /** null = use app default; expose a virtual printer to the remote session. */
    val printerShareOverride: Boolean? = null,
    /**
     * Windows keyboard layout id (e.g. 0x0409). 0 = use app default (resolved
     * from settings at connect time, mirroring how audio/camera defaults are
     * resolved). No per-profile editor surface today; the app default is the
     * only producer.
     */
    val keyboardLayoutId: Int = 0,
) : ConnectionProfile()

@Serializable
@SerialName("gateway")
data class GatewayConnectionProfile(
    override val id: String,
    override val displayName: String,
    val gatewayBaseUrl: String,
    val targetHost: String,
    val targetPort: Int = 3389,
    val bearerToken: String? = null,
    val width: Int = 1280,
    val height: Int = 720,
    val requireBiometric: Boolean = false,
    val autoResolution: Boolean = false,
    val desktopScaleFactor: Int = 0,
    val audioMode: AudioMode = AudioMode.UseAppDefault,
    val microphoneOverride: Boolean? = null,
    val clipboardSyncOverride: Boolean? = null,
    val audioQuality: AudioQuality = AudioQuality.UseAppDefault,
    val cameraMode: CameraMode = CameraMode.UseAppDefault,
    val cameraDeviceId: String? = null,
    val printerShareOverride: Boolean? = null,
    /** See [DirectConnectionProfile.keyboardLayoutId]. */
    val keyboardLayoutId: Int = 0,
) : ConnectionProfile()
