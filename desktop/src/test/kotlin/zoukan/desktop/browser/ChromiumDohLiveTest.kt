package zoukan.desktop.browser

import io.mockk.every
import io.mockk.mockk
import me.friwi.jcefmaven.CefAppBuilder
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter
import org.cef.browser.CefBrowser
import org.cef.browser.CefBrowserHeadless
import org.cef.browser.CefFrame
import org.cef.browser.CefRequestContext
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRenderHandler
import org.cef.network.CefRequest
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.awt.Rectangle
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Checks that Chromium resolves names with the DNS over HTTPS server ChromiumDns writes to its
 * Local State, in secure mode (no fallback to the system's DNS). Needs an installed Chromium and
 * the internet, so it runs only when asked, one case per JVM (Chromium starts once per process):
 *
 *   ZOUKAN_LIVE=true ZOUKAN_CEF_DIR=<data>/browser ZOUKAN_CEF_TEMPLATE=<url> ./gradlew :desktop:test --tests "*ChromiumDohLiveTest"
 *
 * With an unreachable template (https://127.0.0.1:9/dns-query) the page must fail to resolve,
 * proving Chromium neither ignores the preference nor falls back; with a provider's it must load.
 *
 * Chromium runs with the app's privacy switches and preferences (JcefRuntime.PRIVACY_SWITCHES,
 * Safe Browsing off) unless ZOUKAN_CEF_PRIVACY=off, and a loaded page must still run JavaScript
 * (as a Cloudflare challenge does): a script sets document.title, read back through the display handler.
 */
class ChromiumDohLiveTest {
    @Test
    fun `Chromium resolves through the Local State DoH server`() {
        assumeTrue(System.getenv("ZOUKAN_LIVE") == "true")
        val installDir = System.getenv("ZOUKAN_CEF_DIR")?.let(::File)
        assumeTrue(installDir?.isDirectory == true)
        val template = System.getenv("ZOUKAN_CEF_TEMPLATE")?.takeIf { it != "off" }
        val url = System.getenv("ZOUKAN_CEF_URL") ?: "https://example.com/"
        val cache = Files.createTempDirectory("zoukan-cef-doh").toFile()
        val privacy = System.getenv("ZOUKAN_CEF_PRIVACY") != "off"
        val safeBrowsing = CompletableFuture<Any?>()
        // As JcefRuntime does before Chromium starts
        ChromiumDns.updateLocalState(null, template)?.let { File(cache, "Local State").writeText(it) }

        val app = CefAppBuilder().apply {
            setInstallDir(installDir)
            cefSettings.windowless_rendering_enabled = true
            cefSettings.root_cache_path = cache.absolutePath
            cefSettings.cache_path = cache.absolutePath
            // Extra switches, separated by "|" (for --host-resolver-rules, whose value has spaces)
            System.getenv("ZOUKAN_CEF_ARGS")?.split('|')?.filter { it.isNotBlank() }?.let { addJcefArgs(*it.toTypedArray()) }
            if (privacy) addJcefArgs(*JcefRuntime.PRIVACY_SWITCHES.toTypedArray())
            setAppHandler(
                object : MavenCefAppHandlerAdapter() {
                    override fun onContextInitialized() {
                        if (privacy) JcefRuntime.applyPrivacyPreferences()
                        safeBrowsing.complete(
                            runCatching { CefRequestContext.getGlobalContext().getPreference("safebrowsing.enabled") }
                                .getOrElse { "error: $it" },
                        )
                    }
                },
            )
        }.build()
        val result = CompletableFuture<String>()
        val title = CompletableFuture<String>()
        val client = app.createClient()
        client.addDisplayHandler(object : CefDisplayHandlerAdapter() {
            override fun onTitleChange(browser: CefBrowser, newTitle: String?) {
                if (newTitle?.startsWith("zk-") == true) title.complete(newTitle)
            }
        })
        client.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(browser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                if (frame.isMain) result.complete("LOADED HTTP $httpStatusCode")
            }

            override fun onLoadError(
                browser: CefBrowser,
                frame: CefFrame,
                errorCode: CefLoadHandler.ErrorCode,
                errorText: String?,
                failedUrl: String?,
            ) {
                if (frame.isMain) result.complete("ERROR $errorCode $errorText")
            }

            override fun onLoadStart(browser: CefBrowser, frame: CefFrame, transitionType: CefRequest.TransitionType) {}
        })
        val render = mockk<CefRenderHandler>(relaxed = true) { every { getViewRect(any()) } returns Rectangle(0, 0, 800, 600) }
        val browser = CefBrowserHeadless(client, url, render).apply { createImmediately() }
        val outcome = runCatching { result.get(60, TimeUnit.SECONDS) }.getOrElse { "TIMEOUT ${it::class.simpleName}" }
        val script = if (outcome.startsWith("LOADED")) {
            browser.executeJavaScript("document.title = 'zk-' + (6 * 7);", browser.url, 0)
            runCatching { title.get(15, TimeUnit.SECONDS) }.getOrElse { "TIMEOUT ${it::class.simpleName}" }
        } else {
            null
        }
        println("CHROMIUM-DOH privacy=$privacy safebrowsing.enabled=${runCatching { safeBrowsing.get(5, TimeUnit.SECONDS) }.getOrNull()} script title=$script")
        val localState = File(cache, "Local State").takeIf { it.isFile }?.readText().orEmpty()
        println("CHROMIUM-DOH template=$template args=${System.getenv("ZOUKAN_CEF_ARGS")} url=$url -> $outcome")
        println("CHROMIUM-DOH Local State dns_over_https: " + Regex("\"dns_over_https\":\\{[^}]*\\}").find(localState)?.value)
        browser.close(true)
        client.dispose()
        val expect = System.getenv("ZOUKAN_CEF_EXPECT")
        if (expect != null) {
            check(outcome.contains(expect)) { outcome }
        } else if (template == null || !template.contains("127.0.0.1")) {
            check(outcome.startsWith("LOADED")) { outcome }
            check(script == "zk-42") { "JavaScript did not run: $script" }
            if (privacy) check(safeBrowsing.get(5, TimeUnit.SECONDS) == false) { "Safe Browsing is on" }
        } else {
            check(outcome.contains("NAME_NOT_RESOLVED")) { "Chromium did not use the DoH server: $outcome" }
        }
    }
}
