package com.crdp.app

import android.app.Activity
import android.content.ComponentName
import android.util.Log

/**
 * Reflection wrapper around Samsung's `SemWindowManager.requestMetaKeyEvent`.
 *
 * On DeX, the WindowManager intercepts Win / Alt+F4 / Alt+Tab BEFORE any
 * Activity dispatch and before the global AccessibilityService key filter.
 * The only documented (well — observed-via-reverse-engineering) way for a
 * sideloaded app to receive those keys in DeX is to call Samsung's private
 * `SemWindowManager.requestMetaKeyEvent(componentName, true)`. Termux-x11
 * uses the same shim — see SamsungDexUtils.java in that repo.
 *
 * Failure modes:
 *   - On non-Samsung devices the class won't load → [available] is false.
 *   - On Samsung devices without DeX (or DeX disabled) the call is a no-op
 *     but doesn't throw.
 *   - On future Samsung firmware where the method signature changes, the
 *     reflection lookup fails at static-init time and we silently degrade.
 */
object SamsungDexUtils {
    private const val TAG = "SamsungDexUtils"
    private val requestMetaKeyEventMethod: java.lang.reflect.Method?
    private val manager: Any?

    init {
        var m: java.lang.reflect.Method? = null
        var inst: Any? = null
        try {
            val clazz = Class.forName("com.samsung.android.view.SemWindowManager")
            val obtain = clazz.getMethod("getInstance")
            m = clazz.getDeclaredMethod(
                "requestMetaKeyEvent",
                ComponentName::class.java,
                Boolean::class.javaPrimitiveType,
            )
            inst = obtain.invoke(null)
            Log.d(TAG, "SemWindowManager available")
        } catch (t: Throwable) {
            Log.d(TAG, "SemWindowManager not available: ${t.javaClass.simpleName}")
            m = null
            inst = null
        }
        requestMetaKeyEventMethod = m
        manager = inst
    }

    fun available(): Boolean = requestMetaKeyEventMethod != null && manager != null

    /**
     * Enable or disable meta-key capture for [activity]. When enabled, DeX
     * forwards Win / Alt+F4 / Alt+Tab to the Activity instead of consuming
     * them as system shortcuts. Call with `enable=true` on session start and
     * `enable=false` on session exit; leaving it on after disconnect would
     * break the user's ability to use DeX shortcuts elsewhere.
     */
    fun dexMetaKeyCapture(activity: Activity, enable: Boolean) {
        val method = requestMetaKeyEventMethod ?: return
        val mgr = manager ?: return
        try {
            method.invoke(mgr, activity.componentName, enable)
            Log.d(TAG, "dexMetaKeyCapture(enable=$enable) ok")
        } catch (t: Throwable) {
            Log.d(TAG, "dexMetaKeyCapture failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }
}
