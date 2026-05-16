#!/usr/bin/env bash
#
# Build FreeRDP native libraries for Android and copy the resulting .so
# artifacts under engine/afreerdp/src/main/jniLibs/<abi>/.
#
# Prerequisites:
#   - Git submodule initialized: `git submodule update --init --recursive`
#   - Android SDK + NDK installed; export ANDROID_SDK and ANDROID_NDK env vars,
#     OR set sdk.dir / ndk.dir in the project's local.properties.
#   - cmake >= 3.13, make, autotools, perl, yasm, nasm.
#   - Bash (Git Bash / WSL on Windows; native bash on Linux/macOS).
#
# Usage:
#   scripts/build-freerdp.sh [--release|--debug] [--no-deps]

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FREERDP_ROOT="$REPO_ROOT/third_party/FreeRDP"
BUILD_SCRIPT="$FREERDP_ROOT/scripts/android-build-freerdp.sh"
JNI_OUT="$REPO_ROOT/engine/afreerdp/src/main/jniLibs"
# FreeRDP's android-build.conf hard-codes BUILD_DST relative to its own scripts
# dir. We let it write there, then copy out — overriding via env var doesn't
# stick because the conf reassigns BUILD_DST after our export.
FREERDP_DEFAULT_OUT="$FREERDP_ROOT/client/Android/Studio/freeRDPCore/src/main/jniLibs"

if [ ! -f "$BUILD_SCRIPT" ]; then
    echo "FreeRDP submodule missing. Run: git submodule update --init --recursive" >&2
    exit 1
fi

read_local_prop() {
    local key="$1"
    local lp="$REPO_ROOT/local.properties"
    [ -f "$lp" ] || return 1
    grep -E "^${key}=" "$lp" | head -n1 | cut -d= -f2-
}

: "${ANDROID_SDK:=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$(read_local_prop sdk.dir || true)}}}"
: "${ANDROID_NDK:=${ANDROID_NDK_HOME:-$(read_local_prop ndk.dir || true)}}"

if [ -z "${ANDROID_SDK:-}" ] || [ -z "${ANDROID_NDK:-}" ]; then
    echo "ANDROID_SDK and ANDROID_NDK must be set (env or local.properties sdk.dir/ndk.dir)." >&2
    exit 1
fi
export ANDROID_SDK ANDROID_NDK

BUILD_TYPE_FLAG="--release"
WITH_DEPS=1
for arg in "$@"; do
    case "$arg" in
        --debug)   BUILD_TYPE_FLAG="--debug" ;;
        --release) BUILD_TYPE_FLAG="--release" ;;
        --no-deps) WITH_DEPS=0 ;;
        *)         echo "unknown flag: $arg" >&2; exit 2 ;;
    esac
done

WORK="$REPO_ROOT/.build/freerdp"
mkdir -p "$WORK"
cd "$WORK"

# android-build.conf hard-codes BUILD_ARCH="armeabi-v7a arm64-v8a" and re-assigns
# after sourcing, so our env export doesn't stick. x86_64 is handled in the
# post-build copy loop below.
export BUILD_ARCH="arm64-v8a armeabi-v7a x86_64"

# The Android SDK cmake ships Windows .exe binaries that can't run in WSL.
# Pre-set CMAKE_PROGRAM so android-build-common.sh skips its 'find' lookup.
export CMAKE_PROGRAM="${CMAKE_PROGRAM:-/usr/bin/cmake}"

mkdir -p "$JNI_OUT"

# We pass --openssl + --cjson explicitly. FFmpeg defaults to ON in FreeRDP's
# android-build.conf and we don't override it (used for software H.264 fallback
# when MediaCodec is unavailable on the device).
#
# WITH_MEDIACODEC defaults to 0 in android-build.conf and is sourced AFTER
# any env var we export, so a plain `export WITH_MEDIACODEC=1` does not stick.
# We patch the conf in-place for the duration of the build, then restore it.
# Runtime path: libfreerdp/codec/h264.c walks the registered subsystems and
# falls through MediaCodec -> FFmpeg if MediaCodec init fails on the device,
# so this is safe to enable unconditionally.
CONF_FILE="$FREERDP_ROOT/scripts/android-build.conf"
CONF_BACKUP="$WORK/android-build.conf.bak"
if [ -f "$CONF_FILE" ]; then
    cp -p "$CONF_FILE" "$CONF_BACKUP"
    # Use a tmpfile-rename instead of sed -i to avoid GNU/BSD inplace flag drift.
    # Two patches:
    #  - WITH_MEDIACODEC=1 enables the hardware H.264 decoder subsystem.
    #  - NDK_TARGET=24 bumps the build's __ANDROID_API__ from upstream's 21 to 24,
    #    needed for libcamera2ndk + libmediandk symbols used by our rdpecam HAL.
    #    Our app minSdk is 26 so this can never run on a device under API 24 anyway.
    awk '
        /^WITH_MEDIACODEC=0$/ { print "WITH_MEDIACODEC=1"; next }
        /^NDK_TARGET=21$/     { print "NDK_TARGET=26"; next }
        { print }
    ' "$CONF_FILE" > "$CONF_FILE.tmp" && mv "$CONF_FILE.tmp" "$CONF_FILE"
    trap 'cp -p "$CONF_BACKUP" "$CONF_FILE"' EXIT
