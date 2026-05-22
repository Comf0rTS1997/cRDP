package com.crdp.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Accessibility-backed key filter that intercepts hardware-keyboard events
 * BEFORE the WindowManager's global shortcut router gets them. This is the
 * only way a regular (non-system) app can prevent Samsung DeX's Alt+F4 = "close
 * foreground app" shortcut, the launcher's Win key bindings, Alt+Tab, etc.,
 * from being consumed before reaching the Activity.
 *
 * Ported from termux-x11's `KeyInterceptor`. The mechanism:
 *
 *  1. Service is declared in the manifest with `BIND_ACCESSIBILITY_SERVICE`
 *     and an `accessibility-service` meta-data resource that sets
 *     `canRequestFilterKeyEvents="true"`.
 *  2. The user enables the service in Android Settings → Accessibility, or
 *     we auto-enable it after granting WRITE_SECURE_SETTINGS via:
 *         adb shell pm grant com.crdp.android android.permission.WRITE_SECURE_SETTINGS
 *  3. When enabled with `FLAG_REQUEST_FILTER_KEY_EVENTS`, Android delivers
 *     every key event to `onKeyEvent` BEFORE any other dispatch — return
 *     `true` to drop the event from the system, after we've forwarded it to
 *     the active session.
 *
 * The filter is only ENGAGED while [MainActivity] is foreground & connected
 * to an RDP session. Outside that window we either ignore events or only
 * release tracked modifiers — so the user retains normal system key behaviour
 * everywhere else.
 */
class KeyInterceptorService : AccessibilityService() {

    private val pressedKeys = LinkedHashSet<Int>()
    private var filterEnabled = false

    override fun onServiceConnected() {
        instance = this
        Log.d(TAG, "Accessibility key filter service connected")
        recheck()
    }

    override fun onDestroy() {
        instance = null
        pressedKeys.clear()
        super.onDestroy()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val activity = MainActivity.activeInstance
        if (activity == null) {
            // Activity gone — release any tracked modifiers and pass through.
            if (event.action == KeyEvent.ACTION_UP) pressedKeys.remove(event.keyCode)
            return false
        }
        val intercept = activity.shouldInterceptKeys()
        val isReleaseForTrackedKey = event.action == KeyEvent.ACTION_UP &&
            pressedKeys.contains(event.keyCode)

        val handled = if (intercept || isReleaseForTrackedKey) {
            activity.dispatchInterceptedKey(event)
        } else {
            false
        }

        if (intercept && event.action == KeyEvent.ACTION_DOWN) {
            pressedKeys.add(event.keyCode)
        } else if (event.action == KeyEvent.ACTION_UP) {
            pressedKeys.remove(event.keyCode)
        }

        recheck()
        return handled
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    companion object {
        private const val TAG = "cRdpKeyInterceptor"
        private const val SERVICE_CLASS = "com.crdp.app.KeyInterceptorService"

        @Volatile private var instance: KeyInterceptorService? = null

        @JvmStatic
        fun isConnected(): Boolean = instance != null

        /**
         * Toggle the filter on the live service whenever the activity's
         * interception state changes (entered/left a session, gained/lost
         * window focus). When disabled we leave the service connected so
         * we don't pay the re-bind latency on the next session — we just
         * drop the FLAG_REQUEST_FILTER_KEY_EVENTS bit.
         */
        @JvmStatic
        fun recheck() {
            val self = instance ?: return
            val a = MainActivity.activeInstance
            val shouldFilter = a != null &&
                (a.hasWindowFocus() || self.pressedKeys.isNotEmpty()) &&
                a.shouldInterceptKeys()
            if (shouldFilter == self.filterEnabled) return
            val info = AccessibilityServiceInfo()
            info.flags = if (shouldFilter) {
                AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            } else {
                AccessibilityServiceInfo.DEFAULT
            }
            self.serviceInfo = info
            self.filterEnabled = shouldFilter
            Log.d(TAG, "filter ${if (shouldFilter) "enabled" else "disabled"}")
        }

        /**
         * Best-effort auto-enable: requires WRITE_SECURE_SETTINGS, which is
         * not granted by default. If denied, callers should prompt the user
         * to enable manually in Settings.
         */
        @JvmStatic
        fun tryAutoEnable(ctx: Context): Boolean {
            return try {
                val resolver = ctx.contentResolver
                val serviceId = "${ctx.packageName}/$SERVICE_CLASS"
                val current = Settings.Secure.getString(
                    resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                ).orEmpty()
                if (!current.contains(serviceId)) {
                    val next = if (current.isEmpty()) serviceId else "$current:$serviceId"
                    Settings.Secure.putString(
                        resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next,
                    )
                }
                Settings.Secure.putString(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, "1")
                true
            } catch (e: SecurityException) {
                Log.w(TAG, "auto-enable denied — needs WRITE_SECURE_SETTINGS", e)
                false
            }
        }
    }
}
