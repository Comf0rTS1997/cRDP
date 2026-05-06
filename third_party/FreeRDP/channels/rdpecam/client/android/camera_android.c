/**
 * cRDP / FreeRDP — Android rdpecam HAL: subsystem entry, ICamHal vtable, enumeration.
 *
 * Built-in cameras are enumerated through libcamera2ndk; UVC bridge entries get
 * appended afterwards. StartStream dispatches to the right backend based on the
 * device ID prefix.
 */

#include <stdio.h>
#include <string.h>
#include <pthread.h>

#include <android/log.h>
#include <camera/NdkCameraManager.h>
#include <camera/NdkCameraDevice.h>
#include <camera/NdkCameraMetadata.h>

#include "camera_android.h"

#define TAG ANDROID_CAM_TAG

CamAndroidHal* g_cam_android_hal = NULL;

/* Per-virtual-device record kept in bridgeRegistry. */
typedef struct
{
	char* label;
	int width;
	int height;
	int fps;
} CamAndroidBridgeEntry;

static void cam_android_stream_free(void* obj)
{
	CamAndroidStream* s = (CamAndroidStream*)obj;
	if (!s)
		return;
	if (s->streaming)
	{
		if (s->backend == CAM_ANDROID_BACKEND_NDK)
			cam_android_capture_stop(s);
		else
			cam_android_bridge_stop(s);
	}
	pthread_mutex_destroy(&s->lock);
	free(s);
}

static void cam_android_bridge_entry_free(void* obj)
{
	CamAndroidBridgeEntry* e = (CamAndroidBridgeEntry*)obj;
	if (!e)
		return;
	free(e->label);
	free(e);
}

/* Resolve "front"/"back"/"external:..." to a camera2 ID; returns NULL when not found. */
static const char* cam_android_resolve_camera2_id(ACameraManager* mgr, const char* deviceId,
                                                  ACameraIdList** outList)
{
	ACameraIdList* list = NULL;
	if (ACameraManager_getCameraIdList(mgr, &list) != ACAMERA_OK || !list)
		return NULL;

	const char* match = NULL;
	for (int i = 0; i < list->numCameras; ++i)
	{
		const char* id = list->cameraIds[i];
		ACameraMetadata* meta = NULL;
		if (ACameraManager_getCameraCharacteristics(mgr, id, &meta) != ACAMERA_OK || !meta)
			continue;

		ACameraMetadata_const_entry e;
		uint8_t facing = 0;
		if (ACameraMetadata_getConstEntry(meta, ACAMERA_LENS_FACING, &e) == ACAMERA_OK && e.count > 0)
			facing = e.data.u8[0];
		ACameraMetadata_free(meta);

		if (strcmp(deviceId, "front") == 0 && facing == ACAMERA_LENS_FACING_FRONT)
		{
			match = id;
			break;
		}
		else if (strcmp(deviceId, "back") == 0 && facing == ACAMERA_LENS_FACING_BACK)
		{
			match = id;
			break;
		}
		else if (strncmp(deviceId, "external:", 9) == 0 && facing == ACAMERA_LENS_FACING_EXTERNAL)
		{
			if (strcmp(deviceId + 9, id) == 0)
			{
				match = id;
				break;
			}
		}
		else if (strcmp(deviceId, "external") == 0 && facing == ACAMERA_LENS_FACING_EXTERNAL)
		{
			/* Caller asked for the first external camera. */
			match = id;
			break;
		}
	}

	*outList = list; /* caller frees */
	return match;
}

/*
 * ICamHal::Enumerate
 */
