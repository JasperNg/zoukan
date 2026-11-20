// ZOUKAN -->
import mihon.buildlogic.AndroidConfig
import mihon.buildlogic.checkMultiplatformSourceLayout
import mihon.buildlogic.configureTest
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

/**
 * Kotlin Multiplatform library with an Android target and a JVM `desktop` target.
 *
 * Code shared by both apps lives in `commonMain`; Android-only code in `androidMain`;
 * desktop-only code in `desktopMain`.
 */
plugins {
    id("com.android.kotlin.multiplatform.library")
    kotlin("multiplatform")
    id("mihon.code.lint")
}

kotlin {
    android {
        compileSdk = AndroidConfig.COMPILE_SDK
        minSdk = AndroidConfig.MIN_SDK

        compilerOptions {
            jvmTarget.set(AndroidConfig.JvmTarget)
        }
    }
    jvm("desktop")

    applyDefaultHierarchyTemplate()

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xexpect-actual-classes",
            "-opt-in=kotlin.RequiresOptIn",
        )
    }
}

configureTest()
checkMultiplatformSourceLayout()
// ZOUKAN <--
