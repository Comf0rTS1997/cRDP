# cRDP

Android RDP client. Two transports: **direct** (TCP to a Windows host) and
**gateway** (relay via WebSocket through a custom gateway service).

## Building

Default build uses the stub RDP engine — no native toolchain required, no
external dependencies, but Direct connections will fail with
`RDP engine not configured`.

```sh
./gradlew :app:assembleDebug
```

To compile in the real FreeRDP-backed engine:

```sh
git submodule update --init --recursive
./scripts/build-freerdp.sh           # builds .so artifacts (slow first time)
./gradlew :app:assembleDebug -Pcrdp.engine=afreerdp
```

On Windows / PowerShell:

```powershell
git submodule update --init --recursive
pwsh scripts\build-freerdp.ps1
.\gradlew.bat :app:assembleDebug "-Pcrdp.engine=afreerdp"
```

> Note: PowerShell's parser splits unquoted property arguments on `.`; always
> quote `-P…` flags when the property name contains a dot.

### Build prerequisites for `crdp.engine=afreerdp`

Building FreeRDP from source needs:

- Android SDK + NDK r26 or newer (export `ANDROID_SDK` / `ANDROID_NDK`,
  or set `sdk.dir` / `ndk.dir` in `local.properties`)
- CMake ≥ 3.13, GNU Make
- autotools, perl, yasm, nasm (OpenSSL / FFmpeg builds)
- Bash (Git Bash on Windows is sufficient)

If you don't want the toolchain, drop pre-built `.so` files into
`engine/afreerdp/prebuilts/<abi>/` and run with `-Pcrdp.engine=afreerdp`.
The prebuilts directory is overlaid onto `jniLibs` at build time.

ABIs shipped: `arm64-v8a`, `armeabi-v7a`, `x86_64`. (`x86` deliberately dropped.)

## Module layout

```
:app                      Application + DI wiring
:core:ui                  Compose theming, biometric prompter
:core:rdp                 Connection profiles, RdpSessionPort, repositories
:core:rdp-engine          RdpEngine seam — replaceable boundary for any RDP
                          transport implementation. No 3rd-party types here.
:rdp-direct               Direct TCP transport (DirectRdpSession). Routes
                          all wire I/O through @DirectEngine RdpEngine.
:rdp-gateway              WebSocket-relay transport.
:engine:afreerdp          [optional] FreeRDP-backed RdpEngine. Included only
                          when -Pcrdp.engine=afreerdp.
:feature:connections      UI: profile list/editor, vault.
:feature:session          UI: live session screen, surface attach, challenge dialogs.
```

## The `RdpEngine` seam

`com.crdp.core.rdp.engine.RdpEngine` is a small (10-method) interface that
hides which RDP library actually moves bytes. To swap implementations:

| Swap | Effort |
|---|---|
| Bump FreeRDP version | `git checkout <new-tag>` in submodule, rerun build script. |
| Replace aFreeRDP with custom JNI / different RDP library | New module under `engine/<name>/` implementing `RdpEngine`; bind it `@DirectEngine`; remove `:engine:afreerdp` from `settings.gradle.kts`. No edits in `:feature:*`, `:app`, `:rdp-direct`, or `:core:*`. |
| Mock for tests | Provide a `FakeRdpEngine` in a test source set; override `@DirectEngine` via Hilt's `@TestInstallIn`. |

See `THIRD_PARTY.md` for license info.

## Vibe Code

This  project is heavily vibe coded. Tools used including Claude and Cursor.
