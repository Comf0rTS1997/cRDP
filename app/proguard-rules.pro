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

# --- Tink (security-crypto) references errorprone annotations at compile time only.
-dontwarn com.google.errorprone.annotations.**
