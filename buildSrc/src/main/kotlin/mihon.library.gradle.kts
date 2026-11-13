import mihon.buildlogic.configureAndroid
import mihon.buildlogic.configureTest
// ZOUKAN -->
import mihon.buildlogic.checkMultiplatformSourceLayout
// ZOUKAN <--

plugins {
    id("com.android.library")

    id("mihon.code.lint")
}

android {
    configureAndroid(this)
    configureTest()
}

// ZOUKAN -->
// Every multiplatform library (mihon.library.kmp, source-api, source-local, i18n*): no sources in src/main
plugins.withId("org.jetbrains.kotlin.multiplatform") {
    checkMultiplatformSourceLayout()
}
// ZOUKAN <--