static UINT cam_android_enumerate(ICamHal* ihal, ICamHalEnumCallback callback,
                                  CameraPlugin* ecam, GENERIC_CHANNEL_CALLBACK* hchannel)
{
	WINPR_UNUSED(ihal);
	UINT rc = CHANNEL_RC_OK;

	ACameraManager* mgr = ACameraManager_create();
	if (!mgr)
	{
		WLog_WARN(TAG, "ACameraManager_create failed");
	}
	else
	{
		int camera2Count = 0;
		ACameraIdList* list = NULL;
		if (ACameraManager_getCameraIdList(mgr, &list) == ACAMERA_OK && list)
		{
			for (int i = 0; i < list->numCameras; ++i)
			{
				const char* id = list->cameraIds[i];
				ACameraMetadata* meta = NULL;
				if (ACameraManager_getCameraCharacteristics(mgr, id, &meta) != ACAMERA_OK || !meta)
					continue;
				ACameraMetadata_const_entry e;
				uint8_t facing = 0;
				if (ACameraMetadata_getConstEntry(meta, ACAMERA_LENS_FACING, &e) == ACAMERA_OK &&
				    e.count > 0)
					facing = e.data.u8[0];
				ACameraMetadata_free(meta);

				const char* devId = NULL;
				const char* label = NULL;
				char extBuf[48];
				switch (facing)
				{
					case ACAMERA_LENS_FACING_FRONT:
						devId = "front";
						label = "Front Camera";
						break;
					case ACAMERA_LENS_FACING_BACK:
						devId = "back";
						label = "Back Camera";
						break;
					case ACAMERA_LENS_FACING_EXTERNAL:
						snprintf(extBuf, sizeof(extBuf), "external:%s", id);
						devId = extBuf;
						label = "External Camera";
						break;
					default:
						continue;
				}
				camera2Count++;
				UINT cb = callback(ecam, hchannel, devId, label);
				if (cb != CHANNEL_RC_OK)
				{
					rc = cb;
					/* Keep enumerating; one failed report shouldn't drop the rest. */
				}
			}
			ACameraManager_deleteCameraIdList(list);
		}
		WLog_INFO(TAG, "cam_android_enumerate: %d Camera2 device(s) found", camera2Count);
		ACameraManager_delete(mgr);
	}

	/* Append virtual UVC devices announced via the bridge. */
	if (g_cam_android_hal && g_cam_android_hal->bridgeRegistry)
	{
		pthread_mutex_lock(&g_cam_android_hal->lock);
		ULONG_PTR* keys = NULL;
		size_t n = HashTable_GetKeys(g_cam_android_hal->bridgeRegistry, &keys);
		for (size_t i = 0; i < n; ++i)
		{
			const char* devId = (const char*)keys[i];
			CamAndroidBridgeEntry* entry =
			    (CamAndroidBridgeEntry*)HashTable_GetItemValue(g_cam_android_hal->bridgeRegistry,
			                                                   (const void*)devId);
			const char* label = entry ? entry->label : devId;
			UINT cb = callback(ecam, hchannel, devId, label);
			if (cb != CHANNEL_RC_OK)
				rc = cb;
		}
		free(keys);
		pthread_mutex_unlock(&g_cam_android_hal->lock);
	}
	return rc;
}

/*
 * ICamHal::GetMediaTypeDescriptions
 *
 * Advertise a single 720p30 capability at the format the Android HAL can actually
 * deliver: H.264 passthrough (AMediaCodec encoder path) preferred, NV12 raw as
 * fallback (AImageReader path; the channel layer re-encodes). Returns the index
 * into supportedFormats[] that corresponds to the chosen input format, as required
 * by the ICamHal contract. Returning the count rather than an index is an
 * out-of-bounds read when the caller does supportedFormats[formatIndex].
 */
