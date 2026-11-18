plugins {
    // ZOUKAN -->
    id("mihon.library.kmp")
    id("zoukan.kover")
    id("zoukan.test-home")
    // ZOUKAN <--
    kotlin("plugin.serialization")
}

android {
    namespace = "tachiyomi.domain"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi")
    }

    // ZOUKAN -->
    sourceSets {
        commonMain {
            dependencies {
                implementation(projects.sourceApi)
                implementation(projects.core.common)

                implementation(project.dependencies.platform(kotlinx.coroutines.bom))
                implementation(kotlinx.coroutines.core)
                implementation(kotlinx.serialization.json)
                implementation(kotlinx.serialization.json.okio)
                implementation(kotlinx.serialization.protobuf)
                implementation(kotlinx.serialization.xml)

                api(libs.sqldelight.android.paging)

                compileOnly(project.dependencies.platform(compose.bom))
                compileOnly(compose.runtime.annotation)

                // Android types in shared models (e.g. Extension icons); see android-stubs.
                compileOnly(projects.androidStubs)
            }
        }
        androidMain {
            dependencies {
                implementation(libs.unifile)

                // Android only: the desktop app uses Swing's main dispatcher and the JVM XML core
                implementation(project.dependencies.platform(kotlinx.coroutines.bom))
                implementation(kotlinx.coroutines.android)
                implementation(kotlinx.coroutines.guava)
                implementation(kotlinx.serialization.xml.core)
            }
        }
        androidUnitTest {
            dependencies {
                implementation(libs.bundles.test)
                implementation(kotlinx.coroutines.test)
                runtimeOnly(libs.junit.platform.launcher)
            }
        }
        // Komikku's domain tests also run against the desktop target, as they are
        //   ./gradlew :domain:desktopTest
        getByName("desktopTest") {
            kotlin.srcDir("src/androidUnitTest/kotlin")
            dependencies {
                implementation(libs.bundles.test)
                implementation(kotlinx.coroutines.test)
                runtimeOnly(libs.junit.platform.launcher)
            }
        }
    }
    // ZOUKAN <--
}