fi

DEPS_FLAG=()
[ "$WITH_DEPS" = "1" ] && DEPS_FLAG=("--build-deps")

# android-build-freerdp.sh hard-codes its CMAKE_CMD_ARGS string and ignores any
# env-var-based extension hook, so we patch the script in-place to append our
# extra flag (CHANNEL_RDPECAM_CLIENT=ON enables the Android camera HAL we ship
# under channels/rdpecam/client/android/). Restored on EXIT alongside the
# WITH_MEDIACODEC patch below.
FREERDP_BUILD_SCRIPT="$FREERDP_ROOT/scripts/android-build-freerdp.sh"
FREERDP_BUILD_SCRIPT_BAK="$WORK/android-build-freerdp.sh.bak"
if [ -f "$FREERDP_BUILD_SCRIPT" ] && ! grep -q "CHANNEL_RDPECAM_CLIENT" "$FREERDP_BUILD_SCRIPT"; then
    cp -p "$FREERDP_BUILD_SCRIPT" "$FREERDP_BUILD_SCRIPT_BAK"
    awk '/^CMAKE_CMD_ARGS="-DANDROID_NDK=\$ANDROID_NDK \\$/ {
            print
            print "\t-DCHANNEL_RDPECAM_CLIENT=ON \\"
            next
         } { print }' "$FREERDP_BUILD_SCRIPT" > "$FREERDP_BUILD_SCRIPT.tmp" \
         && mv "$FREERDP_BUILD_SCRIPT.tmp" "$FREERDP_BUILD_SCRIPT"
    # Chain restoration onto the existing trap (set further down for android-build.conf).
    trap 'cp -p "$CONF_BACKUP" "$CONF_FILE" 2>/dev/null; cp -p "$FREERDP_BUILD_SCRIPT_BAK" "$FREERDP_BUILD_SCRIPT" 2>/dev/null' EXIT
fi

bash "$BUILD_SCRIPT" \
    "$BUILD_TYPE_FLAG" \
    "${DEPS_FLAG[@]}" \
    --openssl \
    --cjson

# Build libfreerdp-android.so (the JNI bridge) for each ABI.
# This is a separate cmake project under the FreeRDP Android Studio module that
# AGP would normally invoke; we build it ourselves here so the script is self-contained.
CPP_DIR="$FREERDP_ROOT/client/Android/Studio/freeRDPCore/src/main/cpp"
ANDROID_JNI_BUILD="$REPO_ROOT/.build/freerdp-android-jni"
BUILD_NATIVE_ABIS="arm64-v8a armeabi-v7a"  # android-build.conf builds only these two

if [ -f "$CPP_DIR/CMakeLists.txt" ]; then
    echo "Building libfreerdp-android.so (JNI bridge) ..."
    for abi in $BUILD_NATIVE_ABIS; do
        build_dir="$ANDROID_JNI_BUILD/$abi"
        rm -rf "$build_dir"
        mkdir -p "$build_dir"
        "$CMAKE_PROGRAM" \
            -S "$CPP_DIR" \
            -B "$build_dir" \
            -DANDROID_NDK="$ANDROID_NDK" \
            -DANDROID_NATIVE_API_LEVEL=android-21 \
            -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
            -DCMAKE_BUILD_TYPE=Release \
            -DANDROID_ABI="$abi" \
            -DWITH_CLIENT_CHANNELS=ON \
            -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384"
        "$CMAKE_PROGRAM" --build "$build_dir" -- -j"$(nproc 2>/dev/null || echo 4)"
        cp -v "$build_dir/libfreerdp-android.so" "$FREERDP_DEFAULT_OUT/$abi/"
    done
else
    echo "[warn] FreeRDP Android JNI CPP dir missing at $CPP_DIR — skipping libfreerdp-android.so" >&2
fi

# Copy artifacts from FreeRDP's hard-coded output dir into our engine module.
echo "Copying .so artifacts to $JNI_OUT"
for abi in $BUILD_ARCH; do
    src="$FREERDP_DEFAULT_OUT/$abi"
    dst="$JNI_OUT/$abi"
    if [ -d "$src" ]; then
        mkdir -p "$dst"
        cp -v "$src"/*.so "$dst/" 2>/dev/null || true
    else
        echo "  [warn] no artifacts for $abi at $src" >&2
    fi
done

echo "FreeRDP libs deployed under $JNI_OUT"
