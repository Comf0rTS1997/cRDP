/**
 * cRDP / FreeRDP — Android rdpecam HAL: NDK Camera2 capture backend.
 *
 * Two output paths, picked by the negotiated CAM_MEDIA_FORMAT:
 *
 *   1. H.264 passthrough (default, when the server requests it). The encoder is
 *      AMediaCodec configured with an input surface; Camera2 writes directly into
 *      the encoder, GPU does YUV→encoder colour conversion, a drain thread pulls
 *      Annex-B NAL units off the codec output and forwards them via
 *      cam_android_submit_frame(). SPS/PPS arrive as a CSD prefix in the first
 *      output buffer and are forwarded as-is.
 *
 *   2. Raw YUV (NV12). Camera2 writes into an AImageReader; each frame is packed
 *      from YUV_420_888 to NV12 and forwarded raw. Used when the server asked
 *      for an uncompressed format. The channel layer encodes upstream of this
 *      callback when a transport-side codec is needed.
 *
 * Threading: Camera2 NDK callbacks fire on a looper thread the SDK creates per
 * session. The encoder drain runs on its own pthread to keep dequeue latency off
 * that thread. Each AImageReader frame holds its own ownership through
 * AImage_delete; encoder output buffers are released back via
 * AMediaCodec_releaseOutputBuffer.
 */

#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <stdatomic.h>
#include <pthread.h>
#include <unistd.h>

#include <android/log.h>
#include <android/native_window.h>
#include <camera/NdkCameraManager.h>
#include <camera/NdkCameraDevice.h>
#include <camera/NdkCameraCaptureSession.h>
#include <camera/NdkCameraMetadata.h>
#include <camera/NdkCaptureRequest.h>
#include <media/NdkImage.h>
#include <media/NdkImageReader.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>

#include "camera_android.h"

#define TAG ANDROID_CAM_TAG

extern const char* cam_android_resolve_id(const char* deviceId, ACameraManager* mgr,
                                          ACameraIdList** outList);

/* CAM_MEDIA_FORMAT enum values from rdpecam.h. We avoid hard-coding integers
 * by referring to the symbol names; the channel header is included transitively
 * through camera.h → freerdp/channels/rdpecam.h. */

typedef enum
{
	CAM_PATH_H264_ENCODER, /* AMediaCodec input-surface path */
	CAM_PATH_RAW_YUV,      /* AImageReader path */
} CamCapturePath;

typedef struct
{
	CamCapturePath path;

	/* Camera2 NDK objects (always present) */
	ACameraManager* mgr;
	ACameraDevice* device;
	ACameraCaptureSession* session;
	ACaptureSessionOutputContainer* outContainer;
	ACaptureSessionOutput* sessionOut;
	ACameraOutputTarget* target;
	ACaptureRequest* request;
	ANativeWindow* captureWindow; /* whichever surface Camera2 writes into */

	/* H.264 encoder path */
	AMediaCodec* encoder;
	pthread_t drainThread;
	atomic_int drainStop;
	atomic_int drainRunning;

	/* Raw YUV path */
	AImageReader* reader;
	BYTE* nv12Buf;
	size_t nv12Cap;
} CamCaptureState;

/* ---------- Raw YUV path ---------- */

