/**
 * cRDP / FreeRDP — Android rdpecam HAL: JNI bridge backend.
 *
 * Cameras whose USB UVC stack the platform Camera2 service doesn't expose
 * (older OEMs without uvc_v4l2_kernel cooperation) are captured in Kotlin
 * via libuvc/UVCCamera, then pushed here as raw NV12 or pre-encoded H.264 NALs.
 * Frames arrive at cam_android_bridge_push() and get forwarded to whichever
 * stream is currently active for that device id.
 */

#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <pthread.h>

#include <winpr/string.h>

#include "camera_android.h"

#define TAG ANDROID_CAM_TAG

/* Per-virtual-device record kept in bridgeRegistry. Mirror of the type in
 * camera_android.c — kept in sync via the public registration API only, so the
 * JNI side never touches the struct directly. */
typedef struct
{
	char* label;
	int width;
	int height;
	int fps;
} CamAndroidBridgeEntry;

/* No backend-specific state needed: bridge streams are pure pass-throughs. The
 * JNI side calls cam_android_bridge_push() which looks up the stream and
 * forwards bytes via cam_android_submit_frame. */

UINT cam_android_bridge_start(CamAndroidStream* stream)
{
	if (!stream)
		return ERROR_INVALID_PARAMETER;
	stream->state = NULL;
	WLog_INFO(TAG, "bridge stream started for %s (%dx%d)",
	          stream->dev ? stream->dev->deviceId : "?",
	          stream->mediaType.Width, stream->mediaType.Height);
	return CHANNEL_RC_OK;
}

void cam_android_bridge_stop(CamAndroidStream* stream)
{
	if (!stream)
		return;
	stream->state = NULL;
	stream->streaming = FALSE;
}

UINT cam_android_bridge_register(const char* deviceId, const char* label,
                                 int width, int height, int fps)
{
	if (!g_cam_android_hal || !deviceId)
		return ERROR_INVALID_STATE;
	if (strncmp(deviceId, "usb:", 4) != 0)
		return ERROR_INVALID_PARAMETER;

	CamAndroidBridgeEntry* entry = (CamAndroidBridgeEntry*)calloc(1, sizeof(*entry));
	if (!entry)
		return CHANNEL_RC_NO_MEMORY;
	entry->label = label ? _strdup(label) : _strdup(deviceId);
	if (!entry->label)
	{
		free(entry);
		return CHANNEL_RC_NO_MEMORY;
	}
	entry->width = width > 0 ? width : 1280;
	entry->height = height > 0 ? height : 720;
	entry->fps = fps > 0 ? fps : 30;

	pthread_mutex_lock(&g_cam_android_hal->lock);
	/* Remove any prior entry under the same id so re-attach is idempotent. */
	HashTable_Remove(g_cam_android_hal->bridgeRegistry, deviceId);
	HashTable_Insert(g_cam_android_hal->bridgeRegistry, deviceId, entry);
	pthread_mutex_unlock(&g_cam_android_hal->lock);
	return CHANNEL_RC_OK;
}

UINT cam_android_bridge_unregister(const char* deviceId)
{
	if (!g_cam_android_hal || !deviceId)
		return ERROR_INVALID_STATE;
	pthread_mutex_lock(&g_cam_android_hal->lock);
	HashTable_Remove(g_cam_android_hal->bridgeRegistry, deviceId);
	/* Also tear down any active stream for this device — caller may unplug */
	CamAndroidStream* s = (CamAndroidStream*)HashTable_GetItemValue(g_cam_android_hal->streams,
	                                                               deviceId);
	if (s)
		HashTable_Remove(g_cam_android_hal->streams, deviceId);
	pthread_mutex_unlock(&g_cam_android_hal->lock);
	return CHANNEL_RC_OK;
}

UINT cam_android_bridge_push(const char* deviceId, const BYTE* data, size_t size,
                             BOOL isKeyframe, BOOL isEncoded, int64_t tsUs)
{
	WINPR_UNUSED(isKeyframe);
	WINPR_UNUSED(isEncoded);
	WINPR_UNUSED(tsUs);
	if (!g_cam_android_hal || !deviceId || !data || !size)
		return ERROR_INVALID_PARAMETER;

	pthread_mutex_lock(&g_cam_android_hal->lock);
	CamAndroidStream* s = (CamAndroidStream*)HashTable_GetItemValue(g_cam_android_hal->streams,
	                                                               deviceId);
	pthread_mutex_unlock(&g_cam_android_hal->lock);
	if (!s)
		return CHANNEL_RC_OK; /* stream not active right now — drop the frame */

	/* The channel layer expects the byte stream in the negotiated CAM_MEDIA_FORMAT.
	 * For now we trust the Kotlin side: if isEncoded is true, frames are H.264
	 * Annex-B NAL units; otherwise raw NV12 in the size we registered with. */
	return cam_android_submit_frame(s, data, size);
}
