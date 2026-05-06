package com.crdp.engine.afreerdp

/**
 * JNI bridge for the FreeRDP "androidbridge" rdpecam HAL subsystem.
 *
 * The native HAL has two subsystems: an NDK Camera2-driven one (handles the device's
 * built-in cameras directly in C) and this bridge — a HAL whose frames come in over
 * JNI from a Kotlin capture source. The bridge path exists so we can drive USB UVC
 * cameras via libuvc/UVCCamera (Java), since USB device permission must be granted
 * from a Java context and userspace UVC support is required on phones whose OEM
 * doesn't expose USB cameras through Camera2 (LENS_FACING_EXTERNAL).
 *
 * All entry points are no-ops if the rdpecam channel isn't currently active.
 */
object NativeCameraBridge {
    init {
        // FreeRDP's add_channel_client_subsystem_library macro emits the rdpecam
        // Android HAL as an OBJECT library, so its JNI symbols end up inside
        // libfreerdp-client3.so (not a separate librdpecam-client-android.so).
        // Loading freerdp-android pulls in the dependency chain via NEEDED entries,
        // but be defensive in case load order matters.
        runCatching { System.loadLibrary("freerdp-client3") }
        runCatching { System.loadLibrary("freerdp-android") }
    }

    /**
     * Announce a virtual UVC device to the native HAL so it appears in rdpecam
     * device enumeration. Call before [pushFrame].
     *
     * @return 0 on success, non-zero on failure.
     */
    external fun registerDevice(deviceId: String, label: String, width: Int, height: Int, fps: Int): Int

    /** Tear down a previously registered device; safe to call even if not registered. */
    external fun unregisterDevice(deviceId: String): Int

    /**
     * Push one captured frame upstream. Frame must be NV12 (Y plane followed by
     * interleaved UV) when [isEncoded] = false, or one H.264 NAL/access unit when
     * [isEncoded] = true. The native side copies the byte array into a channel
     * buffer; the caller may reuse the array immediately on return.
     *
     * @return 0 if accepted, -1 if the device isn't streaming right now (drop and
     *         keep capturing), -2 on fatal error.
     */
    external fun pushFrame(
        deviceId: String,
        frame: ByteArray,
        offset: Int,
        length: Int,
        timestampUs: Long,
        isKeyframe: Boolean,
        isEncoded: Boolean,
    ): Int
}
