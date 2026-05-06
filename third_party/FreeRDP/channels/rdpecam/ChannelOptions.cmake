
set(OPTION_DEFAULT ON)
# cRDP override: enable the rdpecam client by default on Android (the upstream
# default is OFF because only the V4L2 backend exists). The Android backend lives
# under client/android/ and is gated inside client/CMakeLists.txt.
if(ANDROID)
	set(OPTION_CLIENT_DEFAULT ON)
else()
	set(OPTION_CLIENT_DEFAULT OFF)
endif()
set(OPTION_SERVER_DEFAULT ON)

define_channel_options(NAME "rdpecam" TYPE "dynamic"
	DESCRIPTION "Video Capture Virtual Channel Extension"
	SPECIFICATIONS "[MS-RDPECAM]"
	DEFAULT ${OPTION_DEFAULT})

define_channel_server_options(${OPTION_SERVER_DEFAULT})
define_channel_client_options(${OPTION_CLIENT_DEFAULT})

