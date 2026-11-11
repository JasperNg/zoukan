plugins {
    // ZOUKAN -->
    id("mihon.library.kmp")
    // ZOUKAN <--
    kotlin("plugin.serialization")
    id("com.github.ben-manes.versions")
}

android {
    namespace = "eu.kanade.tachiyomi.core.common"
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.serialization.ExperimentalSerializationApi",
        )
    }

    // ZOUKAN -->
    sourceSets {
        commonMain {
            dependencies {
                implementation(projects.i18n)
                // SY -->
                implementation(projects.i18nSy)
                // SY <--

                api(libs.logcat)

                api(libs.rxjava)

                api(libs.okhttp.core)
                api(libs.okhttp.logging)
                api(libs.okhttp.brotli)
                api(libs.okhttp.dnsoverhttps)
                api(libs.okio)

                api(kotlinx.coroutines.core)
                api(kotlinx.serialization.json)
                api(kotlinx.serialization.json.okio)

                implementation(libs.jsoup)

                // Sort
                implementation(libs.natural.comparator)

                // Android types shared with desktop (Context, CookieManager); see android-stubs.
                compileOnly(projects.androidStubs)
            }
        }
        androidMain {
            dependencies {
                implementation(libs.image.decoder)

                implementation(libs.unifile)
                implementation(libs.libarchive)

                api(libs.preferencektx)

                // JavaScript engine
                implementation(libs.bundles.js.engine)

                // SY -->
                implementation(sylibs.xlog)
                implementation(sylibs.exifinterface)
                // SY <--
            }
        }
        androidUnitTest {
            dependencies {
                implementation(libs.bundles.test)
                runtimeOnly(libs.junit.platform.launcher)
            }
        }
    }
    // ZOUKAN <--
}
