package zoukan.desktop.browser

import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.cef.CefSettings
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The settings Chromium starts with; no Chromium runs in these tests. */
class JcefSettingsTest {
    @Test
    fun `Chromium does not persist session cookies`(@TempDir dir: File) {
        val settings = CefSettings()
        JcefRuntime.applySettings(settings, File(dir, "cache"), dir, debugLogging = false)
        settings.persist_session_cookies shouldBe false
    }

    @Test
    fun `Chromium writes no log unless debugging`(@TempDir dir: File) {
        val settings = CefSettings()
        JcefRuntime.applySettings(settings, File(dir, "cache"), dir, debugLogging = false)
        settings.log_severity shouldBe CefSettings.LogSeverity.LOGSEVERITY_DISABLE
        settings.log_file shouldBe null
    }

    @Test
    fun `Chromium logs verbosely to the browser folder when debugging`(@TempDir dir: File) {
        val settings = CefSettings()
        JcefRuntime.applySettings(settings, File(dir, "cache"), dir, debugLogging = true)
        settings.log_severity shouldBe CefSettings.LogSeverity.LOGSEVERITY_VERBOSE
        settings.log_file shouldBe File(dir, "chromium.log").absolutePath
    }

    @Test
    fun `every disabled feature is in one switch`() {
        val switches = JcefRuntime.PRIVACY_SWITCHES.filter { it.startsWith("--disable-features=") }
        switches.size shouldBe 1
        switches.single().removePrefix("--disable-features=").split(',') shouldContainAll listOf(
            "OptimizationHints",
            "Reporting",
            "NetworkErrorLogging",
            "NetworkTimeServiceQuerying",
        )
    }

    @Test
    fun `the HTTP and media caches are kept to the minimum`() {
        JcefRuntime.PRIVACY_SWITCHES shouldContainAll listOf("--disk-cache-size=1", "--media-cache-size=1")
    }

    @Test
    fun `the profile keeps no history and sends nothing it does not need`() {
        val prefs = JcefRuntime.PRIVACY_PREFERENCES
        prefs["safebrowsing.enabled"] shouldBe false
        prefs["history.saving_disabled"] shouldBe true
        prefs["net.network_prediction_options"] shouldBe 2
        prefs["webrtc.ip_handling_policy"] shouldBe "disable_non_proxied_udp"
        prefs["browser.enable_spellchecking"] shouldBe false
        prefs["credentials_enable_service"] shouldBe false
        prefs["autofill.profile_enabled"] shouldBe false
        prefs["autofill.credit_card_enabled"] shouldBe false
    }
}
