# cRDP: Android backend for the FreeRDP printer client channel.
#
# Spools each remote print job to a file under a JNI-configured directory; a
# Kotlin FileObserver picks the closed files up and hands them to whatever
# consumer the app exposes (SAF share, system print framework, etc.). No CUPS,
# no native print HAL — just a sink.

if(ANDROID)

	define_channel_client_subsystem("printer" "android" "")

	set(${MODULE_PREFIX}_SRCS
		printer_android.c
		printer_android_jni.c
	)

	set(${MODULE_PREFIX}_LIBS
		winpr
		freerdp
		android
		log
	)

	include_directories(..)

	add_channel_client_subsystem_library(${MODULE_PREFIX} ${MODULE_NAME} ${CHANNEL_NAME} "" TRUE "")

endif()
