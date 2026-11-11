plugins {
    // ZOUKAN -->
    id("mihon.library.kmp")
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
                implementation(kotlinx.bundles.coroutines)
                implementation(kotlinx.bundles.serialization)

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
            }
        }
        androidUnitTest {
            dependencies {
                implementation(libs.bundles.test)
                implementation(kotlinx.coroutines.test)
                runtimeOnly(libs.junit.platform.launcher)
            }
        }
    }
    // ZOUKAN <--
}
