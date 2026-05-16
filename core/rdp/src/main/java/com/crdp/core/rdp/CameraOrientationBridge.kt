package com.crdp.core.rdp

import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-wide bridge from the UI layer (which knows the Activity's current
 * display rotation) to the active RDP engine (which delivers it to the rdpecam
 * native HAL so camera frames can be rotated into display orientation).
 *
 * Modeled as a one-way push: the UI sets a rotation, the engine module installs
 * a callback at app init that forwards to its JNI shim. We do this through a
 * shared object in core/rdp rather than DI because the session feature module
 * and the engine module live in different DI scopes and don't have a direct
 * compile-time dependency on each other; routing through core/rdp avoids that.
 *
 * Default rotation is 0 (portrait); the engine reads the most recent value at
 * camera capture start.
 */
object CameraOrientationBridge {

    private val lastRotation = AtomicInteger(0)
    @Volatile private var sink: ((Int) -> Unit)? = null

    /** Engine module calls this at startup with its JNI bridge. */
    fun installSink(callback: (Int) -> Unit) {
        sink = callback
        callback(lastRotation.get())
    }

    /** UI layer calls this when the display rotation changes. */
    fun setDisplayRotation(degrees: Int) {
        val normalized = ((degrees % 360) + 360) % 360
        lastRotation.set(normalized)
        sink?.invoke(normalized)
    }
}
