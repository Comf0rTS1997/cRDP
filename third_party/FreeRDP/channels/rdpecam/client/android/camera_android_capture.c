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

	/* H.264 encoder. The encoder is fed manually with NV12 bytes copied from
	 * the AImageReader frame — we deliberately avoid AMediaCodec_createInputSurface,
	 * because that path makes the camera write directly into a SurfaceTexture
	 * the encoder owns, which Samsung CamX (S25 family) refuses ("Invalid
	 * Camera buffer [0x0]" / ERROR_CAMERA_SERVICE). Manual feeding works
	 * everywhere. */
	AMediaCodec* encoder;
	pthread_t drainThread;
	atomic_int drainStop;
	atomic_int drainRunning;
	int encW;
	int encH;
	int64_t encPtsUs; /* monotonically-increasing 1/fps ticks */
	int encFps;

	/* Raw YUV path */
	AImageReader* reader;
	BYTE* nv12Buf;
	size_t nv12Cap;

	/* Rotation / mirroring for built-in cameras.
	 *   rotationDeg : 0 / 90 / 180 / 270, applied CW to the raw NV12 frame.
	 *   mirrorH     : horizontal flip (front cameras face the user; without
	 *                 mirroring, text and gestures look reversed).
	 *   rawW/rawH   : dimensions of the raw NV12 from AImageReader.
	 *   outW/outH   : dimensions handed to the encoder. Swapped when
	 *                 rotationDeg is 90 or 270.
	 *   rotBuf      : destination buffer when a rotation/mirror is needed.
	 *                 Allocated lazily on first frame. Lifetime = capture
	 *                 session. */
	int rotationDeg;
	BOOL mirrorH;
	int rawW, rawH;
	int outW, outH;
	BYTE* rotBuf;
	size_t rotCap;

	/* Incremented per delivered AImage. feed_nv12_to_encoder uses it to throttle
	 * the forced sync-frame request (every ~60 frames / ~2s @ 30fps). */
	int frameCount;
} CamCaptureState;

/* Forward declarations */
static UINT feed_nv12_to_encoder(CamAndroidStream* stream, CamCaptureState* st, const BYTE* nv12,
                                 size_t size);

/* ---------- NV12 rotate / mirror ---------- *
 *
 * Transforms an NV12 frame in place from sensor → display orientation, with
 * optional horizontal mirror (used for front-facing cameras so the user sees
 * a "mirror image" rather than a left/right-reversed one).
 *
 * The Y plane is `srcW * srcH` bytes. The interleaved UV plane is
 * `srcW * srcH / 2` bytes with chroma sub-sampled 2×2 — each (cb, cr) pair
 * covers a 2×2 Y block. The rotation moves whole 2×2 blocks so chroma
 * stays aligned with luma.
 *
 * Output dimensions:
 *   rotationDeg ∈ {0, 180} : (srcW, srcH)
 *   rotationDeg ∈ {90, 270}: (srcH, srcW)  — caller must size `dst` for the
 *                                            swapped geometry.
 *
 * Horizontal mirror is applied AFTER rotation. (rotation 90/270 + mirror
 * together is the standard "front camera selfie" transform that matches
 * what apps like Camera2 expect.)
 */
