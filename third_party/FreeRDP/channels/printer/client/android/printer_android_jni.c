/**
 * cRDP / FreeRDP — Android printer backend: JNI exports.
 *
 * Matches com.crdp.engine.afreerdp.PrinterRedirectBridge in Kotlin. Lives
 * inside the printer-android subsystem .so so loading the subsystem also
 * exposes these symbols. If the printer channel isn't activated for the
 * current session the setters still link but have no effect on data flow
 * (they only mutate process-global config consumed at CreatePrintJob time).
 */

#include <jni.h>
#include <string.h>
#include <stdlib.h>

#include <winpr/string.h>
#include <winpr/wtypes.h>

#include "printer_android.h"

static char* jni_strdup(JNIEnv* env, jstring s)
{
	if (!s)
		return NULL;
	const char* utf = (*env)->GetStringUTFChars(env, s, NULL);
	if (!utf)
		return NULL;
	char* copy = _strdup(utf);
	(*env)->ReleaseStringUTFChars(env, s, utf);
	return copy;
}

JNIEXPORT jint JNICALL
Java_com_crdp_engine_afreerdp_PrinterRedirectBridge_setSpoolDir(JNIEnv* env, jobject thiz,
                                                                jstring jPath)
{
	(void)thiz;
	char* path = jni_strdup(env, jPath);
	UINT rc = printer_android_set_spool_dir(path);
	free(path);
	return (jint)rc;
}

JNIEXPORT jint JNICALL
Java_com_crdp_engine_afreerdp_PrinterRedirectBridge_setPrinterName(JNIEnv* env, jobject thiz,
                                                                   jstring jName)
{
	(void)thiz;
	char* name = jni_strdup(env, jName);
	UINT rc = printer_android_set_printer_name(name);
	free(name);
	return (jint)rc;
}
