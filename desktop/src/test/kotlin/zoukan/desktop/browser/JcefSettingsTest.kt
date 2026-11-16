package zoukan.desktop.browser

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
}