static void rotate_mirror_nv12(const BYTE* src, int srcW, int srcH, BYTE* dst, int rotationDeg,
                               BOOL mirrorH)
{
	const BYTE* srcY = src;
	const BYTE* srcUV = src + (size_t)srcW * (size_t)srcH;
	int dstW = (rotationDeg == 90 || rotationDeg == 270) ? srcH : srcW;
	int dstH = (rotationDeg == 90 || rotationDeg == 270) ? srcW : srcH;
	BYTE* dstY = dst;
	BYTE* dstUV = dst + (size_t)dstW * (size_t)dstH;

	for (int y = 0; y < srcH; ++y)
	{
		for (int x = 0; x < srcW; ++x)
		{
			int nx, ny;
			switch (rotationDeg)
			{
				case 90:
					nx = srcH - 1 - y;
					ny = x;
					break;
				case 180:
					nx = srcW - 1 - x;
					ny = srcH - 1 - y;
					break;
				case 270:
					nx = y;
					ny = srcW - 1 - x;
					break;
				default:
					nx = x;
					ny = y;
					break;
			}
			if (mirrorH)
				nx = dstW - 1 - nx;
			dstY[ny * dstW + nx] = srcY[y * srcW + x];
		}
	}

	const int srcUVRows = srcH / 2;
	const int srcUVCols = srcW / 2;
	for (int uy = 0; uy < srcUVRows; ++uy)
	{
		for (int ux = 0; ux < srcUVCols; ++ux)
		{
			int nux, nuy;
			switch (rotationDeg)
			{
				case 90:
					nux = srcUVRows - 1 - uy;
					nuy = ux;
					break;
				case 180:
					nux = srcUVCols - 1 - ux;
					nuy = srcUVRows - 1 - uy;
					break;
				case 270:
					nux = uy;
					nuy = srcUVCols - 1 - ux;
					break;
				default:
					nux = ux;
					nuy = uy;
					break;
			}
			int dstUVCols = dstW / 2;
			if (mirrorH)
				nux = dstUVCols - 1 - nux;
			const BYTE* sp = srcUV + ((size_t)uy * srcUVCols + ux) * 2;
			BYTE* dp = dstUV + ((size_t)nuy * dstUVCols + nux) * 2;
			dp[0] = sp[0]; /* Cb */
			dp[1] = sp[1]; /* Cr */
		}
	}
}

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

	/* Bumped per delivered frame; used by feed_nv12_to_encoder to request a
	 * forced sync frame every ~2 seconds (see comment in that function). */
	st->frameCount++;

	/* If a rotation or mirror is needed (built-in cameras only), apply it
	 * into rotBuf and feed that to the encoder instead of nv12Buf. */
	const BYTE* feedBuf = st->nv12Buf;
	size_t feedSize = need;
	if (st->rotationDeg != 0 || st->mirrorH)
	{
		const size_t rotNeed = (size_t)st->outW * (size_t)st->outH * 3 / 2;
		if (st->rotCap < rotNeed)
		{
			BYTE* nb = (BYTE*)realloc(st->rotBuf, rotNeed);
			if (!nb)
			{
				AImage_delete(image);
				return;
			}
			st->rotBuf = nb;
			st->rotCap = rotNeed;
		}
		rotate_mirror_nv12(st->nv12Buf, width, height, st->rotBuf, st->rotationDeg, st->mirrorH);
		feedBuf = st->rotBuf;
		feedSize = rotNeed;
	}

	feed_nv12_to_encoder(stream, st, feedBuf, feedSize);
	AImage_delete(image);
}

/* Pulls an input buffer off the encoder, copies the NV12 frame into it (the
 * encoder is configured with COLOR_FormatYUV420SemiPlanar = NV12), and queues
 * it with a monotonic 1/fps timestamp. Drops the frame if no input buffer is
 * available within 5ms — better to drop a frame than block the AImageReader
 * listener thread and stall the camera. */
