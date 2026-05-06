package com.crdp.core.rdp.engine

/** Already-resolved audio playback mode. The "use app default" indirection lives in the profile layer. */
enum class AudioPlayback { LocalDevice, RemoteConsole, Disabled }

enum class AudioQuality { Dynamic, Medium, High }

/**
 * Already-resolved camera-redirection target. The "use app default" indirection lives in the
 * profile layer; by the time params reach the engine, the choice is concrete.
 */
enum class CameraRedirect { Disabled, BuiltInFront, BuiltInBack, External, Specific }

data class RdpConnectParams(
    val host: String,
    val port: Int = 3389,
    val username: String = "",
    val domain: String? = null,
    val password: String = "",
    val width: Int = 1280,
    val height: Int = 720,
    val colorDepth: Int = 32,
    /** Server-side desktop scale factor as percent, 100..500. 100 = no scaling. */
    val desktopScaleFactor: Int = 100,
    val audioPlayback: AudioPlayback = AudioPlayback.LocalDevice,
    val microphoneEnabled: Boolean = false,
    val audioQuality: AudioQuality = AudioQuality.Dynamic,
    val cameraRedirect: CameraRedirect = CameraRedirect.Disabled,
    /**
     * Native rdpecam device id when [cameraRedirect] == Specific. For built-in/external the HAL
     * resolves the actual device, so this is null. Format mirrors what the Android HAL emits
     * from its Enumerate callback (e.g. "front", "back", "external:0", "usb:1234:5678").
     */
    val cameraDeviceId: String? = null,
    /** true = H.264 passthrough via MediaCodec; false = raw frames (server transcodes). */
    val cameraEncode: Boolean = true,
)
