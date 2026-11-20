plugins {
    id("mihon.library.kmp")
    kotlin("plugin.serialization")
    id("com.github.ben-manes.versions")
    // ZOUKAN -->
    id("zoukan.kover")
    id("zoukan.test-home")
    // ZOUKAN <--
}

kotlin {
    android {
        namespace = "eu.kanade.tachiyomi.source"
        optimization {
            consumerKeepRules.file("consumer-proguard.pro")
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                api(kotlinx.serialization.json)
                api(libs.injekt)
                api(libs.rxjava)
                api(libs.jsoup)

                // SY -->
                api(projects.i18n)
                api(projects.i18nSy)
                api(kotlinx.reflect)
                // SY <--

                implementation(project.dependencies.platform(compose.bom))
                implementation(compose.runtime)

                // ZOUKAN -->
                // The extension API exposes a few Android types (Application, Context,
                // SharedPreferences, Uri, PreferenceScreen); see android-stubs.
                compileOnly(projects.androidStubs)
                implementation(projects.core.common)
                // ZOUKAN <--
            }
        }
        androidMain {
            dependencies {
                implementation(projects.core.common)
                api(libs.preferencektx)

                // Workaround for https://youtrack.jetbrains.com/issue/KT-57605
                implementation(kotlinx.coroutines.android)
                implementation(project.dependencies.platform(kotlinx.coroutines.bom))
            }
        }
        // ZOUKAN -->
        // The module's own tests run on the desktop target, as the desktop app uses the module:
        //   ./gradlew :source-api:desktopTest
        getByName("desktopTest") {
            dependencies {
                implementation(libs.bundles.test)
                implementation(kotlinx.coroutines.test)
                // The Android classes the extension API names (Uri, SharedPreferences), as the
                // desktop app supplies them at run time
                implementation(projects.androidCompat)
                runtimeOnly(libs.junit.platform.launcher)
            }
        }
        // ZOUKAN <--
    }
}