static INT16 cam_android_get_media_type_descriptions(ICamHal* ihal, const char* deviceId,
                                                     int streamIndex,
                                                     const CAM_MEDIA_FORMAT_INFO* supportedFormats,
                                                     size_t nSupportedFormats,
                                                     CAM_MEDIA_TYPE_DESCRIPTION* mediaTypes,
                                                     size_t* nMediaTypes)
{
	WINPR_UNUSED(ihal);
	WINPR_UNUSED(deviceId);
	WINPR_UNUSED(streamIndex);
	if (!mediaTypes || !nMediaTypes || !supportedFormats || nSupportedFormats == 0 ||
	    *nMediaTypes < 1)
		return -1;

	/* Pick the first format in priority order that the HAL can deliver.
	 * Camera2 NDK gives us either H264 (encoder path) or NV12 (raw path). */
	static const CAM_MEDIA_FORMAT preferred[] = { CAM_MEDIA_FORMAT_H264, CAM_MEDIA_FORMAT_NV12 };
	INT16 bestIdx = -1;
	for (size_t p = 0; p < ARRAYSIZE(preferred) && bestIdx < 0; ++p)
	{
		for (INT16 i = 0; i < (INT16)nSupportedFormats; ++i)
		{
			if (supportedFormats[i].inputFormat == preferred[p])
			{
				bestIdx = i;
				break;
			}
		}
	}
	if (bestIdx < 0)
		return -1;

	CAM_MEDIA_TYPE_DESCRIPTION* d = &mediaTypes[0];
	memset(d, 0, sizeof(*d));
	d->Format = supportedFormats[bestIdx].outputFormat;
	d->Width = 1280;
	d->Height = 720;
	d->FrameRateNumerator = 30;
	d->FrameRateDenominator = 1;
	d->PixelAspectRatioNumerator = 1;
	d->PixelAspectRatioDenominator = 1;
	*nMediaTypes = 1;
	return bestIdx;
}

/*
 * ICamHal::StartStream — dispatches to the right backend based on device ID format.
 */
static UINT cam_android_start_stream(ICamHal* ihal, CameraDevice* dev, int streamIndex,
                                     const CAM_MEDIA_TYPE_DESCRIPTION* mediaType,
                                     ICamHalSampleCapturedCallback callback)
{
	CamAndroidHal* hal = (CamAndroidHal*)ihal;
	if (!hal || !dev || !mediaType || !callback)
		return ERROR_INVALID_PARAMETER;

	CamAndroidStream* s = (CamAndroidStream*)calloc(1, sizeof(*s));
	if (!s)
		return CHANNEL_RC_NO_MEMORY;
	pthread_mutex_init(&s->lock, NULL);
	s->dev = dev;
	s->streamIndex = streamIndex;
	s->callback = callback;
	s->mediaType = *mediaType;

	if (strncmp(dev->deviceId, "usb:", 4) == 0)
		s->backend = CAM_ANDROID_BACKEND_BRIDGE;
	else
		s->backend = CAM_ANDROID_BACKEND_NDK;

	pthread_mutex_lock(&hal->lock);
	if (HashTable_GetItemValue(hal->streams, dev->deviceId))
	{
		pthread_mutex_unlock(&hal->lock);
		WLog_WARN(TAG, "stream already active for %s", dev->deviceId);
		cam_android_stream_free(s);
		return ERROR_ALREADY_EXISTS;
	}
	HashTable_Insert(hal->streams, dev->deviceId, s);
	pthread_mutex_unlock(&hal->lock);

	UINT rc = (s->backend == CAM_ANDROID_BACKEND_NDK) ? cam_android_capture_start(s)
	                                                  : cam_android_bridge_start(s);
	if (rc != CHANNEL_RC_OK)
	{
		pthread_mutex_lock(&hal->lock);
		HashTable_Remove(hal->streams, dev->deviceId);
		pthread_mutex_unlock(&hal->lock);
	}
	else
	{
		s->streaming = TRUE;
	}
	return rc;
}

/*
 * ICamHal::StopStream
 */
static UINT cam_android_stop_stream(ICamHal* ihal, const char* deviceId, int streamIndex)
{
	WINPR_UNUSED(streamIndex);
	CamAndroidHal* hal = (CamAndroidHal*)ihal;
	if (!hal || !deviceId)
		return ERROR_INVALID_PARAMETER;

	pthread_mutex_lock(&hal->lock);
	CamAndroidStream* s = (CamAndroidStream*)HashTable_GetItemValue(hal->streams, deviceId);
	if (s)
		HashTable_Remove(hal->streams, deviceId);
	pthread_mutex_unlock(&hal->lock);
	if (!s)
		return CHANNEL_RC_OK;
	cam_android_stream_free(s);
	return CHANNEL_RC_OK;
}

