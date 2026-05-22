import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

val crdpEngine: String =
    (project.findProperty("crdp.engine") as? String)
        ?: System.getenv("CRDP_ENGINE")
        ?: "afreerdp"

// Release signing: prefer a project-local keystore.properties (gitignored)
// for the non-secret parts (storeFile, keyAlias). Passwords are resolved at
// build time from env vars (CRDP_STORE_PASSWORD / CRDP_KEY_PASSWORD), so the
// preferred entry point is `scripts/build-release.ps1`, which prompts and
// sets the env vars for one build. Falls back to keystore.properties values
// if those keys are present, and finally to the Android debug keystore so
// non-release tasks always work.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

fun resolveSecret(envKey: String, fileKey: String): String? {
    System.getenv(envKey)?.takeIf { it.isNotEmpty() }?.let { return it }
    return keystoreProperties.getProperty(fileKey)?.takeIf { it.isNotEmpty() }
}

android {
    namespace = "com.crdp.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.crdp.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "0.1.7"
        if (crdpEngine == "afreerdp") {
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            }
        }
    }

    signingConfigs {
        create("release") {
            if (keystoreProperties.containsKey("storeFile")) {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                keyAlias = keystoreProperties.getProperty("keyAlias")
                val resolvedStorePass = resolveSecret("CRDP_STORE_PASSWORD", "storePassword")
                val resolvedKeyPass = resolveSecret("CRDP_KEY_PASSWORD", "keyPassword")
                storePassword = resolvedStorePass ?: "android"
                keyPassword = resolvedKeyPass ?: resolvedStorePass ?: "android"
            } else {
                storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            // Distinct applicationId so the debug build installs alongside
            // the signed release (different UID, different DataStore, separate
            // Accessibility-service registration).
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation(project(":core:ui"))
    implementation(project(":core:rdp"))
    implementation(project(":feature:connections"))
    implementation(project(":feature:session"))
    implementation(project(":rdp-direct"))
    implementation(project(":rdp-gateway"))

    if (crdpEngine == "afreerdp") {
        implementation(project(":engine:afreerdp"))
    }

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.window)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.security.crypto)
    implementation(libs.tink.android)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    debugImplementation(libs.compose.ui.tooling)
}
