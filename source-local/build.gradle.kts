import mihon.buildlogic.AndroidConfig
import mihon.buildlogic.checkMultiplatformSourceLayout
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    id("com.android.kotlin.multiplatform.library")
    kotlin("multiplatform")
    id("mihon.code.lint")
}

kotlin {
    android {
        compileSdk = AndroidConfig.COMPILE_SDK
        minSdk = AndroidConfig.MIN_SDK
        namespace = "tachiyomi.source.local"

        compilerOptions {
            jvmTarget.set(AndroidConfig.JvmTarget)
        }

        optimization {
            consumerKeepRules.file("consumer-rules.pro")
        }

        withHostTest {}
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(projects.sourceApi)
                api(projects.i18n)
                // SY -->
                api(projects.i18nSy)
                // SY <--

                implementation(libs.unifile)
            }
        }
        androidMain {
            dependencies {
                implementation(projects.core.archive)
                implementation(projects.core.common)
                implementation(projects.coreMetadata)

                // Move ChapterRecognition to separate module?
                implementation(projects.domain)

                implementation(kotlinx.bundles.serialization)
            }
        }
    }

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xexpect-actual-classes",
            "-opt-in=kotlinx.serialization.ExperimentalSerializationApi",
        )
    }
}

checkMultiplatformSourceLayout()
