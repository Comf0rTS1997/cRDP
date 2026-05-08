/*
 * Thin JNI shim for libfreerdp-android.so.
 *
 * The FQN of this class (com.freerdp.freerdpcore.services.LibFreeRDP) is
 * baked into the native library by FreeRDP — JNIEXPORT symbols are mangled
 * as Java_com_freerdp_freerdpcore_services_LibFreeRDP_*. We must keep this
 * exact package + class name to receive callbacks.
 *
 * We deliberately do NOT depend on the upstream aFreeRDP demo classes
 * (GlobalApp, SessionState, BookmarkBase, ApplicationSettingsActivity, …).
 * Instead, callbacks are dispatched to a registered AdapterCallbacks impl
 * keyed by instance handle.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.freerdp.freerdpcore.services;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LibFreeRDP {
    private static final String TAG = "LibFreeRDP";

    public static final long VERIFY_CERT_FLAG_NONE              = 0x000;
    public static final long VERIFY_CERT_FLAG_LEGACY            = 0x002;
    public static final long VERIFY_CERT_FLAG_REDIRECT          = 0x010;
    public static final long VERIFY_CERT_FLAG_GATEWAY           = 0x020;
    public static final long VERIFY_CERT_FLAG_CHANGED           = 0x040;
    public static final long VERIFY_CERT_FLAG_MISMATCH          = 0x080;
    public static final long VERIFY_CERT_FLAG_MATCH_LEGACY_SHA1 = 0x100;
    public static final long VERIFY_CERT_FLAG_FP_IS_PEM         = 0x200;

    private LibFreeRDP() {}

    /** Per-instance callbacks. Native code looks up by instance handle. */
    public interface AdapterCallbacks {
        void onPreConnect();
        void onConnectionSuccess();
        void onConnectionFailure();
        void onDisconnecting();
        void onDisconnected();

        /**
         * Auth challenge. Implementations mutate the StringBuilder args in place
         * with the chosen username/domain/password and return true. Return false
         * to reject. The native thread is blocked until this returns.
         */
        boolean onAuthenticate(StringBuilder username, StringBuilder domain, StringBuilder password);

        boolean onGatewayAuthenticate(StringBuilder username, StringBuilder domain, StringBuilder password);

        /** Return 1 = accept always, 2 = accept once, 0 = reject. Blocks native thread. */
        int onVerifyCertificateEx(String host, long port, String commonName,
                                  String subject, String issuer, String fingerprint, long flags);

        int onVerifyChangedCertificateEx(String host, long port, String commonName,
                                         String subject, String issuer, String fingerprint,
                                         String oldSubject, String oldIssuer, String oldFingerprint,
                                         long flags);

        void onSettingsChanged(int width, int height, int bpp);
        void onGraphicsUpdate(int x, int y, int width, int height);
        void onGraphicsResize(int width, int height, int bpp);
        void onRemoteClipboardChanged(String data);

        /**
         * Bitmap to be filled by freerdp_update_graphics. The engine owns this Bitmap,
         * keeps a stable reference for the lifetime of the connection, and returns it
         * on every freerdp_update_graphics call.
         */
        Bitmap requestSurfaceBitmap();

        /**
         * Server-supplied cursor bitmap. Pixels are in Android Bitmap.Config.ARGB_8888
         * byte order — bytes in memory are R,G,B,A (matches PIXEL_FORMAT_RGBA32 on the
         * C side, same convention as the frame buffer's PIXEL_FORMAT_RGBX32).
         * Length is exactly width * height * 4. (hotX, hotY) is the cursor hotspot in
         * pixels relative to the top-left of the bitmap.
         */
        default void onCursorBitmap(byte[] argb, int width, int height, int hotX, int hotY) {}

        /**
         * Server signaled a stateful cursor change without a bitmap.
         *   state == 0  → cursor hidden (server requested SetNull)
         *   state == 1  → use the local default cursor (server requested SetDefault, or
         *                 a Set arrived without a usable bitmap)
         */
        default void onCursorState(int state) {}
    }

    public static final int CURSOR_STATE_HIDDEN  = 0;
    public static final int CURSOR_STATE_DEFAULT = 1;

    private static final Map<Long, AdapterCallbacks> CALLBACKS = new HashMap<>();
    private static volatile boolean sLoaded = false;
    private static volatile boolean sHasH264 = false;

    public static synchronized void loadNativeLibraries() {
        if (sLoaded) return;
        try {
            System.loadLibrary("freerdp-android");
            String version = freerdp_get_jni_version();
            String[] versions = version.split("[\\.-]");
            if (versions.length > 0) {
                int major = Integer.parseInt(versions[0]);
                System.loadLibrary("freerdp-client" + major);
                System.loadLibrary("freerdp" + major);
                System.loadLibrary("winpr" + major);
            }
            Pattern p = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+).*");
            Matcher m = p.matcher(version);
            if (m.matches() && m.groupCount() >= 3) {
                int major = Integer.parseInt(m.group(1));
                int minor = Integer.parseInt(m.group(2));
                int patch = Integer.parseInt(m.group(3));
                if (major > 2 || (major == 2 && minor > 5) || (major == 2 && minor == 5 && patch >= 1)) {
                    sHasH264 = freerdp_has_h264();
                }
            }
            sLoaded = true;
            Log.i(TAG, "Loaded FreeRDP native libs (version=" + version + ", h264=" + sHasH264 + ")");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load FreeRDP native libs: " + e.getMessage());
            throw e;
        }
    }

    public static boolean hasH264Support() { return sHasH264; }

    public static void registerCallbacks(long instance, AdapterCallbacks cb) {
        synchronized (CALLBACKS) { CALLBACKS.put(instance, cb); }
    }
    public static void unregisterCallbacks(long instance) {
        synchronized (CALLBACKS) { CALLBACKS.remove(instance); }
    }
    private static AdapterCallbacks cb(long instance) {
        synchronized (CALLBACKS) { return CALLBACKS.get(instance); }
    }

    // ────── public Kotlin-friendly API ─────────────────────────────────────────
    public static long newInstance(Context context)            { return freerdp_new(context); }
    public static void freeInstance(long inst)                 { freerdp_free(inst); }
    public static boolean parseArguments(long inst, String[] a){ return freerdp_parse_arguments(inst, a); }
    public static boolean connect(long inst)                   { return freerdp_connect(inst); }
    public static boolean disconnect(long inst)                { return freerdp_disconnect(inst); }
    public static String  lastErrorString(long inst)           { return freerdp_get_last_error_string(inst); }
    public static boolean updateGraphics(long inst, Bitmap b, int x, int y, int w, int h) {
        return freerdp_update_graphics(inst, b, x, y, w, h);
    }
    public static boolean sendCursorEvent(long inst, int x, int y, int flags) {
        return freerdp_send_cursor_event(inst, x, y, flags);
    }

    /**
     * Forwards one multitouch contact to FreeRDP ({@code freerdp_client_handle_touch}).
     * {@code flags} uses FreeRDP {@code FREERDP_TOUCH_*} values (see {@code AFreeRdpEngine}).
     */
    public static boolean sendTouchContact(long inst, int flags, int finger, int pressure, int x, int y) {
        return freerdp_send_touch_contact(inst, flags, finger, pressure, x, y);
    }
    public static boolean sendKeyEvent(long inst, int keycode, boolean down) {
        return freerdp_send_key_event(inst, keycode, down);
    }
    public static boolean sendUnicodeKeyEvent(long inst, int code, boolean down) {
        return freerdp_send_unicodekey_event(inst, code, down);
    }
    public static boolean sendClipboardData(long inst, String data) {
        return freerdp_send_clipboard_data(inst, data);
    }
    public static String version() { return freerdp_get_version(); }

    // ────── native declarations (FQN must match libfreerdp-android.so symbols) ─
    private static native boolean freerdp_has_h264();
    private static native String  freerdp_get_jni_version();
    private static native String  freerdp_get_version();
    private static native String  freerdp_get_build_revision();
    private static native String  freerdp_get_build_config();
    private static native long    freerdp_new(Context context);
    private static native void    freerdp_free(long inst);
    private static native boolean freerdp_parse_arguments(long inst, String[] args);
    private static native boolean freerdp_connect(long inst);
    private static native boolean freerdp_disconnect(long inst);
    private static native boolean freerdp_update_graphics(long inst, Bitmap bitmap, int x, int y, int w, int h);
    private static native boolean freerdp_send_cursor_event(long inst, int x, int y, int flags);
    private static native boolean freerdp_send_touch_contact(long inst, int flags, int finger, int pressure, int x, int y);
    private static native boolean freerdp_send_key_event(long inst, int keycode, boolean down);
    private static native boolean freerdp_send_unicodekey_event(long inst, int keycode, boolean down);
    private static native boolean freerdp_send_clipboard_data(long inst, String data);
    private static native String  freerdp_get_last_error_string(long inst);

    // ────── callbacks invoked from JNI ─────────────────────────────────────────
    // These names + signatures must EXACTLY match what FreeRDP's android client
    // module calls back into. Do not refactor without checking the C side.

    private static void OnConnectionSuccess(long inst) {
        AdapterCallbacks c = cb(inst); if (c != null) c.onConnectionSuccess();
    }
    private static void OnConnectionFailure(long inst) {
        AdapterCallbacks c = cb(inst); if (c != null) c.onConnectionFailure();
    }
    private static void OnPreConnect(long inst) {
        AdapterCallbacks c = cb(inst); if (c != null) c.onPreConnect();
    }
    private static void OnDisconnecting(long inst) {
        AdapterCallbacks c = cb(inst); if (c != null) c.onDisconnecting();
    }
    private static void OnDisconnected(long inst) {
        AdapterCallbacks c = cb(inst); if (c != null) c.onDisconnected();
    }
    private static void OnSettingsChanged(long inst, int w, int h, int bpp) {
        AdapterCallbacks c = cb(inst); if (c != null) c.onSettingsChanged(w, h, bpp);
    }
    private static boolean OnAuthenticate(long inst, StringBuilder u, StringBuilder d, StringBuilder p) {
        AdapterCallbacks c = cb(inst);
        return c != null && c.onAuthenticate(u, d, p);
    }
    private static boolean OnGatewayAuthenticate(long inst, StringBuilder u, StringBuilder d, StringBuilder p) {
        AdapterCallbacks c = cb(inst);
        return c != null && c.onGatewayAuthenticate(u, d, p);
    }
    private static int OnVerifyCertificateEx(long inst, String host, long port, String commonName,
                                             String subject, String issuer, String fingerprint, long flags) {
        AdapterCallbacks c = cb(inst);
        return c == null ? 0 : c.onVerifyCertificateEx(host, port, commonName, subject, issuer, fingerprint, flags);
    }
    private static int OnVerifyChangedCertificateEx(long inst, String host, long port, String commonName,
                                                    String subject, String issuer, String fingerprint,
                                                    String oldSubject, String oldIssuer, String oldFingerprint,
                                                    long flags) {
        AdapterCallbacks c = cb(inst);
        return c == null ? 0 : c.onVerifyChangedCertificateEx(host, port, commonName, subject, issuer,
                                                              fingerprint, oldSubject, oldIssuer, oldFingerprint,
                                                              flags);
    }
    private static void OnGraphicsUpdate(long inst, int x, int y, int w, int h) {
        AdapterCallbacks c = cb(inst); if (c != null) c.onGraphicsUpdate(x, y, w, h);
    }
    private static void OnGraphicsResize(long inst, int w, int h, int bpp) {
        AdapterCallbacks c = cb(inst); if (c != null) c.onGraphicsResize(w, h, bpp);
    }
    private static void OnRemoteClipboardChanged(long inst, String data) {
        AdapterCallbacks c = cb(inst); if (c != null) c.onRemoteClipboardChanged(data);
    }
    private static void OnCursorBitmap(long inst, byte[] argb, int w, int h, int hotX, int hotY) {
        AdapterCallbacks c = cb(inst); if (c != null) c.onCursorBitmap(argb, w, h, hotX, hotY);
    }
    private static void OnCursorState(long inst, int state) {
        AdapterCallbacks c = cb(inst); if (c != null) c.onCursorState(state);
    }
}
