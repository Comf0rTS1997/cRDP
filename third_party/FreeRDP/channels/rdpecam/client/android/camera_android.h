/**
 * cRDP / FreeRDP — Android rdpecam HAL, shared internals.
 *
 * The HAL is one ICamHal-backed instance ("android" subsystem). It owns:
 *   - Native Camera2 NDK capture for built-in / Camera2-visible external cameras.
 *   - A JNI-driven frame relay for UVC cameras Kotlin discovers via UsbManager.
 *
 * Both paths converge at SubmitFrame() which forwards the bytes to the
 * ICamHalSampleCapturedCallback the channel passed into StartStream.
 */

#ifndef FREERDP_CLIENT_RDPECAM_ANDROID_HAL_H
#define FREERDP_CLIENT_RDPECAM_ANDROID_HAL_H

#include <stdint.h>
#include <pthread.h>

#include "../camera.h"

#ifdef __cplusplus
extern "C" {
#endif

#define ANDROID_CAM_TAG CHANNELS_TAG("rdpecam-android.client")

/* All device IDs the HAL hands out come from this small set:
 *   "front", "back"        — Camera2 LENS_FACING_FRONT / BACK
 *   "external:<camId>"     — Camera2 LENS_FACING_EXTERNAL
 *   "usb:<vid>:<pid>"      — JNI bridge virtual device
 */
typedef enum
{
	CAM_ANDROID_BACKEND_NDK,    /* libcamera2ndk capture */
	CAM_ANDROID_BACKEND_BRIDGE, /* JNI frames pushed from Kotlin */
} CamAndroidBackend;

typedef struct cam_android_stream
{
	pthread_mutex_t lock;

	CameraDevice* dev;
	int streamIndex;
	ICamHalSampleCapturedCallback callback;

	BOOL streaming;
	CamAndroidBackend backend;

	/* Negotiated media type the channel asked us to deliver. */
	CAM_MEDIA_TYPE_DESCRIPTION mediaType;

	/* Backend-specific opaque state, allocated/freed by the backend driver. */
	void* state;
} CamAndroidStream;

typedef struct cam_android_hal
{
	ICamHal iHal;

	pthread_mutex_t lock;
	wHashTable* streams; /* deviceId -> CamAndroidStream */

	/* Virtual devices announced via the JNI bridge but not yet streaming.
	 * Keyed by deviceId (e.g. "usb:1234:5678"); value carries a label + caps. */
	wHashTable* bridgeRegistry;
} CamAndroidHal;

/* Singleton — accessed by the JNI shim and the capture backends. Set when the
 * subsystem entry point registers, cleared on Free. */
extern CamAndroidHal* g_cam_android_hal;

/* Backend dispatch — capture vs. bridge */
UINT cam_android_capture_start(CamAndroidStream* stream);
void cam_android_capture_stop(CamAndroidStream* stream);

UINT cam_android_bridge_start(CamAndroidStream* stream);
void cam_android_bridge_stop(CamAndroidStream* stream);

/* Push a captured frame out through the channel callback. Used by both
 * backends (capture thread feeds it raw NV12 / encoded H.264; the bridge
 * forwards JNI-pushed bytes the same way). */
UINT cam_android_submit_frame(CamAndroidStream* stream, const BYTE* data, size_t size);

/* JNI bridge — implemented in camera_android_bridge.c, declared here so
 * camera_android_jni.c can call them. */
UINT cam_android_bridge_register(const char* deviceId, const char* label,
                                 int width, int height, int fps);
UINT cam_android_bridge_unregister(const char* deviceId);
UINT cam_android_bridge_push(const char* deviceId, const BYTE* data, size_t size,
                             BOOL isKeyframe, BOOL isEncoded, int64_t tsUs);

#ifdef __cplusplus
}
#endif

#endif /* FREERDP_CLIENT_RDPECAM_ANDROID_HAL_H */
