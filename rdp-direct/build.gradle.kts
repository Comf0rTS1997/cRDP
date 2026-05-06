plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

/**
 * `crdp.engine` selects which RdpEngine binding ships with the build.
 *  - `stub` (default): bind StubRdpEngine — connect fails with a clear message.
 *  - `afreerdp`:       :engine:afreerdp module provides the binding; the stub
 *                      source set is excluded so Hilt sees exactly one binding.
 */
val crdpEngine: String =
    (project.findProperty("crdp.engine") as? String)
        ?: System.getenv("CRDP_ENGINE")
        ?: "stub"

android {
    namespace = "com.crdp.rdp.direct"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
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
            if (crdpEngine != "afreerdp") {
                java.srcDirs("src/stubEngine/java")
            }
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
