plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

/**
 * The aFreeRDP-backed RdpEngine. Consumes .so artifacts produced by
 * `scripts/build-freerdp.sh` (which builds FreeRDP via the vendored submodule
 * at third_party/FreeRDP). If contributors don't want the full FreeRDP toolchain,
 * they may instead drop pre-built artifacts under
 *     engine/afreerdp/prebuilts/<abi>/
 * and they will be picked up via the `prebuilts` jniLibs source dir.
 */
android {
    namespace = "com.crdp.engine.afreerdp"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        getByName("main") {
            // Both directories are merged. The script-built jniLibs/ wins when both
            // exist (last src dir takes precedence in AGP), so a fresh build always
            // overrides any stale prebuilt drop-in.
            jniLibs.srcDirs("prebuilts", "src/main/jniLibs")
        }
    }
}

dependencies {
    implementation(project(":core:rdp"))
    implementation(project(":core:rdp-engine"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.javax.inject)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