static void on_image_available(void* ctx, AImageReader* reader)
{
	CamAndroidStream* stream = (CamAndroidStream*)ctx;
	if (!stream || !reader)
		return;
	CamCaptureState* st = (CamCaptureState*)stream->state;
	if (!st)
		return;

	AImage* image = NULL;
	if (AImageReader_acquireLatestImage(reader, &image) != AMEDIA_OK || !image)
		return;

	int32_t format = 0;
	int32_t width = 0;
	int32_t height = 0;
	AImage_getFormat(image, &format);
	AImage_getWidth(image, &width);
	AImage_getHeight(image, &height);
	if (format != AIMAGE_FORMAT_YUV_420_888 || width <= 0 || height <= 0)
	{
		AImage_delete(image);
		return;
	}

	const size_t need = (size_t)width * (size_t)height * 3 / 2;
	if (st->nv12Cap < need)
	{
		BYTE* nb = (BYTE*)realloc(st->nv12Buf, need);
		if (!nb)
		{
			AImage_delete(image);
			return;
		}
		st->nv12Buf = nb;
		st->nv12Cap = need;
	}

	uint8_t* yPlane = NULL;
	uint8_t* uPlane = NULL;
	uint8_t* vPlane = NULL;
	int yLen = 0, uLen = 0, vLen = 0;
	int yRowStride = 0, uvRowStride = 0, uvPixelStride = 0;
	AImage_getPlaneData(image, 0, &yPlane, &yLen);
	AImage_getPlaneData(image, 1, &uPlane, &uLen);
	AImage_getPlaneData(image, 2, &vPlane, &vLen);
	AImage_getPlaneRowStride(image, 0, &yRowStride);
	AImage_getPlaneRowStride(image, 1, &uvRowStride);
	AImage_getPlanePixelStride(image, 1, &uvPixelStride);

	BYTE* dstY = st->nv12Buf;
	for (int row = 0; row < height; ++row)
		memcpy(dstY + row * width, yPlane + row * yRowStride, (size_t)width);

	BYTE* dstUV = st->nv12Buf + (size_t)width * (size_t)height;
	const int uvHeight = height / 2;
	const int uvWidth = width / 2;
	for (int row = 0; row < uvHeight; ++row)
	{
		uint8_t* srcU = uPlane + row * uvRowStride;
		uint8_t* srcV = vPlane + row * uvRowStride;
		BYTE* dst = dstUV + row * width;
		for (int x = 0; x < uvWidth; ++x)
		{
			dst[2 * x + 0] = srcU[x * uvPixelStride];
			dst[2 * x + 1] = srcV[x * uvPixelStride];
		}
	}
	cam_android_submit_frame(stream, st->nv12Buf, need);
	AImage_delete(image);
}

/* ---------- H.264 encoder path ---------- */

static void* encoder_drain_thread(void* arg)
{
	CamAndroidStream* stream = (CamAndroidStream*)arg;
	if (!stream)
		return NULL;
	CamCaptureState* st = (CamCaptureState*)stream->state;
	if (!st || !st->encoder)
		return NULL;

	atomic_store(&st->drainRunning, 1);
	while (!atomic_load(&st->drainStop))
	{
		AMediaCodecBufferInfo info;
		memset(&info, 0, sizeof(info));
		ssize_t idx = AMediaCodec_dequeueOutputBuffer(st->encoder, &info, 10000 /* 10ms */);
		if (idx >= 0)
		{
			size_t outSize = 0;
			uint8_t* out = AMediaCodec_getOutputBuffer(st->encoder, (size_t)idx, &outSize);
			if (out && info.size > 0)
			{
				/* AMediaCodec emits Annex-B (start-code-prefixed) NAL units. The
				 * very first output buffer carries the codec-specific data
				 * (SPS+PPS) with AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG; we forward
				 * it on the wire so the decoder gets the parameter sets before
				 * any sample frames. The channel doesn't care — it treats the
				 * payload as opaque H.264 bytes. */
				cam_android_submit_frame(stream, out + info.offset, (size_t)info.size);
			}
			AMediaCodec_releaseOutputBuffer(st->encoder, (size_t)idx, false);
			if (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM)
				break;
		}
		else if (idx == AMEDIACODEC_INFO_TRY_AGAIN_LATER)
		{
			/* No output ready — keep looping. The 10ms dequeue timeout handles backoff. */
		}
		else if (idx == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED)
		{
			/* Could fetch new csd-0/csd-1 here. With Annex-B output the codec
			 * emits SPS/PPS as a regular CODEC_CONFIG buffer, so this branch is
			 * effectively a no-op for our purposes. */
		}
		else if (idx == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED)
		{
			/* Older API quirk; safe to ignore. */
		}
		else
		{
			/* Negative status: rare codec error. Sleep a tick rather than spin. */
			usleep(2000);
		}
	}
	atomic_store(&st->drainRunning, 0);
	return NULL;
}

