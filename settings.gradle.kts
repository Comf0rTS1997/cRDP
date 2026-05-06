pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "cRdp"

include(
    ":app",
    ":core:ui",
    ":core:rdp",
    ":core:rdp-engine",
    ":feature:connections",
    ":feature:session",
    ":rdp-direct",
    ":rdp-gateway",
)

// Engine selection at configure time. Default = "stub" (StubRdpEngine, no native deps).
// Set -Pcrdp.engine=afreerdp (or env CRDP_ENGINE=afreerdp) to compile with the FreeRDP
// adapter and the vendored aFreeRDP module.
val crdpEngine: String =
    providers.gradleProperty("crdp.engine").orNull
        ?: System.getenv("CRDP_ENGINE")
        ?: "stub"

if (crdpEngine == "afreerdp") {
    include(":engine:afreerdp")

    // We do NOT include freeRDPCore (the demo Android app) as a Gradle subproject.
    // It depends on appcompat-v7:28 and on demo Activities/Fragments we don't want.
    // Instead, :engine:afreerdp ships its own thin LibFreeRDP shim and consumes the
    // FreeRDP .so artifacts produced by scripts/build-freerdp.{sh,ps1}.
    val freerdpRoot = file("third_party/FreeRDP")
    if (!freerdpRoot.isDirectory) {
        logger.warn(
            "[cRDP] crdp.engine=afreerdp but FreeRDP submodule is missing at $freerdpRoot. " +
                "Run: git submodule update --init --recursive",
        )
    }
}
