# Third-party Components

cRDP integrates the following third-party software. License compliance and
attribution must accompany every distributed build.

## FreeRDP

- Source: https://github.com/FreeRDP/FreeRDP
- Pinned tag: **3.9.0** (commit `4ae5b6c25452211f01f370d3e6e481553e72778a`)
- License: **Apache License 2.0**
- Vendored at: `third_party/FreeRDP/` (git submodule)

cRDP consumes only the native libraries (`libfreerdp3.so`, `libfreerdp-client3.so`,
`libwinpr3.so`, `libfreerdp-android.so`) built from the FreeRDP source tree by
`scripts/build-freerdp.sh`. The aFreeRDP demo Android client (`freeRDPCore` /
`freeRDPApp`) is NOT included — we ship our own thin Java JNI shim
(`com.freerdp.freerdpcore.services.LibFreeRDP`) inside `:engine:afreerdp` to
receive the JNI callbacks emitted by `libfreerdp-android.so`.

## OpenSSL

- Source: https://github.com/openssl/openssl
- Pinned tag: **openssl-3.3.1**
- License: **Apache License 2.0**
- Pulled in transitively by FreeRDP's `android-build-openssl.sh` and statically
  linked into `libfreerdp3.so`.

## cJSON

- Source: https://github.com/DaveGamble/cJSON
- Pinned tag: **v1.7.18**
- License: **MIT**
- Pulled in transitively by FreeRDP's build (Azure AD / OAuth flows).

## ABIs Shipped

`arm64-v8a`, `armeabi-v7a`, `x86_64`. We deliberately drop `x86` (no modern
device target; saves build time and APK size).

## How to refresh / replace

| Action | How |
|---|---|
| Bump FreeRDP version | `cd third_party/FreeRDP && git fetch && git checkout <tag>`; commit submodule pointer; rerun `scripts/build-freerdp.sh`. |
| Swap aFreeRDP for a different RDP engine | Add a new module under `engine/<name>/` implementing `com.crdp.core.rdp.engine.RdpEngine`; bind it at `@DirectEngine`; remove `:engine:afreerdp` from `settings.gradle.kts`. |
| Replace OpenSSL inside FreeRDP | Edit FreeRDP's `scripts/android-build-openssl.sh` (or skip OpenSSL build and link a different TLS library via `-DWITH_OPENSSL=OFF` + custom flags). |

## Build prerequisites

- Android SDK + NDK (r26+ recommended)
- CMake ≥ 3.13, GNU Make, autotools, perl, yasm, nasm
- Bash (Git Bash on Windows is sufficient)
