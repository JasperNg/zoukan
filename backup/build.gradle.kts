// ZOUKAN -->
// The backup format and the code that creates and restores backups, shared by the Android app and
// the desktop app so both always read and write the same `.tachibk` files.
//
// Package names are unchanged from the Android app (`eu.kanade.tachiyomi.data.backup`).
plugins {
    id("mihon.library.kmp")
    kotlin("plugin.serialization")
}

android {
    namespace = "eu.kanade.tachiyomi.data.backup"
}

kotlin {
    compilerOptions {
        optIn.add("kotlinx.serialization.ExperimentalSerializationApi")
    }

    sourceSets {
        commonMain {
            dependencies {
                api(projects.domain)
                api(projects.data)
                api(projects.sourceApi)
                api(projects.core.common)
                api(projects.i18n)
                api(projects.i18nSy)
                api(projects.i18nKmk)

                implementation(project.dependencies.platform(kotlinx.coroutines.bom))
                implementation(kotlinx.bundles.coroutines)
                api(kotlinx.serialization.json)
                api(kotlinx.serialization.protobuf)
                implementation(kotlinx.immutables)
                implementation(libs.okio)
                implementation(libs.injekt)
                implementation(libs.logcat)

                // Android types in shared code (SharedPreferences); see android-stubs.
                compileOnly(projects.androidStubs)
            }
        }
    }
}
// ZOUKAN <--
