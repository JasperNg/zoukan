pluginManagement {
    resolutionStrategy {
        eachPlugin {
            val regex = "com.android.(library|application)".toRegex()
            if (regex matches requested.id.id) {
                useModule("com.android.tools.build:gradle:${requested.version}")
            }
        }
    }
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        maven(url = "https://www.jitpack.io")
    }
}

dependencyResolutionManagement {
    versionCatalogs {
        create("kotlinx") {
            from(files("gradle/kotlinx.versions.toml"))
        }
        create("androidx") {
            from(files("gradle/androidx.versions.toml"))
        }
        create("compose") {
            from(files("gradle/compose.versions.toml"))
        }
        create("sylibs") {
            from(files("gradle/sy.versions.toml"))
        }
        // ZOUKAN -->
        create("zoukanlibs") {
            from(files("gradle/zoukan.versions.toml"))
        }
        // ZOUKAN <--
    }
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
        maven(url = "https://www.jitpack.io")
        // ZOUKAN -->
        // Suwayomi's android.jar for the desktop runtime (the artifact Suwayomi-Server builds against).
        // Pinned to a commit of the android-jar branch, so the jar cannot change under the same
        // version; android-compat's verifyAndroidJar also checks its SHA-256.
        maven(url = "https://raw.githubusercontent.com/Suwayomi/Suwayomi-Server/7488b5cd498c0ee945c41d0b07205ec8f1e21314/") {
            content { includeModule("com.github.Suwayomi", "android-jar") }
        }
        // ZOUKAN <--
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "Komikku"
include(":app")
// ZOUKAN -->
include(":android-compat")
include(":android-stubs")
include(":backup")
include(":desktop")
include(":extension-jvm")
include(":i18n-desktop")
// ZOUKAN <--
include(":core-metadata")
include(":core:archive")
include(":core:common")
include(":data")
include(":domain")
include(":i18n")
// KMK -->
include(":i18n-kmk")
include(":flagkit")
// KMK <--
// SY -->
include(":i18n-sy")
// SY <--
include(":macrobenchmark")
include(":presentation-core")
include(":presentation-widget")
include(":source-api")
include(":source-local")
include(":telemetry")
