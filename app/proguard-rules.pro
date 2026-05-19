# cRDP release shrink/obfuscation rules.

# Keep source file + line numbers for readable crash stacks.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- JNI / native ---------------------------------------------------------
# Native methods themselves and the classes that own them must keep their
# JNI-visible names. The C side resolves Java callbacks by class + method
# name (Java_<FQCN>_<method> mangling), so any class that hosts an `external
# fun` / `native` declaration OR receives a callback via GetStaticMethodID
# must keep BOTH its FQCN and member names un-renamed and un-stripped.
-keepclasseswithmembernames class * {
    native <methods>;
}
# Engine-side Kotlin packages: AFreeRdpEngine, NativeCameraBridge (rdpecam
# DVC JNI), PrinterRedirectBridge (printer DVC JNI), SurfaceBlitter, etc.
-keep class com.crdp.engine.afreerdp.** { *; }
# core/rdp + core/rdp-engine: hosts CameraOrientationBridge (Kotlin sink the
# engine module installs at startup; called from C indirectly via
# LibFreeRDP.freerdp_set_camera_display_rotation), RdpConnectParams enums
# (CameraRedirect, AudioPlayback, …) that buildArgs() switches on.
-keep class com.crdp.core.rdp.** { *; }
-keep class com.crdp.rdp.direct.** { *; }
# Explicit per-bridge keeps. These are technically redundant with the
# package-level keeps above, but spelled out so a future package refactor
# can't silently break JNI:
#
#   rdpecam HAL (camera redirect):
#     Java_com_crdp_engine_afreerdp_NativeCameraBridge_registerDevice
#     Java_com_crdp_engine_afreerdp_NativeCameraBridge_unregisterDevice
#     Java_com_crdp_engine_afreerdp_NativeCameraBridge_pushFrame
#   printer HAL:
#     Java_com_crdp_engine_afreerdp_PrinterRedirectBridge_setSpoolDir
#     Java_com_crdp_engine_afreerdp_PrinterRedirectBridge_setPrinterName
#
# Kotlin `object` declarations also carry an INSTANCE static field that JNI
# needs to dispatch instance methods through — covered by `{ *; }`.
-keep class com.crdp.engine.afreerdp.NativeCameraBridge { *; }
-keep class com.crdp.engine.afreerdp.PrinterRedirectBridge { *; }
-keep class com.crdp.core.rdp.CameraOrientationBridge { *; }
# FreeRDP JNI bridge: FQN (com.freerdp.freerdpcore.services.LibFreeRDP) and the
# private static OnXxx callback methods (OnConnectionSuccess, OnGraphicsUpdate,
# OnRemoteClipboardChanged, OnCursorBitmap, …) are resolved by name from
# libfreerdp-android.so. The AdapterCallbacks inner interface must also keep
# its method names so the dispatch table inside LibFreeRDP doesn't get
# renamed out from under the C callers. Keeping the whole package also
# prevents R8 from making LibFreeRDP effectively abstract (private ctor
# never called from kept code), which crashed JNI_OnLoad.
-keep class com.freerdp.freerdpcore.** { *; }
-keep class com.freerdp.freerdpcore.services.LibFreeRDP { *; }
-keep class com.freerdp.freerdpcore.services.LibFreeRDP$* { *; }

# --- kotlinx.serialization ------------------------------------------------
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers @kotlinx.serialization.Serializable class * {
    static **$Companion Companion;
    static <fields>;
    <fields>;
    static <methods>;
    <methods>;
}
-keep,includedescriptorclasses class com.crdp.**$$serializer { *; }
-keepclassmembers class com.crdp.** {
    *** Companion;
}
-keepclasseswithmembers class com.crdp.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- AndroidX lifecycle / Hilt / ViewModel --------------------------------
-keep,allowobfuscation,allowshrinking class * extends androidx.lifecycle.ViewModel
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }
-dontwarn dagger.hilt.**

# --- Compose / Coroutines -------------------------------------------------
-dontwarn org.jetbrains.annotations.**
-dontwarn kotlinx.coroutines.**

# --- DataStore proto/preferences-related reflection ----------------------
-keep class androidx.datastore.*.** { *; }

# --- Tink (security-crypto) ----------------------------------------------
# androidx.security:security-crypto bundles Tink. Tink (de)serializes keysets
# via reflection on its shaded-protobuf classes; if R8 strips them the keyset
# round-trips corrupted and AES-GCM finish fails with KM_ERROR_VERIFICATION_FAILED,
# silently breaking EncryptedFile/MasterKey writes/reads (lost profiles/passwords).
-keep class com.google.crypto.tink.** { *; }
-keepclassmembers class com.google.crypto.tink.** { *; }
-keep class androidx.security.crypto.** { *; }
-keepclassmembers class androidx.security.crypto.** { *; }
-dontwarn com.google.crypto.tink.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.conscrypt.**
