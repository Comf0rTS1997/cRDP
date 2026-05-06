/**
 * cRDP / FreeRDP — Android rdpecam HAL: JNI exports.
 *
 * These symbols match com.crdp.engine.afreerdp.NativeCameraBridge in Kotlin.
 * They live inside the rdpecam-android subsystem .so so that loading the
 * subsystem also exposes the JNI surface — Kotlin loads libfreerdp-android.so
 * (the host) which in turn dlopens the rdpecam subsystem when the channel is
 * activated; once that happens these symbols become resolvable.
 *
 * If the channel isn't activated for the current session, the JNI calls below
 * still link (the symbols exist in the subsystem) but cam_android_bridge_*
 * return ERROR_INVALID_STATE because g_cam_android_hal is NULL.
 */

#include <jni.h>
#include <string.h>
#include <stdlib.h>

#include <winpr/string.h>

#include "camera_android.h"

/* Helper: convert jstring → UTF-8 C string allocated via _strdup; caller frees. */
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
Java_com_crdp_engine_afreerdp_NativeCameraBridge_registerDevice(JNIEnv* env, jobject thiz,
                                                                jstring jDeviceId, jstring jLabel,
                                                                jint width, jint height, jint fps)
{
	WINPR_UNUSED(thiz);
	char* devId = jni_strdup(env, jDeviceId);
	char* label = jni_strdup(env, jLabel);
	if (!devId)
	{
		free(label);
		return (jint)ERROR_INVALID_PARAMETER;
	}
	UINT rc = cam_android_bridge_register(devId, label, (int)width, (int)height, (int)fps);
	free(devId);
	free(label);
	return (jint)rc;
}

JNIEXPORT jint JNICALL
Java_com_crdp_engine_afreerdp_NativeCameraBridge_unregisterDevice(JNIEnv* env, jobject thiz,
                                                                  jstring jDeviceId)
{
	WINPR_UNUSED(thiz);
	char* devId = jni_strdup(env, jDeviceId);
	if (!devId)
		return (jint)ERROR_INVALID_PARAMETER;
	UINT rc = cam_android_bridge_unregister(devId);
	free(devId);
	return (jint)rc;
}

JNIEXPORT jint JNICALL
Java_com_crdp_engine_afreerdp_NativeCameraBridge_pushFrame(JNIEnv* env, jobject thiz,
                                                           jstring jDeviceId, jbyteArray jFrame,
                                                           jint jOffset, jint jLength,
                                                           jlong jTimestampUs, jboolean jKeyframe,
                                                           jboolean jEncoded)
{
	WINPR_UNUSED(thiz);
	if (!jFrame || jLength <= 0)
		return (jint)ERROR_INVALID_PARAMETER;
	char* devId = jni_strdup(env, jDeviceId);
	if (!devId)
		return (jint)ERROR_INVALID_PARAMETER;

	/* Copy out of the JVM array so the bridge can hand the bytes to the channel
	 * without holding a JNI reference across the call. The channel's encoder
	 * ring already holds enough credit headroom (ECAM_MAX_SAMPLE_CREDITS) that
	 * we don't need our own queue here. */
	jbyte* bytes = (*env)->GetByteArrayElements(env, jFrame, NULL);
	UINT rc = ERROR_INVALID_PARAMETER;
	if (bytes)
	{
		const BYTE* src = (const BYTE*)bytes + jOffset;
		rc = cam_android_bridge_push(devId, src, (size_t)jLength, jKeyframe ? TRUE : FALSE,
		                             jEncoded ? TRUE : FALSE, (int64_t)jTimestampUs);
		(*env)->ReleaseByteArrayElements(env, jFrame, bytes, JNI_ABORT);
	}
	free(devId);
	return (jint)rc;
}
