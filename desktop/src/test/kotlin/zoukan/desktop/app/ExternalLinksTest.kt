package zoukan.desktop.app

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ExternalLinksTest {
    @Test
    fun `only web addresses are opened`() {
        ExternalLinks.isWebUrl("https://mangadex.org/title/1") shouldBe true
        ExternalLinks.isWebUrl(" HTTP://example.org ") shouldBe true
        listOf(
            "file:///C:/Windows/System32/calc.exe",
            "file://server/share/run.bat",
            "C:\\Windows\\System32\\calc.exe",
            "ms-msdt:/id PCWDiagnostic",
            "search-ms:query=x",
            "javascript:alert(1)",
            "komikku://anilist-auth",
            "https:///no-host",
            "not a url",
            "",
        ).forEach { ExternalLinks.isWebUrl(it) shouldBe false }
    }
}