/* ---------- Camera2 device callbacks ---------- */

static void on_device_error(void* ctx, ACameraDevice* dev, int err)
{
	WINPR_UNUSED(ctx);
	WINPR_UNUSED(dev);
	WLog_WARN(TAG, "camera device error %d", err);
}
static void on_device_disconnected(void* ctx, ACameraDevice* dev)
{
	WINPR_UNUSED(ctx);
	WINPR_UNUSED(dev);
}
static void on_session_active(void* ctx, ACameraCaptureSession* s)
{
	WINPR_UNUSED(ctx);
	WINPR_UNUSED(s);
}
static void on_session_ready(void* ctx, ACameraCaptureSession* s)
{
	WINPR_UNUSED(ctx);
	WINPR_UNUSED(s);
}
static void on_session_closed(void* ctx, ACameraCaptureSession* s)
{
	WINPR_UNUSED(ctx);
	WINPR_UNUSED(s);
}

/* ---------- Teardown ---------- */

static void capture_state_free(CamCaptureState* st)
{
	if (!st)
		return;

	if (st->encoder)
	{
		atomic_store(&st->drainStop, 1);
		/* Signal EOS so the encoder flushes; the drain loop will see EOS and exit. */
		AMediaCodec_signalEndOfInputStream(st->encoder);
	}
	if (st->session)
	{
		ACameraCaptureSession_close(st->session);
		st->session = NULL;
	}
	if (st->request)
	{
		ACaptureRequest_free(st->request);
		st->request = NULL;
	}
	if (st->target)
	{
		ACameraOutputTarget_free(st->target);
		st->target = NULL;
	}
	if (st->sessionOut)
	{
		ACaptureSessionOutput_free(st->sessionOut);
		st->sessionOut = NULL;
	}
	if (st->outContainer)
	{
		ACaptureSessionOutputContainer_free(st->outContainer);
		st->outContainer = NULL;
	}
	if (st->encoder)
	{
		/* Wait briefly for the drain thread to exit before stopping/releasing. */
		for (int i = 0; i < 50 && atomic_load(&st->drainRunning); ++i)
			usleep(2000);
		pthread_join(st->drainThread, NULL);
		AMediaCodec_stop(st->encoder);
		AMediaCodec_delete(st->encoder);
		st->encoder = NULL;
	}
	if (st->reader)
	{
		AImageReader_delete(st->reader);
		st->reader = NULL;
	}
	if (st->captureWindow)
	{
		/* The captureWindow is owned by either the encoder (input surface) or
		 * the AImageReader; freed via the owner above. Don't release here. */
		st->captureWindow = NULL;
	}
	if (st->device)
	{
		ACameraDevice_close(st->device);
		st->device = NULL;
	}
	if (st->mgr)
	{
		ACameraManager_delete(st->mgr);
		st->mgr = NULL;
	}
	free(st->nv12Buf);
	free(st);
}

/* ---------- Encoder bring-up helper ---------- */

