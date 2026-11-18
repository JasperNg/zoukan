package xyz.nulldev.androidcompat.pm

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** The app's own PackageInfo, as the emulated package manager reports it to extensions (ExternalPackages.kt). */
class ExternalPackagesTest {
    @AfterEach
    fun reset() {
        ExternalPackages.appVersionName = "0.0.0"
        ExternalPackages.appVersionCode = 0
    }

    @Test
    fun `the app's package reports the version the runtime set`() {
        ExternalPackages.appVersionName = "1.13.6"
        ExternalPackages.appVersionCode = 130_600L
        val info = ExternalPackages.appPackageInfo("app.komikku")
        info.packageName shouldBe "app.komikku"
        info.versionName shouldBe "1.13.6"
        info.longVersionCode shouldBe 130_600L
        info.applicationInfo.packageName shouldBe "app.komikku"
    }

    @Test
    fun `each call is a new PackageInfo, so an extension cannot change the app's for the next one`() {
        val first = ExternalPackages.appPackageInfo("app.komikku")
        first.versionName = "tampered"
        ExternalPackages.appPackageInfo("app.komikku").versionName shouldBe "0.0.0"
    }
}
