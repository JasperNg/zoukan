buildscript {
    dependencies {
        classpath(libs.android.shortcut.gradle)
        classpath(sylibs.gradleversionsx)
    }
}

plugins {
    alias(kotlinx.plugins.serialization) apply false
    alias(libs.plugins.aboutLibraries) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.moko) apply false
    alias(libs.plugins.sqldelight) apply false
    // ZOUKAN -->
    // Test coverage; the version comes with buildSrc (zoukan.kover)
    id("org.jetbrains.kotlinx.kover")
    // ZOUKAN <--
}

// ZOUKAN -->
// One coverage report for the modules whose code the desktop tests run (desktop/TESTING.md,
// "Coverage"): `./gradlew :koverXmlReportUnit` writes build/reports/kover/reportUnit.xml. Each of
// these modules applies the zoukan.kover convention (buildSrc), which defines its "unit" variant.
// No threshold is set.
kover {
    currentProject {
        createVariant("unit") {}
    }
}
dependencies {
    kover(projects.desktop)
    kover(projects.extensionJvm)
    kover(projects.androidCompat)
    kover(projects.backup)
    kover(projects.domain)
    kover(projects.data)
    kover(projects.core.common)
    kover(projects.sourceApi)
}
// ZOUKAN <--

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
