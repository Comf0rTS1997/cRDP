package com.crdp.engine.afreerdp

/**
 * JNI bridge for the FreeRDP "printer" channel's Android backend (see
 * third_party/FreeRDP/channels/printer/client/android/). Lives in the same
 * .so as the rdpecam HAL bridge — the subsystem .o files are linked into
 * libfreerdp-client3.so, so loading freerdp-android pulls the symbols in.
 *
 * Both setters mutate process-global state that the backend reads when a
 * print job starts. Safe to call before connect() and they take effect for
 * the next session; the JNI side is mutex-guarded so concurrent calls are
 * fine.
 *
 * If the printer channel isn't activated for the current session the setters
 * still link but the values just sit unused.
 */
object PrinterRedirectBridge {
    init {
        runCatching { System.loadLibrary("freerdp-client3") }
        runCatching { System.loadLibrary("freerdp-android") }
    }

    /**
     * True only when libfreerdp-client3.so was rebuilt with CHANNEL_PRINTER_CLIENT=ON
     * and the printer-android subsystem statically linked in. Determined by probing
     * one JNI symbol once; cached for the process lifetime.
     *
     * CRITICAL: callers MUST gate /printer argv emission on this — if the native
     * subsystem is missing, FreeRDP's rdpdr falls through to a dynamic dlopen of
     * libprinter-client.so (which we don't ship as a separate .so on Android),
     * that dlopen fails, devman_load_device_service returns error 1359, and the
     * server kicks the session with ERRCONNECT_POST_CONNECT_FAILED. End result:
     * connections succeed → immediately drop → black screen / retry loop.
     *
     * The check costs one no-op JNI call the first time it's read. When the symbol
     * is absent we cache `false` and never call again.
     */
    val isNativeAvailable: Boolean by lazy {
        // Probe BOTH external setters. Empirically one symbol can resolve while
        // the other is missing when libfreerdp-client3.so is rebuilt without
        // every printer-android translation unit linked — and emitting /printer
        // when ANY setter is missing still kills the session with
        // ERRCONNECT_POST_CONNECT_FAILED because rdpdr can't find the channel.
        try {
            setPrinterName("cRDP")
            setSpoolDir("")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Point the backend at the directory it should drop completed print jobs
     * into. Must be writable from the FreeRDP worker thread (i.e. by the app's
     * UID). Typically the cRDP app passes Context.getExternalFilesDir("printer_spool").
     */
    external fun setSpoolDir(path: String): Int

    /** Override the printer-display name advertised over RDPDR. Pass empty to reset. */
    external fun setPrinterName(name: String): Int
}
