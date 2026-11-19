package zoukan.desktop.browser

import eu.kanade.tachiyomi.network.interceptor.DesktopBrowser
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import xyz.nulldev.androidcompat.webkit.KcefWebSettings
import xyz.nulldev.androidcompat.webkit.KcefWebSettings.UserAgentBrandVersion
import java.io.File

/**
 * The built-in browser keeps Chromium's own user agent, learns what Chromium reports for
 * [DesktopBrowser.userAgent], and supplies matching desktop client-hint metadata for WebViews.
 */
class BrowserUserAgentAndMetadataTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val assumed = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36"
    private val reported = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36"

    @AfterEach
    fun reset() {
        KcefWebSettings.userAgentProvider = null
    }

    // --- Built-in browser user-agent guard & Chromium reporting ---

    @Test
    fun `the built-in browser never overrides Chromium's user agent`() {
        val sources = File(root, "desktop/src/main/kotlin/zoukan/desktop/browser")
            .walk().filter { it.isFile && it.extension == "kt" }.toList()
        sources.shouldNotBeEmpty()
        sources.filter { "setUserAgentOverride" in it.readText() }.map { it.name }.shouldBeEmpty()
    }

    @Test
    fun `a valid reported user agent wins over the assumed one`() {
        JcefRuntime.chromiumUserAgent(reported, assumed) shouldBe reported
    }

    @Test
    fun `a failed or implausible report keeps the assumed user agent`() {
        listOf(
            null,
            "",
            "   ",
            "null",
            "Chrome/153.0.0.0",
            "$reported\nX-Injected: 1",
            "$reported\rX-Injected: 1",
        ).forEach { JcefRuntime.chromiumUserAgent(it, assumed) shouldBe assumed }
    }

    @Test
    fun `learning the reported user agent sets the one the app records clearances with`() {
        val previous = DesktopBrowser.userAgent
        try {
            DesktopBrowser.userAgent = assumed
            JcefRuntime.learnUserAgent("null")
            DesktopBrowser.userAgent shouldBe assumed

            JcefRuntime.learnUserAgent(reported)
            DesktopBrowser.userAgent shouldBe reported
            JcefRuntime.userAgentKnown shouldBe true
        } finally {
            DesktopBrowser.userAgent = previous
        }
    }

    // --- Client-hint metadata for extensions' WebViews ---

    @Test
    fun `desktop metadata matches the user agent's Chromium and this OS`() {
        val meta = KcefWebSettings.desktopUserAgentMetadata(assumed, "152.0.7977.83", "Mac OS X", "15.2", "aarch64")
        meta.mobile shouldBe false
        meta.model shouldBe ""
        meta.platform shouldBe "macOS"
        meta.platformVersion shouldBe "15.2.0"
        meta.architecture shouldBe "arm"
        meta.bitness shouldBe "64"
        meta.formFactors shouldBe listOf("Desktop")
        meta.fullVersion shouldBe "152.0.7977.83"
        meta.brands shouldBe listOf(UserAgentBrandVersion("Not?A_Brand", "24"), UserAgentBrandVersion("Chromium", "152"))
        meta.fullVersionList shouldBe
            listOf(UserAgentBrandVersion("Not?A_Brand", "24.0.0.0"), UserAgentBrandVersion("Chromium", "152.0.7977.83"))
    }

    @Test
    fun `the GREASE brand follows Chromium's rule`() {
        // Chromium 149 reports [Chromium 149, Not)A;Brand 24]
        val meta = KcefWebSettings.desktopUserAgentMetadata("Chrome/149.0.0.0", null, "Linux", "6.8.0-45-generic", "amd64")
        meta.brands shouldBe listOf(UserAgentBrandVersion("Chromium", "149"), UserAgentBrandVersion("Not)A;Brand", "24"))
        meta.platform shouldBe "Linux"
        meta.platformVersion shouldBe "6.8.0"
        meta.architecture shouldBe "x86"
        // A full version of another Chromium is not used
        meta.fullVersion shouldBe "149.0.0.0"
    }

    @Test
    fun `Windows reports its platform version as Chromium does`() {
        KcefWebSettings.desktopUserAgentMetadata(assumed, null, "Windows 10", "10.0", "amd64").let {
            it.platform shouldBe "Windows"
            it.platformVersion shouldBe "10.0.0"
        }
        KcefWebSettings.desktopUserAgentMetadata(assumed, null, "Windows 11", "10.0", "amd64").platformVersion shouldBe "19.0.0"
    }

    @Test
    fun `a known Windows build gives Chromium's platform version`() {
        fun version(osName: String, build: Int?) =
            KcefWebSettings.desktopUserAgentMetadata(assumed, null, osName, "10.0", "amd64", windowsBuild = build).platformVersion
        version("Windows 10", 19045) shouldBe "10.0.0"
        version("Windows 10", 17763) shouldBe "7.0.0"
        version("Windows 11", 22000) shouldBe "14.0.0"
        version("Windows 11", 22621) shouldBe "15.0.0"
        version("Windows 11", 22631) shouldBe "15.0.0"
        version("Windows 11", 26100) shouldBe "19.0.0"
        version("Windows 11", 26200) shouldBe "19.0.0"
        // Older than Windows 10, or unknown: the name decides, as before
        version("Windows 11", 9600) shouldBe "19.0.0"
        version("Windows 10", null) shouldBe "10.0.0"
        // Other systems ignore it
        KcefWebSettings.desktopUserAgentMetadata(assumed, null, "Mac OS X", "15.2", "aarch64", windowsBuild = 22631)
            .platformVersion shouldBe "15.2.0"
    }

    @Test
    fun `the Windows release table follows the UniversalApiContract versions`() {
        KcefWebSettings.windowsPlatformVersion(10240) shouldBe "1.0.0"
        KcefWebSettings.windowsPlatformVersion(18363) shouldBe "8.0.0"
        KcefWebSettings.windowsPlatformVersion(19041) shouldBe "10.0.0"
        KcefWebSettings.windowsPlatformVersion(20348) shouldBe "10.0.0"
        KcefWebSettings.windowsPlatformVersion(10239).shouldBeNull()
    }

    @Test
    fun `the build number is read from reg query's output`() {
        val output = "\r\nHKEY_LOCAL_MACHINE\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\r\n" +
            "    CurrentBuildNumber    REG_SZ    22631\r\n\r\n"
        KcefWebSettings.parseWindowsBuild(output) shouldBe 22631
        KcefWebSettings.parseWindowsBuild("ERROR: The system was unable to find the specified registry key or value.").shouldBeNull()
        KcefWebSettings.parseWindowsBuild("").shouldBeNull()
    }

    @Test
    fun `no user agent or version still gives desktop metadata`() {
        val meta = KcefWebSettings.desktopUserAgentMetadata(null, null, "", "", "")
        meta.mobile shouldBe false
        meta.brands.shouldNotBeNull().map { it.brand }.contains("Chromium") shouldBe true
    }

    @Test
    fun `a WebView on the default user agent sends desktop metadata, an extension's own user agent none`() {
        KcefWebSettings.userAgentProvider = { assumed }
        val settings = KcefWebSettings()
        settings.userAgentString shouldBe assumed
        settings.getUserAgentMetadata().shouldNotBeNull().mobile shouldBe false

        settings.userAgentString = "Mozilla/5.0 (Linux; Android 10; K) Chrome/120.0.0.0 Mobile Safari/537.36"
        settings.getUserAgentMetadata().shouldBeNull()

        settings.userAgentString = assumed
        settings.getUserAgentMetadata().shouldNotBeNull().platform shouldBe KcefWebSettings.defaultUserAgentMetadata().platform
    }

    @Test
    fun `a failing provider falls back to http agent`() {
        KcefWebSettings.userAgentProvider = { error("not ready") }
        KcefWebSettings.defaultUserAgent() shouldBe (System.getProperty("http.agent") ?: "")
    }
}
