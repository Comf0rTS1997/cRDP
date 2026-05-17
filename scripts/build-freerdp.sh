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
    # Restoration trap is set later, after all upstream-script patches are installed.
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
    # Patches to android-build-freerdp.sh:
    #  - CHANNEL_RDPECAM_CLIENT=ON enables the rdpecam Android HAL.
    #  - CHANNEL_PRINTER=ON + CHANNEL_PRINTER_CLIENT=ON enable the printer
    #    channel with our channels/printer/client/android/ file-spool backend.
    #    CHANNEL_PRINTER_CLIENT is a CMAKE_DEPENDENT_OPTION on CHANNEL_PRINTER —
    #    passing only the client flag gets force-OFF'd by cmake unless the parent
    #    is also ON, because the channel's ChannelOptions.cmake defaults
    #    OPTION_DEFAULT to OFF on Android (no CUPS).
    #  - Extend CMAKE_SHARED_LINKER_FLAGS with -Wl,-z,max-page-size=16384 so
    #    libfreerdp3 / libfreerdp-client3 / libwinpr3 LOAD segments are aligned
    #    for Android 15+ devices running with 16KB pages.
    awk '/^CMAKE_CMD_ARGS="-DANDROID_NDK=\$ANDROID_NDK \\$/ {
            print
            print "\t-DCHANNEL_RDPECAM_CLIENT=ON \\"
            print "\t-DCHANNEL_PRINTER=ON \\"
            print "\t-DCHANNEL_PRINTER_CLIENT=ON \\"
            # WinPR built-in RC4 + MD4 instead of OpenSSL'\''s legacy provider.
            # OpenSSL 3.x banished RC4/MD4 to a non-default provider; without
            # this, NTLM (used by NLA in /sec:nla) fails with
            # SEC_E_INTERNAL_ERROR because ntlm_init_rc4_seal_states cant fetch
            # EVP_rc4(). Surfaces as "The connection transport layer failed"
            # right after the cert prompt on every connect.
            print "\t-DWITH_INTERNAL_RC4=ON \\"
            print "\t-DWITH_INTERNAL_MD4=ON \\"
            next
         }
         /-DCMAKE_SHARED_LINKER_FLAGS="-L\$BUILD_DST\/\$ARCH" \\$/ {
            sub(/"-L\$BUILD_DST\/\$ARCH"/, "\"-L$BUILD_DST/$ARCH -Wl,-z,max-page-size=16384\"")
            print
            next
         }
         { print }' "$FREERDP_BUILD_SCRIPT" > "$FREERDP_BUILD_SCRIPT.tmp" \
         && mv "$FREERDP_BUILD_SCRIPT.tmp" "$FREERDP_BUILD_SCRIPT"
fi

# Patch cJSON build to add 16KB page alignment to libcjson.so.
CJSON_BUILD_SCRIPT="$FREERDP_ROOT/scripts/android-build-cjson.sh"
CJSON_BUILD_SCRIPT_BAK="$WORK/android-build-cjson.sh.bak"
if [ -f "$CJSON_BUILD_SCRIPT" ] && ! grep -q "max-page-size=16384" "$CJSON_BUILD_SCRIPT"; then
    cp -p "$CJSON_BUILD_SCRIPT" "$CJSON_BUILD_SCRIPT_BAK"
    # CMAKE_POLICY_VERSION_MINIMUM=3.5 needed because cJSON 1.7.18 declares
    # cmake_minimum_required < 3.5 which CMake 4.x rejects outright.
    awk '/^CMAKE_CMD_ARGS="-DANDROID_NDK=\$ANDROID_NDK \\$/ {
            print
            print "\t-DCMAKE_SHARED_LINKER_FLAGS=\"-Wl,-z,max-page-size=16384\" \\"
            print "\t-DCMAKE_POLICY_VERSION_MINIMUM=3.5 \\"
            next
         } { print }' "$CJSON_BUILD_SCRIPT" > "$CJSON_BUILD_SCRIPT.tmp" \
         && mv "$CJSON_BUILD_SCRIPT.tmp" "$CJSON_BUILD_SCRIPT"
fi

# Patch FFmpeg build to add 16KB page alignment to libav*/libsw*.so via --extra-ldflags.
FFMPEG_BUILD_SCRIPT="$FREERDP_ROOT/scripts/android-build-ffmpeg.sh"
FFMPEG_BUILD_SCRIPT_BAK="$WORK/android-build-ffmpeg.sh.bak"
if [ -f "$FFMPEG_BUILD_SCRIPT" ] && ! grep -q "max-page-size=16384" "$FFMPEG_BUILD_SCRIPT"; then
    cp -p "$FFMPEG_BUILD_SCRIPT" "$FFMPEG_BUILD_SCRIPT_BAK"
    # The --extra-ldflags value is "${LDFLAGS}". Replace with concatenation.
    awk '{ sub(/--extra-ldflags="\$\{LDFLAGS\}"/, "--extra-ldflags=\"${LDFLAGS} -Wl,-z,max-page-size=16384\""); print }' \
        "$FFMPEG_BUILD_SCRIPT" > "$FFMPEG_BUILD_SCRIPT.tmp" \
        && mv "$FFMPEG_BUILD_SCRIPT.tmp" "$FFMPEG_BUILD_SCRIPT"
fi

# Patch OpenSSL build to add 16KB page alignment to libssl.so / libcrypto.so.
# OpenSSL's Configure accepts -Wl,... linker flags as positional args after the target.
OPENSSL_BUILD_SCRIPT="$FREERDP_ROOT/scripts/android-build-openssl.sh"
OPENSSL_BUILD_SCRIPT_BAK="$WORK/android-build-openssl.sh.bak"
if [ -f "$OPENSSL_BUILD_SCRIPT" ] && ! grep -q "max-page-size=16384" "$OPENSSL_BUILD_SCRIPT"; then
    cp -p "$OPENSSL_BUILD_SCRIPT" "$OPENSSL_BUILD_SCRIPT_BAK"
    awk '{ sub(/\.\/Configure \$\{CONFIG\} -D__ANDROID_API__=\$NDK_TARGET/, "./Configure ${CONFIG} -D__ANDROID_API__=$NDK_TARGET -Wl,-z,max-page-size=16384"); print }' \
        "$OPENSSL_BUILD_SCRIPT" > "$OPENSSL_BUILD_SCRIPT.tmp" \
        && mv "$OPENSSL_BUILD_SCRIPT.tmp" "$OPENSSL_BUILD_SCRIPT"
fi

# Chain restoration of all patched upstream scripts.
trap '
    cp -p "$CONF_BACKUP" "$CONF_FILE" 2>/dev/null
    cp -p "$FREERDP_BUILD_SCRIPT_BAK" "$FREERDP_BUILD_SCRIPT" 2>/dev/null
    cp -p "$CJSON_BUILD_SCRIPT_BAK" "$CJSON_BUILD_SCRIPT" 2>/dev/null
    cp -p "$FFMPEG_BUILD_SCRIPT_BAK" "$FFMPEG_BUILD_SCRIPT" 2>/dev/null
    cp -p "$OPENSSL_BUILD_SCRIPT_BAK" "$OPENSSL_BUILD_SCRIPT" 2>/dev/null
' EXIT

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
