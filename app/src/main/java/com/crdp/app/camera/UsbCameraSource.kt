package com.crdp.app.camera

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import com.crdp.engine.afreerdp.NativeCameraBridge
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Capture pipeline for UVC cameras that the system Camera2 service does NOT expose
 * via LENS_FACING_EXTERNAL. The session calls [start] when a UVC-only device is the
 * selected camera; frames flow into the FreeRDP rdpecam HAL via [NativeCameraBridge].
 *
 * The actual UVC capture is delegated to [UvcBackend]. A no-op default backend ships
 * with the build so the rest of the pipeline compiles and runs; integrate a libuvc /
 * UVCCamera-based backend by setting [backend] from your DI module before connect.
 */
@Singleton
class UsbCameraSource @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    interface UvcBackend {
        /**
         * Open the device, negotiate a frame format (NV12 or H.264 if supported), and
         * start capture. Each frame must be forwarded to [NativeCameraBridge.pushFrame]
         * with [deviceId] as the registered key.
         *
         * @return true if capture started.
         */
        fun start(context: Context, device: UsbDevice, deviceId: String, width: Int, height: Int, fps: Int): Boolean

        /** Idempotent — safe to call from [stop] even if start failed. */
        fun stop(deviceId: String)
    }

    @Volatile
    var backend: UvcBackend = NoopUvcBackend
        @Synchronized set

    private var activeDeviceId: String? = null

    /**
     * Begin capture for [deviceId] (formatted as "usb:<vid>:<pid>"). Returns false if
     * the device isn't currently attached, USB permission isn't granted, or no UVC
     * backend is wired up — callers should fall back to "camera unavailable" UX rather
     * than failing the whole session.
     */
    fun start(deviceId: String, width: Int = 1280, height: Int = 720, fps: Int = 30): Boolean {
        if (!deviceId.startsWith("usb:")) return false
        val parts = deviceId.removePrefix("usb:").split(":")
        if (parts.size != 2) return false
        val vid = parts[0].toIntOrNull() ?: return false
        val pid = parts[1].toIntOrNull() ?: return false

        val um = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        val device = um.deviceList.values.firstOrNull { it.vendorId == vid && it.productId == pid }
            ?: return false.also { Log.w(TAG, "device $deviceId not attached") }
        if (!um.hasPermission(device)) {
            Log.w(TAG, "USB permission not granted for $deviceId — caller should request it first")
            return false
        }

        // Announce to native HAL so rdpecam enumeration sees it as a virtual device.
        if (NativeCameraBridge.registerDevice(deviceId, device.productName ?: deviceId, width, height, fps) != 0) {
            Log.w(TAG, "registerDevice failed for $deviceId")
            return false
        }

        val started = backend.start(context, device, deviceId, width, height, fps)
        if (!started) {
            Log.w(TAG, "UVC backend declined to start $deviceId (no libuvc integration?)")
            NativeCameraBridge.unregisterDevice(deviceId)
            return false
        }
        activeDeviceId = deviceId
        return true
    }

    fun stop() {
        val id = activeDeviceId ?: return
        runCatching { backend.stop(id) }
        runCatching { NativeCameraBridge.unregisterDevice(id) }
        activeDeviceId = null
    }

    private object NoopUvcBackend : UvcBackend {
        override fun start(
            context: Context,
            device: UsbDevice,
            deviceId: String,
            width: Int,
            height: Int,
            fps: Int,
        ): Boolean {
            Log.i(
                TAG,
                "NoopUvcBackend: skipping libuvc capture for $deviceId. " +
                    "Wire UsbCameraSource.backend in your DI module to enable UVC fallback.",
            )
            return false
        }

        override fun stop(deviceId: String) = Unit
    }

    private companion object {
        const val TAG = "UsbCameraSource"
    }
}