/*
 * ICamHal::Free
 */
static UINT cam_android_free(ICamHal* ihal)
{
	CamAndroidHal* hal = (CamAndroidHal*)ihal;
	if (!hal)
		return ERROR_INVALID_PARAMETER;
	if (hal == g_cam_android_hal)
		g_cam_android_hal = NULL;
	HashTable_Free(hal->streams);
	HashTable_Free(hal->bridgeRegistry);
	pthread_mutex_destroy(&hal->lock);
	free(hal);
	return CHANNEL_RC_OK;
}

/* Forward sample. Both backends call this with whichever bytes they have ready.
 * The channel will route the sample through its encoder pipeline (raw → H.264 if
 * needed) before sending. */
UINT cam_android_submit_frame(CamAndroidStream* stream, const BYTE* data, size_t size)
{
	if (!stream || !data || !size || !stream->callback)
		return ERROR_INVALID_PARAMETER;
	if (!stream->streaming)
		return CHANNEL_RC_OK; /* drop silently between StopStream and final teardown */
	return stream->callback(stream->dev, stream->streamIndex, data, size);
}

/*
 * Subsystem entry point. Naming is dictated by FreeRDP's
 * `add_channel_client_subsystem` macro: <subsystem>_freerdp_<channel>_client_subsystem_entry.
 */
FREERDP_ENTRY_POINT(UINT VCAPITYPE android_freerdp_rdpecam_client_subsystem_entry(
    PFREERDP_CAMERA_HAL_ENTRY_POINTS pEntryPoints))
{
	WINPR_ASSERT(pEntryPoints);

	CamAndroidHal* hal = (CamAndroidHal*)calloc(1, sizeof(*hal));
	if (!hal)
		return CHANNEL_RC_NO_MEMORY;

	pthread_mutex_init(&hal->lock, NULL);
	hal->iHal.Enumerate = cam_android_enumerate;
	hal->iHal.GetMediaTypeDescriptions = cam_android_get_media_type_descriptions;
	hal->iHal.StartStream = cam_android_start_stream;
	hal->iHal.StopStream = cam_android_stop_stream;
	hal->iHal.Free = cam_android_free;

	hal->streams = HashTable_New(FALSE);
	hal->bridgeRegistry = HashTable_New(FALSE);
	if (!hal->streams || !hal->bridgeRegistry)
	{
		cam_android_free(&hal->iHal);
		return CHANNEL_RC_NO_MEMORY;
	}
	HashTable_SetupForStringData(hal->streams, FALSE);
	HashTable_SetupForStringData(hal->bridgeRegistry, FALSE);
	{
		wObject* o = HashTable_ValueObject(hal->streams);
		if (o)
			o->fnObjectFree = cam_android_stream_free;
	}
	{
		wObject* o = HashTable_ValueObject(hal->bridgeRegistry);
		if (o)
			o->fnObjectFree = cam_android_bridge_entry_free;
	}

	UINT rc = pEntryPoints->pRegisterCameraHal(pEntryPoints->plugin, &hal->iHal);
	if (rc != CHANNEL_RC_OK)
	{
		WLog_ERR(TAG, "RegisterCameraHal failed: 0x%08" PRIX32, rc);
		cam_android_free(&hal->iHal);
		return rc;
	}
	g_cam_android_hal = hal;
	return CHANNEL_RC_OK;
}

/* Helper exposed to the bridge code (it doesn't link directly against the
 * camera2ndk side). Returns the camera2 string id or NULL. */
const char* cam_android_resolve_id(const char* deviceId, ACameraManager* mgr,
                                   ACameraIdList** outList)
{
	return cam_android_resolve_camera2_id(mgr, deviceId, outList);
}