static UINT feed_nv12_to_encoder(CamAndroidStream* stream, CamCaptureState* st, const BYTE* nv12,
                                 size_t size)
{
	if (!stream || !st || !st->encoder || !nv12 || !size)
		return ERROR_INVALID_PARAMETER;

	ssize_t idx = AMediaCodec_dequeueInputBuffer(st->encoder, 5000 /* 5ms */);
	if (idx < 0)
		return CHANNEL_RC_OK; /* no buffer; drop this frame */

	size_t bufSize = 0;
	uint8_t* buf = AMediaCodec_getInputBuffer(st->encoder, (size_t)idx, &bufSize);
	if (!buf || bufSize < size)
	{
		/* Surrender the buffer back without enqueuing a frame. */
		AMediaCodec_queueInputBuffer(st->encoder, (size_t)idx, 0, 0, 0, 0);
		return ERROR_INTERNAL_ERROR;
	}
	memcpy(buf, nv12, size);

	const int fps = st->encFps > 0 ? st->encFps : 30;
	const int64_t step = 1000000 / fps;
	st->encPtsUs += step;
	AMediaCodec_queueInputBuffer(st->encoder, (size_t)idx, 0, size, st->encPtsUs, 0);

	/* Force a sync frame (SPS/PPS + IDR) every ~2 seconds. The encoder's
	 * AMEDIAFORMAT_KEY_I_FRAME_INTERVAL=1 already requests one keyframe per
	 * second, but if a single keyframe drops on the wire (RDP is over TCP,
	 * but DVCs can still slice across PDUs), the Windows decoder stays stuck
	 * on P-frames until the next interval boundary. Explicitly requesting a
	 * sync gives the decoder a recovery opportunity at a predictable cadence
	 * — the user reported one-off "stuck on first frame, restart Camera app
	 * fixes it" hangs, which this defends against. */
	if ((st->frameCount % 60) == 0 && st->frameCount > 0)
	{
		AMediaFormat* params = AMediaFormat_new();
		if (params)
		{
			AMediaFormat_setInt32(params, "request-sync", 0);
			AMediaCodec_setParameters(st->encoder, params);
			AMediaFormat_delete(params);
		}
	}
	return CHANNEL_RC_OK;
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

	/* Stop the camera→AImageReader listener BEFORE we begin tearing down the
	 * encoder. on_image_available → feed_nv12_to_encoder touches st->encoder;
	 * if a callback fires while we're mid-teardown the encoder pointer races.
	 * AImageReader_setImageListener(NULL) prevents new callbacks; in-flight
	 * callbacks complete on their own (they're fast — memcpy + queueInputBuffer). */
	if (st->reader)
		AImageReader_setImageListener(st->reader, NULL);

	if (st->encoder)
	{
		atomic_store(&st->drainStop, 1);
		/* Manual-feed encoder: signal EOS by queueing an empty input buffer with
		 * BUFFER_FLAG_END_OF_STREAM. We deliberately don't call
		 * AMediaCodec_signalEndOfInputStream — that one is only valid when the
		 * encoder was created with createInputSurface, which we no longer use. */
		ssize_t idx = AMediaCodec_dequeueInputBuffer(st->encoder, 5000);
		if (idx >= 0)
			AMediaCodec_queueInputBuffer(st->encoder, (size_t)idx, 0, 0, 0,
			                             AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
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
	free(st->rotBuf);
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

	st->encW = width;
	st->encH = height;
	st->encFps = fps > 0 ? fps : 30;
	st->encPtsUs = 0;

	AMediaFormat* fmt = AMediaFormat_new();
	AMediaFormat_setString(fmt, AMEDIAFORMAT_KEY_MIME, "video/avc");
	AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_WIDTH, width);
	AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_HEIGHT, height);
	AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_FRAME_RATE, st->encFps);
	AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_I_FRAME_INTERVAL, 1);
	/* MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar = 21 (NV12).
	 * We feed the encoder NV12 bytes manually (copied off AImageReader frames),
	 * so we must NOT use COLOR_FormatSurface here — that's only valid when the
	 * encoder owns the input surface and the camera writes through GPU. */
	AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_COLOR_FORMAT, 21);
	/* Bitrate ≈ resolution × fps × 0.1 bpp (low-motion baseline; codec ABRs from there). */
	const int32_t bitrate = (int32_t)((int64_t)width * height * st->encFps / 10);
	AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_BIT_RATE, bitrate);

	media_status_t r = AMediaCodec_configure(st->encoder, fmt, NULL, NULL,
	                                         AMEDIACODEC_CONFIGURE_FLAG_ENCODE);
	AMediaFormat_delete(fmt);
	if (r != AMEDIA_OK)
	{
		WLog_WARN(TAG, "AMediaCodec_configure failed: %d", r);
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

	/* Hybrid path:
	 *   1. Camera2 → AImageReader (NV12)           — bypasses CamX surface bug
	 *   2. AImageReader → AMediaCodec input buffer — manual feed, NOT input surface
	 *   3. AMediaCodec drain thread → cam_android_submit_frame as H.264
	 * The channel layer can't transcode NV12→H.264 because the bundled FFmpeg
	 * has no H.264 encoder, so we deliver H.264 directly and the channel does
	 * a passthrough (input==output==H.264). */
	st->path = CAM_PATH_RAW_YUV; /* now interpreted as "AImageReader source"; encoder still runs */

	st->mgr = ACameraManager_create();
	if (!st->mgr)
	{
		capture_state_free(st);
		return ERROR_INTERNAL_ERROR;
	}

	ACameraIdList* list = NULL;
	const char* listNativeId = cam_android_resolve_id(stream->dev->deviceId, st->mgr, &list);
	if (!listNativeId)
	{
		WLog_WARN(TAG, "camera %s not present on device", stream->dev->deviceId);
		if (list)
			ACameraManager_deleteCameraIdList(list);
		capture_state_free(st);
		return ERROR_NOT_FOUND;
	}
	/* The pointer returned by resolve_id is owned by the ACameraIdList. We
	 * delete that list before we're done logging / using the id, so copy it
	 * out into a local buffer to avoid a use-after-free (previously logged
	 * garbage like "native=Callback"). */
	char nativeId[64] = { 0 };
	strncpy(nativeId, listNativeId, sizeof(nativeId) - 1);

	/* Read sensor orientation + lens facing for this camera so we can apply
	 * the right rotation/mirror to the NV12 frame. External cameras (USB
	 * webcams etc.) have no fixed relationship to the phone — leave them
	 * as-is per the spec.
	 *
	 *   For built-in BACK  cameras: rotation = (sensorOrientation - dispRot + 360) % 360
	 *   For built-in FRONT cameras: rotation = (sensorOrientation + dispRot) % 360, then mirror
	 *
	 * On a Samsung S25 Ultra the sensor orientations are 90 (back) and 270
	 * (front); other devices vary. */
	int sensorOrientation = 0;
	int facing = ACAMERA_LENS_FACING_BACK;
	ACameraMetadata* meta = NULL;
	if (ACameraManager_getCameraCharacteristics(st->mgr, nativeId, &meta) == ACAMERA_OK && meta)
	{
		ACameraMetadata_const_entry e;
		if (ACameraMetadata_getConstEntry(meta, ACAMERA_SENSOR_ORIENTATION, &e) == ACAMERA_OK &&
		    e.count > 0)
			sensorOrientation = e.data.i32[0];
		if (ACameraMetadata_getConstEntry(meta, ACAMERA_LENS_FACING, &e) == ACAMERA_OK &&
		    e.count > 0)
			facing = e.data.u8[0];
		ACameraMetadata_free(meta);
	}

	const int dispRot = cam_android_get_display_rotation();
	if (facing == ACAMERA_LENS_FACING_EXTERNAL)
	{
		st->rotationDeg = 0;
		st->mirrorH = FALSE;
	}
	else if (facing == ACAMERA_LENS_FACING_FRONT)
	{
		st->rotationDeg = ((sensorOrientation + dispRot) % 360 + 360) % 360;
		st->mirrorH = TRUE;
	}
	else /* BACK */
	{
		st->rotationDeg = ((sensorOrientation - dispRot) % 360 + 360) % 360;
		st->mirrorH = FALSE;
	}
	st->rawW = w;
	st->rawH = h;
	if (st->rotationDeg == 90 || st->rotationDeg == 270)
	{
		st->outW = h;
		st->outH = w;
	}
	else
	{
		st->outW = w;
		st->outH = h;
	}
	WLog_DBG(TAG,
	         "rotation: sensor=%d display=%d facing=%d → rot=%d mirror=%d raw=%dx%d out=%dx%d",
	         sensorOrientation, dispRot, facing, st->rotationDeg, (int)st->mirrorH, w, h,
	         st->outW, st->outH);

	/* Publish the capture state on the stream BEFORE the AImageReader listener
	 * is wired up. on_image_available short-circuits when stream->state is
	 * NULL — if the camera produces frames before this assignment, the listener
	 * fires `maxImages` times without acquiring anything, the BufferQueue fills
	 * up, and the camera producer blocks permanently. That presents as "first
	 * frame shows on the remote desktop, then nothing". */
	stream->state = st;

	UINT setupRc = setup_yuv_reader(stream, st, w, h);
	if (setupRc != CHANNEL_RC_OK)
	{
		stream->state = NULL;
		if (list)
			ACameraManager_deleteCameraIdList(list);
		capture_state_free(st);
		return setupRc;
	}

	/* Bring up the H.264 encoder with the ROTATED dimensions. The AImageReader
	 * still receives the raw sensor frame at (w, h); rotate_mirror_nv12()
	 * produces a (outW × outH) NV12 buffer that we feed to the encoder. */
	UINT encRc = setup_h264_encoder(stream, st, st->outW, st->outH, fps);
	if (encRc != CHANNEL_RC_OK)
	{
		WLog_WARN(TAG, "H.264 encoder unavailable on this device; camera redirect will fail");
		stream->state = NULL;
		if (list)
			ACameraManager_deleteCameraIdList(list);
		capture_state_free(st);
		return encRc;
	}

	ACameraDevice_StateCallbacks devCb = { .context = stream,
		                                   .onDisconnected = on_device_disconnected,
		                                   .onError = on_device_error };
	if (ACameraManager_openCamera(st->mgr, nativeId, &devCb, &st->device) != ACAMERA_OK)
	{
		WLog_WARN(TAG, "openCamera failed for %s (CAMERA permission?)", nativeId);
		if (list)
			ACameraManager_deleteCameraIdList(list);
		stream->state = NULL;
		capture_state_free(st);
		return ERROR_ACCESS_DENIED;
	}
	if (list)
		ACameraManager_deleteCameraIdList(list);

	/* TEMPLATE_PREVIEW is what AImageReader-based capture uses; TEMPLATE_RECORD
	 * is intended for MediaCodec input surfaces and triggers Samsung's
	 * SAT/multi-camera pipeline on the back camera, which then complains
	 * about missing buffers. PREVIEW gives us a plain single-camera stream. */
	if (ACameraDevice_createCaptureRequest(st->device, TEMPLATE_PREVIEW, &st->request) !=
	    ACAMERA_OK)
	{
		stream->state = NULL;
		capture_state_free(st);
		return ERROR_INTERNAL_ERROR;
	}
	if (ACameraOutputTarget_create(st->captureWindow, &st->target) != ACAMERA_OK ||
	    ACaptureRequest_addTarget(st->request, st->target) != ACAMERA_OK)
	{
		stream->state = NULL;
		capture_state_free(st);
		return ERROR_INTERNAL_ERROR;
	}
	/* Force auto-exposure + auto-focus on. TEMPLATE_PREVIEW already sets these
	 * to ON on most devices but some Samsung profiles leave them OFF, which
	 * yields uniformly-zero Y data (black frames) once capture starts. */
	{
		uint8_t aeMode = ACAMERA_CONTROL_AE_MODE_ON;
		ACaptureRequest_setEntry_u8(st->request, ACAMERA_CONTROL_AE_MODE, 1, &aeMode);
		uint8_t afMode = ACAMERA_CONTROL_AF_MODE_CONTINUOUS_VIDEO;
		ACaptureRequest_setEntry_u8(st->request, ACAMERA_CONTROL_AF_MODE, 1, &afMode);
		uint8_t controlMode = ACAMERA_CONTROL_MODE_AUTO;
		ACaptureRequest_setEntry_u8(st->request, ACAMERA_CONTROL_MODE, 1, &controlMode);
	}
	if (ACaptureSessionOutputContainer_create(&st->outContainer) != ACAMERA_OK ||
	    ACaptureSessionOutput_create(st->captureWindow, &st->sessionOut) != ACAMERA_OK ||
	    ACaptureSessionOutputContainer_add(st->outContainer, st->sessionOut) != ACAMERA_OK)
	{
		stream->state = NULL;
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
		stream->state = NULL;
		capture_state_free(st);
		return ERROR_INTERNAL_ERROR;
	}
	ACaptureRequest* requests[1] = { st->request };
	if (ACameraCaptureSession_setRepeatingRequest(st->session, NULL, 1, requests, NULL) !=
	    ACAMERA_OK)
	{
		stream->state = NULL;
		capture_state_free(st);
		return ERROR_INTERNAL_ERROR;
	}

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
