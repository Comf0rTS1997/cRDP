#----------------------------------------------------------------
# Generated CMake target import file.
#----------------------------------------------------------------

# Commands may need to know the format version.
set(CMAKE_IMPORT_FILE_VERSION 1)

# Import target "cjson" for configuration ""
set_property(TARGET cjson APPEND PROPERTY IMPORTED_CONFIGURATIONS NOCONFIG)
set_target_properties(cjson PROPERTIES
  IMPORTED_LOCATION_NOCONFIG "/mnt/c/Develop/cRDP/third_party/FreeRDP/client/Android/Studio/freeRDPCore/src/main/jniLibs/armeabi-v7a/./libcjson.so"
  IMPORTED_SONAME_NOCONFIG "libcjson.so"
  )

list(APPEND _cmake_import_check_targets cjson )
list(APPEND _cmake_import_check_files_for_cjson "/mnt/c/Develop/cRDP/third_party/FreeRDP/client/Android/Studio/freeRDPCore/src/main/jniLibs/armeabi-v7a/./libcjson.so" )

# Commands beyond this point should not need to know the version.
set(CMAKE_IMPORT_FILE_VERSION)
