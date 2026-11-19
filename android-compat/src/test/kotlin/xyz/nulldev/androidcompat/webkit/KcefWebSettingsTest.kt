package xyz.nulldev.androidcompat.webkit

import android.webkit.WebSettings
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class KcefWebSettingsTest {
    private var previousProvider: (() -> String?)? = null
    private var previousHttpAgent: String? = null

    @BeforeEach
    fun saveGlobals() {
        previousProvider = KcefWebSettings.userAgentProvider
        previousHttpAgent = System.getProperty("http.agent")
    }

    @AfterEach
    fun restoreGlobals() {
        KcefWebSettings.userAgentProvider = previousProvider
        if (previousHttpAgent == null) {
            System.clearProperty("http.agent")
        } else {
            System.setProperty("http.agent", previousHttpAgent)
        }
    }

    @Test
    fun `exposes safe defaults matching expected WebView baseline`() {
        val settings: WebSettings = KcefWebSettings()

        settings.javaScriptEnabled shouldBe true
        settings.domStorageEnabled shouldBe true
        @Suppress("DEPRECATION")
        settings.cacheMode shouldBe WebSettings.LOAD_NORMAL
        settings.safeBrowsingEnabled shouldBe true
        settings.mediaPlaybackRequiresUserGesture shouldBe true
        settings.allowFileAccess shouldBe true
        settings.allowContentAccess shouldBe true
        settings.allowUniversalAccessFromFileURLs shouldBe false
        settings.allowFileAccessFromFileURLs shouldBe false
        settings.savePassword shouldBe false
        settings.supportMultipleWindows() shouldBe false
        settings.javaScriptCanOpenWindowsAutomatically shouldBe false
        settings.loadsImagesAutomatically shouldBe true
        settings.blockNetworkImage shouldBe false
        settings.blockNetworkLoads shouldBe false
        settings.acceptThirdPartyCookies shouldBe true
        settings.textZoom shouldBe 100
        settings.minimumFontSize shouldBe 8
        settings.minimumLogicalFontSize shouldBe 8
        settings.defaultFontSize shouldBe 16
        settings.defaultFixedFontSize shouldBe 13
        settings.databasePath shouldBe ""
        settings.defaultTextEncodingName shouldBe ""
        settings.databaseEnabled shouldBe true
    }

    @Test
    fun `updates javascript dom storage cache mode and other toggles`() {
        val kcef = KcefWebSettings()
        val settings: WebSettings = kcef

        settings.javaScriptEnabled = false
        settings.javaScriptEnabled shouldBe false

        settings.domStorageEnabled = false
        settings.domStorageEnabled shouldBe false

        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        settings.cacheMode shouldBe WebSettings.LOAD_NO_CACHE

        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.allowUniversalAccessFromFileURLs = true
        settings.allowFileAccessFromFileURLs = true
        settings.blockNetworkImage = true
        settings.blockNetworkLoads = true
        settings.loadsImagesAutomatically = false
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(true)
        settings.mediaPlaybackRequiresUserGesture = false
        kcef.setAcceptThirdPartyCookies(false)
        settings.safeBrowsingEnabled = false
        settings.textZoom = 125
        settings.defaultFontSize = 20
        settings.defaultFixedFontSize = 15
        settings.minimumFontSize = 10
        settings.minimumLogicalFontSize = 11
        settings.standardFontFamily = "sans-serif"
        settings.fixedFontFamily = "monospace"
        settings.defaultTextEncodingName = "UTF-8"
        settings.databasePath = "/tmp/db"
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.layoutAlgorithm = WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING

        settings.allowFileAccess shouldBe false
        settings.allowContentAccess shouldBe false
        settings.allowUniversalAccessFromFileURLs shouldBe true
        settings.allowFileAccessFromFileURLs shouldBe true
        settings.blockNetworkImage shouldBe true
        settings.blockNetworkLoads shouldBe true
        settings.loadsImagesAutomatically shouldBe false
        settings.javaScriptCanOpenWindowsAutomatically shouldBe true
        settings.supportMultipleWindows() shouldBe true
        settings.mediaPlaybackRequiresUserGesture shouldBe false
        kcef.getAcceptThirdPartyCookies() shouldBe false
        settings.safeBrowsingEnabled shouldBe false
        settings.textZoom shouldBe 125
        settings.defaultFontSize shouldBe 20
        settings.defaultFixedFontSize shouldBe 15
        settings.minimumFontSize shouldBe 10
        settings.minimumLogicalFontSize shouldBe 11
        settings.standardFontFamily shouldBe "sans-serif"
        settings.fixedFontFamily shouldBe "monospace"
        settings.defaultTextEncodingName shouldBe "UTF-8"
        settings.databasePath shouldBe "/tmp/db"
        settings.mixedContentMode shouldBe WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.layoutAlgorithm shouldBe WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING

        shouldThrow<RuntimeException> { settings.userAgent = 1 }
        shouldThrow<RuntimeException> { settings.userAgent }
    }

    @Test
    fun `userAgentProvider falls back to http agent and custom UA clears client hints`() {
        val defaultUa = "Mozilla/5.0 Chrome/152.0.0.0 Safari/537.36"
        System.setProperty("http.agent", "FallbackAgent/1.0")
        KcefWebSettings.userAgentProvider = null
        KcefWebSettings.defaultUserAgent() shouldBe "FallbackAgent/1.0"

        KcefWebSettings.userAgentProvider = { error("provider unavailable") }
        KcefWebSettings.defaultUserAgent() shouldBe "FallbackAgent/1.0"

        KcefWebSettings.userAgentProvider = { defaultUa }
        val settings = KcefWebSettings()
        settings.userAgentString shouldBe defaultUa
        settings.getUserAgentMetadata().shouldNotBeNull()

        // Custom UA suppresses desktop Chromium client hints unless explicitly provided
        settings.userAgentString = "CustomMobileApp/2.0"
        settings.userAgentString shouldBe "CustomMobileApp/2.0"
        settings.getUserAgentMetadata().shouldBeNull()

        // Explicitly setting metadata attaches it to the custom UA
        settings.setUserAgentMetadataFromMap(
            mapOf(
                KcefWebSettings.UserAgentMetadata.PLATFORM to "Android",
                KcefWebSettings.UserAgentMetadata.PLATFORM_VERSION to "14.0.0",
                KcefWebSettings.UserAgentMetadata.MOBILE to true,
                KcefWebSettings.UserAgentMetadata.BRAND_VERSION_LIST to listOf(
                    arrayOf("Chromium", "120", "120.0.0.0"),
                ),
                KcefWebSettings.UserAgentMetadata.FORM_FACTORS to arrayOf("Mobile"),
            ),
        )
        val customMeta = settings.getUserAgentMetadata().shouldNotBeNull()
        customMeta.platform shouldBe "Android"
        customMeta.mobile shouldBe true
        customMeta.formFactors shouldBe listOf("Mobile")
        val map = settings.getUserAgentMetadataMap()
        map[KcefWebSettings.UserAgentMetadata.PLATFORM] shouldBe "Android"

        // Resetting UA back to null clears custom metadata and restores default client hints
        settings.setUserAgentString(null)
        settings.userAgentString shouldBe defaultUa
        settings.getUserAgentMetadata().shouldNotBeNull()
    }

    @Test
    fun `setUserAgentMetadataFromMap rejects malformed inputs`() {
        val settings = KcefWebSettings()

        shouldThrow<IllegalArgumentException> {
            settings.setUserAgentMetadataFromMap(
                mapOf(KcefWebSettings.UserAgentMetadata.BRAND_VERSION_LIST to "not-a-list"),
            )
        }
        shouldThrow<IllegalArgumentException> {
            settings.setUserAgentMetadataFromMap(
                mapOf(KcefWebSettings.UserAgentMetadata.BRAND_VERSION_LIST to listOf(arrayOf("Chromium", "120"))),
            )
        }
        shouldThrow<IllegalArgumentException> {
            settings.setUserAgentMetadataFromMap(
                mapOf(KcefWebSettings.UserAgentMetadata.MOBILE to "true"),
            )
        }
        shouldThrow<IllegalArgumentException> {
            settings.setUserAgentMetadataFromMap(
                mapOf(KcefWebSettings.UserAgentMetadata.PLATFORM to 123),
            )
        }
    }

    @Test
    fun `desktopUserAgentMetadata builds OS architecture and Chromium version hints`() {
        val macMeta = KcefWebSettings.desktopUserAgentMetadata(
            userAgent = "Mozilla/5.0 Chrome/152.0.0.0",
            chromiumFullVersion = "152.0.7977.83",
            osName = "Mac OS X",
            osVersion = "15.2",
            osArch = "aarch64",
        )
        macMeta.platform shouldBe "macOS"
        macMeta.platformVersion shouldBe "15.2.0"
        macMeta.architecture shouldBe "arm"
        macMeta.bitness shouldBe "64"
        macMeta.mobile shouldBe false
        macMeta.formFactors shouldBe listOf("Desktop")
        macMeta.fullVersion shouldBe "152.0.7977.83"

        val winMeta = KcefWebSettings.desktopUserAgentMetadata(
            userAgent = "Mozilla/5.0 Chrome/152.0.0.0",
            chromiumFullVersion = "152.0.7977.83",
            osName = "Windows 11",
            osVersion = "10.0",
            osArch = "amd64",
            windowsBuild = 26100,
        )
        winMeta.platform shouldBe "Windows"
        winMeta.platformVersion shouldBe "19.0.0"
        winMeta.architecture shouldBe "x86"
        winMeta.bitness shouldBe "64"

        KcefWebSettings.windowsPlatformVersion(19045) shouldBe "10.0.0"
        KcefWebSettings.windowsPlatformVersion(22000) shouldBe "14.0.0"
        KcefWebSettings.windowsPlatformVersion(22631) shouldBe "15.0.0"
        KcefWebSettings.parseWindowsBuild("    CurrentBuildNumber    REG_SZ    22631") shouldBe 22631
    }
}
