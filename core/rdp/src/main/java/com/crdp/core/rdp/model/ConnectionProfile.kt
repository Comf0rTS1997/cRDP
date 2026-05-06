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
    val username: String = "",
    val domain: String? = null,
    val password: String = "",
    val width: Int = 1280,
    val height: Int = 720,
    val colorDepth: Int = 32,
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
    val audioQuality: AudioQuality = AudioQuality.UseAppDefault,
    val cameraMode: CameraMode = CameraMode.UseAppDefault,
    /** Concrete native device id when [cameraMode] == Specific; null otherwise. */
    val cameraDeviceId: String? = null,
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
    val audioQuality: AudioQuality = AudioQuality.UseAppDefault,
    val cameraMode: CameraMode = CameraMode.UseAppDefault,
    val cameraDeviceId: String? = null,
) : ConnectionProfile()
