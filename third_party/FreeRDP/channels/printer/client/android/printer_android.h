/**
 * cRDP / FreeRDP — Android printer backend: shared declarations.
 *
 * Internal interface between printer_android.c (the rdpPrinterDriver backend
 * registered with the printer client channel) and printer_android_jni.c
 * (the JNI surface exposed to com.crdp.engine.afreerdp.PrinterRedirectBridge).
 */

#ifndef CRDP_PRINTER_ANDROID_H
#define CRDP_PRINTER_ANDROID_H

#include <winpr/wtypes.h>

#ifdef __cplusplus
extern "C"
{
#endif

	/**
	 * Set the spool directory the backend writes each print job into. The Kotlin
	 * side passes a per-app private path (typically a sub-folder of
	 * Context.getExternalFilesDir) so files are scoped to the cRDP package and
	 * survive process death without storage permissions.
	 *
	 * Must be called before the printer channel starts; safe to call multiple
	 * times (subsequent calls replace the previous value). When unset the backend
	 * still functions but jobs go to the system temp dir.
	 */
	UINT printer_android_set_spool_dir(const char* path);

	/**
	 * Override the printer name announced over RDPDR. Defaults to "cRDP" if not
	 * set. Matters because the server uses this as the printer-display label.
	 */
	UINT printer_android_set_printer_name(const char* name);

#ifdef __cplusplus
}
#endif

#endif
