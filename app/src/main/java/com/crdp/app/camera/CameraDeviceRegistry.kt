package com.crdp.app.camera

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stable identity of a camera the user can pick. The [id] is what gets persisted in
 * the connection profile and threaded through to FreeRDP's rdpecam channel as the
 * device argument. The native HAL parses this format:
 *
 * - "front" / "back" — built-in cameras the OS reports via Camera2 LENS_FACING.
 * - "external:<camera2-id>" — a USB camera the OS exposes via Camera2 LENS_FACING_EXTERNAL.
 *   The HAL opens it directly through Camera2 NDK.
 * - "usb:<vid>:<pid>" — a UVC USB device that's NOT visible through Camera2 (older OEMs
 *   without UVC kernel support). Capture goes through the JNI bridge from a libuvc-based
 *   Kotlin source; the native HAL receives frames over [com.crdp.engine.afreerdp.NativeCameraBridge].
 */
data class CameraIdentity(
    val id: String,
    val displayName: String,
    val source: Source,
) {
    enum class Source { BuiltInFront, BuiltInBack, External, UvcOnly }
}

@Singleton
class CameraDeviceRegistry @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Enumerate every camera the user can select right now. Order:
     *   1. Built-in front, built-in back (most-common picks first)
     *   2. External cameras visible via Camera2 (preferred path — kernel/OEM cooperated)
     *   3. UVC devices the system exposes via UsbManager but Camera2 doesn't list (fallback)
     */
    fun list(): List<CameraIdentity> {
        val out = mutableListOf<CameraIdentity>()

        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        val camera2Usb = mutableListOf<String>()
        if (cm != null) {
            for (camId in cm.cameraIdList) {
                val ch = runCatching { cm.getCameraCharacteristics(camId) }.getOrNull() ?: continue
                when (ch[CameraCharacteristics.LENS_FACING]) {
                    CameraCharacteristics.LENS_FACING_FRONT ->
                        out += CameraIdentity("front", "Front camera", CameraIdentity.Source.BuiltInFront)
                    CameraCharacteristics.LENS_FACING_BACK ->
                        out += CameraIdentity("back", "Back camera", CameraIdentity.Source.BuiltInBack)
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> {
                        out += CameraIdentity(
                            id = "external:$camId",
                            displayName = "External camera ($camId)",
                            source = CameraIdentity.Source.External,
                        )
                        camera2Usb += camId
                    }
                }
            }
        }

        // UVC fallback: scan for connected USB video-class devices that Camera2 didn't surface.
        // The vid:pid form is what the libuvc backend will key on if/when it's wired up.
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
            val um = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            if (um != null) {
                for (dev in um.deviceList.values) {
                    if (!dev.isVideoClass()) continue
                    val key = "usb:${dev.vendorId}:${dev.productId}"
                    if (out.any { it.id == key }) continue
                    // Skip if Camera2 already exposed this device — heuristic: when Camera2 has
                    // any external entry and no other USB video device is connected, assume the
                    // single external entry is this one.
                    if (camera2Usb.size == 1 && um.deviceList.values.count { it.isVideoClass() } == 1) continue
                    out += CameraIdentity(
                        id = key,
                        displayName = dev.productName?.takeIf { it.isNotBlank() } ?: "USB camera ${dev.deviceName}",
                        source = CameraIdentity.Source.UvcOnly,
                    )
                }
            }
        }
        return out
    }

    private fun UsbDevice.isVideoClass(): Boolean {
        // UVC = USB class 0x0E. Most webcams expose it on the device; some advertise per-interface
        // (Miscellaneous device class with UVC interfaces under it), so check both.
        if (deviceClass == 0x0E) return true
        for (i in 0 until interfaceCount) {
            if (getInterface(i).interfaceClass == 0x0E) return true
        }
        return false
    }
}