static UINT setup_h264_encoder(CamAndroidStream* stream, CamCaptureState* st, int width, int height,
                               int fps)
{
	st->encoder = AMediaCodec_createEncoderByType("video/avc");
	if (!st->encoder)
	{
		WLog_WARN(TAG, "AMediaCodec_createEncoderByType(video/avc) failed");
		return ERROR_NOT_SUPPORTED;
	}

	AMediaFormat* fmt = AMediaFormat_new();
	AMediaFormat_setString(fmt, AMEDIAFORMAT_KEY_MIME, "video/avc");
	AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_WIDTH, width);
	AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_HEIGHT, height);
	AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_FRAME_RATE, fps > 0 ? fps : 30);
	AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_I_FRAME_INTERVAL, 1);
	/* MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface — mandatory when the
	 * encoder is fed via createInputSurface (Camera2 writes pixels via the GPU). */
	AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_COLOR_FORMAT, 0x7F000789);
	/* Bitrate ≈ resolution × fps × 0.1 bpp (low-motion baseline; codec ABRs from there). */
	const int32_t bitrate = (int32_t)((int64_t)width * height * (fps > 0 ? fps : 30) / 10);
	AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_BIT_RATE, bitrate);

	media_status_t r = AMediaCodec_configure(st->encoder, fmt, NULL, NULL,
	                                         AMEDIACODEC_CONFIGURE_FLAG_ENCODE);
	AMediaFormat_delete(fmt);
	if (r != AMEDIA_OK)
	{
		WLog_WARN(TAG, "AMediaCodec_configure failed: %d", r);
		return ERROR_NOT_SUPPORTED;
	}

	r = AMediaCodec_createInputSurface(st->encoder, &st->captureWindow);
	if (r != AMEDIA_OK || !st->captureWindow)
	{
		WLog_WARN(TAG, "AMediaCodec_createInputSurface failed: %d", r);
		return ERROR_NOT_SUPPORTED;
	}
	if (AMediaCodec_start(st->encoder) != AMEDIA_OK)
	{
		WLog_WARN(TAG, "AMediaCodec_start failed");
		return ERROR_NOT_SUPPORTED;
	}

	atomic_init(&st->drainStop, 0);
	atomic_init(&st->drainRunning, 0);
	if (pthread_create(&st->drainThread, NULL, encoder_drain_thread, stream) != 0)
	{
		WLog_WARN(TAG, "encoder drain thread spawn failed");
		return ERROR_INTERNAL_ERROR;
	}
	return CHANNEL_RC_OK;
}

static UINT setup_yuv_reader(CamAndroidStream* stream, CamCaptureState* st, int width, int height)
{
	if (AImageReader_new(width, height, AIMAGE_FORMAT_YUV_420_888, /*maxImages*/ 4, &st->reader) !=
	    AMEDIA_OK)
		return ERROR_INTERNAL_ERROR;
	AImageReader_ImageListener listener = { .context = stream,
		                                    .onImageAvailable = on_image_available };
	AImageReader_setImageListener(st->reader, &listener);
	if (AImageReader_getWindow(st->reader, &st->captureWindow) != AMEDIA_OK)
		return ERROR_INTERNAL_ERROR;
	return CHANNEL_RC_OK;
}

/* ---------- Public entry: StartStream ---------- */

