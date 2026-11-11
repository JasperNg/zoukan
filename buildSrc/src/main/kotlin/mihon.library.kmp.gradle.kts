// ZOUKAN -->
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

/**
 * Kotlin Multiplatform library with an Android target and a JVM `desktop` target.
 *
 * Code shared by both apps lives in `commonMain`; Android-only code in `androidMain`;
 * desktop-only code in `desktopMain`.
 */
plugins {
    id("mihon.library")
    kotlin("multiplatform")
}

kotlin {
    androidTarget()
    jvm("desktop")

    applyDefaultHierarchyTemplate()

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
}
// ZOUKAN <--
