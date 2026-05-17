# cRDP release shrink/obfuscation rules.

# Keep source file + line numbers for readable crash stacks.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- JNI / native ---------------------------------------------------------
# Native methods themselves and the classes that own them must keep their
# JNI-visible names. afreerdp's C side resolves Java callbacks by class +
# method name, so the bridge packages stay un-renamed.
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.crdp.engine.afreerdp.** { *; }
-keep class com.crdp.core.rdp.** { *; }
-keep class com.crdp.rdp.direct.** { *; }
# FreeRDP JNI bridge: FQN (com.freerdp.freerdpcore.services.LibFreeRDP) and the
# OnXxx/AdapterCallbacks members are resolved by name from libfreerdp-android.so.
# Keeping the whole package also prevents R8 from making LibFreeRDP effectively
# abstract (private ctor never called from kept code), which crashed JNI_OnLoad.
-keep class com.freerdp.freerdpcore.** { *; }

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