UINT cam_android_capture_start(CamAndroidStream* stream)
{
	if (!stream || !stream->dev)
		return ERROR_INVALID_PARAMETER;

	CamCaptureState* st = (CamCaptureState*)calloc(1, sizeof(*st));
	if (!st)
		return CHANNEL_RC_NO_MEMORY;

	const int32_t w = stream->mediaType.Width > 0 ? (int32_t)stream->mediaType.Width : 1280;
	const int32_t h = stream->mediaType.Height > 0 ? (int32_t)stream->mediaType.Height : 720;
	const int32_t fps =
	    (stream->mediaType.FrameRateNumerator > 0 && stream->mediaType.FrameRateDenominator > 0)
	        ? (int32_t)(stream->mediaType.FrameRateNumerator /
	                    stream->mediaType.FrameRateDenominator)
	        : 30;

	/* Pick the path. CAM_MEDIA_FORMAT_H264 == 0x01 per [MS-RDPECAM] §2.2.2.4. */
	st->path = (stream->mediaType.Format == CAM_MEDIA_FORMAT_H264) ? CAM_PATH_H264_ENCODER
	                                                               : CAM_PATH_RAW_YUV;

	st->mgr = ACameraManager_create();
	if (!st->mgr)
	{
		capture_state_free(st);
		return ERROR_INTERNAL_ERROR;
	}

	ACameraIdList* list = NULL;
	const char* nativeId = cam_android_resolve_id(stream->dev->deviceId, st->mgr, &list);
	if (!nativeId)
	{
		WLog_WARN(TAG, "camera %s not present on device", stream->dev->deviceId);
		if (list)
			ACameraManager_deleteCameraIdList(list);
		capture_state_free(st);
		return ERROR_NOT_FOUND;
	}

	UINT setupRc =
	    (st->path == CAM_PATH_H264_ENCODER) ? setup_h264_encoder(stream, st, w, h, fps)
	                                        : setup_yuv_reader(stream, st, w, h);
	if (setupRc != CHANNEL_RC_OK)
	{
		if (list)
			ACameraManager_deleteCameraIdList(list);
		/* Fall back to raw YUV if encoder setup failed (some devices lack a usable
		 * hardware H.264 encoder; the channel will transcode upstream). */
		if (st->path == CAM_PATH_H264_ENCODER)
		{
			WLog_WARN(TAG, "H.264 encoder unavailable; falling back to raw YUV path");
			st->path = CAM_PATH_RAW_YUV;
			if (setup_yuv_reader(stream, st, w, h) != CHANNEL_RC_OK)
			{
				capture_state_free(st);
				return ERROR_INTERNAL_ERROR;
			}
		}
		else
		{
			capture_state_free(st);
			return setupRc;
		}
	}

	ACameraDevice_StateCallbacks devCb = { .context = stream,
		                                   .onDisconnected = on_device_disconnected,
		                                   .onError = on_device_error };
	if (ACameraManager_openCamera(st->mgr, nativeId, &devCb, &st->device) != ACAMERA_OK)
	{
		WLog_WARN(TAG, "openCamera failed for %s (CAMERA permission?)", nativeId);
		if (list)
			ACameraManager_deleteCameraIdList(list);
		capture_state_free(st);
		return ERROR_ACCESS_DENIED;
	}
	if (list)
		ACameraManager_deleteCameraIdList(list);

	if (ACameraDevice_createCaptureRequest(st->device, TEMPLATE_RECORD, &st->request) !=
	    ACAMERA_OK)
	{
		capture_state_free(st);
		return ERROR_INTERNAL_ERROR;
	}
	if (ACameraOutputTarget_create(st->captureWindow, &st->target) != ACAMERA_OK ||
	    ACaptureRequest_addTarget(st->request, st->target) != ACAMERA_OK)
	{
		capture_state_free(st);
		return ERROR_INTERNAL_ERROR;
	}
	if (ACaptureSessionOutputContainer_create(&st->outContainer) != ACAMERA_OK ||
	    ACaptureSessionOutput_create(st->captureWindow, &st->sessionOut) != ACAMERA_OK ||
	    ACaptureSessionOutputContainer_add(st->outContainer, st->sessionOut) != ACAMERA_OK)
	{
		capture_state_free(st);
		return ERROR_INTERNAL_ERROR;
	}
	ACameraCaptureSession_stateCallbacks sessCb = { .context = stream,
		                                            .onActive = on_session_active,
		                                            .onReady = on_session_ready,
		                                            .onClosed = on_session_closed };
	if (ACameraDevice_createCaptureSession(st->device, st->outContainer, &sessCb, &st->session) !=
	    ACAMERA_OK)
	{
		capture_state_free(st);
		return ERROR_INTERNAL_ERROR;
	}
	ACaptureRequest* requests[1] = { st->request };
	if (ACameraCaptureSession_setRepeatingRequest(st->session, NULL, 1, requests, NULL) !=
	    ACAMERA_OK)
	{
		capture_state_free(st);
		return ERROR_INTERNAL_ERROR;
	}

	stream->state = st;
	WLog_INFO(TAG, "Camera2 capture: device=%s native=%s %dx%d %dfps path=%s",
	          stream->dev->deviceId, nativeId, w, h, fps,
	          st->path == CAM_PATH_H264_ENCODER ? "H264" : "NV12");
	return CHANNEL_RC_OK;
}

void cam_android_capture_stop(CamAndroidStream* stream)
{
	if (!stream)
		return;
	CamCaptureState* st = (CamCaptureState*)stream->state;
	stream->state = NULL;
	stream->streaming = FALSE;
	capture_state_free(st);
}
