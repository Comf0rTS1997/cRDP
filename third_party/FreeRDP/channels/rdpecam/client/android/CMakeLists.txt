# cRDP: Android camera HAL subsystem for the FreeRDP rdpecam client channel.
#
# Two upstream sources funnel into one HAL:
#   1. NDK Camera2 (libcamera2ndk) for built-in front/back and Camera2-visible
#      external (USB) cameras.
#   2. A JNI bridge (libfreerdp-android-camerabridge.so → exported into the same
#      module) that accepts NV12 / H.264 frames pushed from Kotlin. Used as the
#      libuvc fallback path for OEMs that don't surface USB cameras through Camera2.
#
# H.264 passthrough uses libmediandk (AMediaCodec). Software fallback to
# FreeRDP's built-in encoder is automatic (it lives upstream of the HAL).

if(ANDROID)

	define_channel_client_subsystem("rdpecam" "android" "")

	set(${MODULE_PREFIX}_SRCS
		camera_android.c
		camera_android_capture.c
		camera_android_bridge.c
		camera_android_jni.c
	)

	set(${MODULE_PREFIX}_LIBS
		winpr
		freerdp
		camera2ndk
		mediandk
		android
		log
	)

	include_directories(..)

	add_channel_client_subsystem_library(${MODULE_PREFIX} ${MODULE_NAME} ${CHANNEL_NAME} "" TRUE "")

endif()
