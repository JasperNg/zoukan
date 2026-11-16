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
                // Only constants used inside; no i18n type appears in a public signature
                implementation(projects.i18n)
                implementation(projects.i18nSy)
                implementation(projects.i18nKmk)

                implementation(project.dependencies.platform(kotlinx.coroutines.bom))
                implementation(kotlinx.coroutines.core)
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
        androidMain {
            dependencies {
                // Android only: the desktop app uses Swing's main dispatcher
                implementation(project.dependencies.platform(kotlinx.coroutines.bom))
                implementation(kotlinx.coroutines.android)
                implementation(kotlinx.coroutines.guava)
            }
        }
    }
}
// ZOUKAN <--
