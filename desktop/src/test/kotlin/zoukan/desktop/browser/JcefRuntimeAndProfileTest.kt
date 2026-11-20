package zoukan.desktop.browser

import eu.kanade.tachiyomi.network.DOH_PROVIDERS
import eu.kanade.tachiyomi.network.PREF_DOH_360
import eu.kanade.tachiyomi.network.PREF_DOH_ADGUARD
import eu.kanade.tachiyomi.network.PREF_DOH_ALIDNS
import eu.kanade.tachiyomi.network.PREF_DOH_CLOUDFLARE
import eu.kanade.tachiyomi.network.PREF_DOH_CONTROLD
import eu.kanade.tachiyomi.network.PREF_DOH_DNSPOD
import eu.kanade.tachiyomi.network.PREF_DOH_GOOGLE
import eu.kanade.tachiyomi.network.PREF_DOH_MULLVAD
import eu.kanade.tachiyomi.network.PREF_DOH_NJALLA
import eu.kanade.tachiyomi.network.PREF_DOH_QUAD101
import eu.kanade.tachiyomi.network.PREF_DOH_QUAD9
import eu.kanade.tachiyomi.network.PREF_DOH_SHECAN
import eu.kanade.tachiyomi.network.dnsOverHttps
import eu.kanade.tachiyomi.network.doh360
import eu.kanade.tachiyomi.network.dohAdGuard
import eu.kanade.tachiyomi.network.dohAliDNS
import eu.kanade.tachiyomi.network.dohCloudflare
import eu.kanade.tachiyomi.network.dohControlD
import eu.kanade.tachiyomi.network.dohDNSPod
import eu.kanade.tachiyomi.network.dohGoogle
import eu.kanade.tachiyomi.network.dohMullvad
import eu.kanade.tachiyomi.network.dohNajalla
import eu.kanade.tachiyomi.network.dohProviderUrl
import eu.kanade.tachiyomi.network.dohQuad9
import eu.kanade.tachiyomi.network.interceptor.DesktopBrowser
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.friwi.jcefmaven.CefBuildInfo
import okhttp3.Cache
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.cef.CefSettings
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import xyz.nulldev.androidcompat.webkit.CefHelper
import xyz.nulldev.androidcompat.webkit.KcefWebSettings
import xyz.nulldev.androidcompat.webkit.KcefWebSettings.UserAgentBrandVersion
import zoukan.desktop.browser.ChromiumNatives.forDownload
import zoukan.desktop.testing.FileSystemSandbox
import zoukan.i18n.desktop.browser_data_not_all_cleared
import zoukan.i18n.desktop.could_not_be_checked_and_was_not_cleared
import zoukan.i18n.desktop.is_a_link_and_was_not_cleared
import java.io.File
import java.io.IOException
import java.net.UnknownHostException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

/** JCEF settings, quit hooks, EDT startup safety, and Chromium DoH Local State configuration; no Chromium runs in these tests. */
class JcefRuntimeAndSettingsTest {

    // --- JCEF settings & privacy ---

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

    // --- JCEF quit & EDT startup ---

    @Test
    fun `with the app's quit installed, Cmd+Q runs it and JCEF does not shut Chromium down itself`() {
        var quits = 0
        JcefRuntime.onBeforeTerminate { quits++ } shouldBe true
        quits shouldBe 1
    }

    @Test
    fun `once the app is quitting, another Cmd+Q still does not let JCEF shut Chromium down`() {
        JcefRuntime.onBeforeTerminate(JcefRuntime.QUIT_UNDERWAY) shouldBe true
    }

    @Test
    fun `without it, as in the command-line tools, JCEF's own shutdown ends the run`() {
        JcefRuntime.onBeforeTerminate(null) shouldBe false
    }

    @Test
    fun `asking for Chromium on the UI thread returns at once instead of blocking it`() {
        val done = CountDownLatch(1)
        var result: Any? = "not called"
        try {
            SwingUtilities.invokeLater {
                result = JcefRuntime.app()
                done.countDown()
            }
            done.await(5, TimeUnit.SECONDS) shouldBe true
            result shouldBe null
        } finally {
            // Releases an event thread blocked by the old behaviour, so later tests in this JVM still run
            CefHelper.cefApp.value = Result.failure(IllegalStateException("test over"))
            done.await(5, TimeUnit.SECONDS)
            CefHelper.cefApp.value = Result.success(null)
        }
    }

    // --- Chromium Local State DNS over HTTPS ---

    @Test
    fun `each provider's template is the server OkHttp uses`() {
        val okHttp: Map<Int, OkHttpClient.Builder.() -> OkHttpClient.Builder> = mapOf(
            PREF_DOH_CLOUDFLARE to { dohCloudflare() },
            PREF_DOH_GOOGLE to { dohGoogle() },
            PREF_DOH_ADGUARD to { dohAdGuard() },
            PREF_DOH_QUAD9 to { dohQuad9() },
            PREF_DOH_ALIDNS to { dohAliDNS() },
            PREF_DOH_DNSPOD to { dohDNSPod() },
            PREF_DOH_360 to { doh360() },
            PREF_DOH_MULLVAD to { dohMullvad() },
            PREF_DOH_CONTROLD to { dohControlD() },
            PREF_DOH_NJALLA to { dohNajalla() },
        )
        okHttp.forEach { (provider, configure) ->
            val dns = OkHttpClient.Builder().configure().build().dns.dnsOverHttps!!
            dohProviderUrl(provider) shouldBe dns.url.toString()
        }
        okHttp.keys.toList() shouldBe DOH_PROVIDERS
        // The removed providers (Quad101, Shecan) are read as Cloudflare, never as off
        dohProviderUrl(PREF_DOH_QUAD101) shouldBe dohProviderUrl(PREF_DOH_CLOUDFLARE)
        dohProviderUrl(PREF_DOH_SHECAN) shouldBe dohProviderUrl(PREF_DOH_CLOUDFLARE)
        dohProviderUrl(-1) shouldBe null
        dohProviderUrl(99) shouldBe null
    }

    @Test
    fun `a provider sets secure mode with its template`() {
        val updated = ChromiumDns.updateLocalState(null, "https://dns.google/dns-query")!!
        val group = Json.parseToJsonElement(updated).jsonObject["dns_over_https"]!!.jsonObject
        group["mode"]!!.jsonPrimitive.content shouldBe "secure"
        group["templates"]!!.jsonPrimitive.content shouldBe "https://dns.google/dns-query"
    }

    @Test
    fun `other local state is kept`() {
        val localState = """{"browser":{"enabled_labs_experiments":[]},"dns_over_https":{"other":1},"n":"123"}"""
        val updated = Json.parseToJsonElement(
            ChromiumDns.updateLocalState(localState, "https://dns.quad9.net/dns-query")!!,
        ).jsonObject
        updated["browser"] shouldBe Json.parseToJsonElement(localState).jsonObject["browser"]
        updated["n"]!!.jsonPrimitive.content shouldBe "123"
        updated["dns_over_https"]!!.jsonObject["other"]!!.jsonPrimitive.content shouldBe "1"

        // Turning DoH off again leaves only what Chromium had
        val off = Json.parseToJsonElement(ChromiumDns.updateLocalState(updated.toString(), null)!!)
        off shouldBe Json.parseToJsonElement(localState)
    }

    @Test
    fun `off removes the group it leaves empty, and nothing changes when nothing differs`() {
        val on = ChromiumDns.updateLocalState("{}", "https://dns.google/dns-query")!!
        Json.parseToJsonElement(ChromiumDns.updateLocalState(on, null)!!) shouldBe JsonObject(emptyMap())
        ChromiumDns.updateLocalState(on, "https://dns.google/dns-query") shouldBe null
        ChromiumDns.updateLocalState("{}", null) shouldBe null
        ChromiumDns.updateLocalState(null, null) shouldBe null
    }

    @Test
    fun `an unreadable local state is left alone`() {
        ChromiumDns.updateLocalState("not json", "https://dns.google/dns-query") shouldBe null
        ChromiumDns.updateLocalState("[1]", "https://dns.google/dns-query") shouldBe null
    }

    @Test
    fun `apply with DoH on and a non-JSON Local State still sets secure mode`(@TempDir dir: File) {
        File(dir, "Local State").writeText("{ damaged")
        ChromiumDns.apply(dir, PREF_DOH_CLOUDFLARE)
        val group = Json.parseToJsonElement(File(dir, "Local State").readText()).jsonObject["dns_over_https"]!!.jsonObject
        group["mode"]!!.jsonPrimitive.content shouldBe "secure"
        group["templates"]!!.jsonPrimitive.content shouldBe "https://cloudflare-dns.com/dns-query"
        // The damaged file is kept beside it, not deleted
        File(dir, "Local State.zoukan-bad").readText() shouldBe "{ damaged"
    }

    @Test
    fun `apply with DoH off leaves a non-JSON Local State alone`(@TempDir dir: File) {
        File(dir, "Local State").writeText("{ damaged")
        ChromiumDns.apply(dir, -1)
        File(dir, "Local State").readText() shouldBe "{ damaged"
        File(dir, "Local State.zoukan-bad").exists() shouldBe false
    }

    @Test
    fun `apply writes the Local State file`(@TempDir dir: File) {
        ChromiumDns.apply(dir, PREF_DOH_CLOUDFLARE)
        val file = File(dir, "Local State")
        Json.parseToJsonElement(file.readText()).jsonObject["dns_over_https"]!!.jsonObject["templates"]!!
            .jsonPrimitive.content shouldBe "https://cloudflare-dns.com/dns-query"
        ChromiumDns.apply(dir, -1)
        Json.parseToJsonElement(file.readText()) shouldBe JsonObject(emptyMap())
        dir.listFiles()!!.map { it.name } shouldBe listOf("Local State")
    }
}

class ChromiumNativesTest {
    @Test
    fun `the JCEF build on the class path has a pinned checksum for every supported platform`() {
        val tag = CefBuildInfo.fromClasspath().releaseTag
        listOf("windows-amd64", "macosx-arm64", "linux-amd64").forEach { platform ->
            val sha = ChromiumNatives.pinnedSha256(platform, tag)
            sha shouldNotBe null
            sha!!.matches(Regex("[0-9a-f]{64}")) shouldBe true
        }
    }

    @Test
    fun `another build or platform has none, so nothing is downloaded for it`() {
        ChromiumNatives.pinnedSha256("windows-amd64", "jcef-0000000+cef-1.0.0") shouldBe null
        ChromiumNatives.pinnedSha256("solaris-sparc", CefBuildInfo.fromClasspath().releaseTag) shouldBe null
    }

    @Test
    fun `the download sends nothing of the sources but keeps the app's DNS`() {
        val dns = Dns { Dns.SYSTEM.lookup(it) }
        val appClient = OkHttpClient.Builder()
            .dns(dns)
            .addInterceptor(Interceptor { it.proceed(it.request().newBuilder().header("User-Agent", "source").build()) })
            .addNetworkInterceptor(Interceptor { it.proceed(it.request()) })
            .cookieJar(
                object : CookieJar {
                    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit

                    override fun loadForRequest(url: HttpUrl) = listOf(Cookie.parse(url, "session=source")!!)
                },
            )
            .cache(Cache(Files.createTempDirectory("zoukan-natives-cache").toFile(), 1024))
            .callTimeout(2, TimeUnit.MINUTES)
            .build()

        val download = appClient.forDownload()

        download.interceptors shouldBe emptyList()
        download.networkInterceptors shouldBe emptyList()
        download.cookieJar shouldBe CookieJar.NO_COOKIES
        download.cache shouldBe null
        download.dns shouldBe dns
        download.callTimeoutMillis shouldBe TimeUnit.HOURS.toMillis(1).toInt()
    }

    @Test
    fun `a linked install folder loses the link, never the files it points to`() {
        FileSystemSandbox.create().use { sandbox ->
            val target = File(sandbox.userFolder, "elsewhere").apply { mkdirs() }
            File(target, "keep.txt").writeText("the user's")
            val installDir = sandbox.link(File(sandbox.dataFolder, "chromium"), target)
            assumeTrue(installDir != null, "symbolic links need rights on this system")
            sandbox.record()
            // Fails at the download, after the folder was cleared for a fresh install
            val offline = OkHttpClient.Builder().dns { throw UnknownHostException(it) }.build()

            assertThrows<IOException> { ChromiumNatives.ensureInstalled(installDir!!, { offline }) }

            File(target, "keep.txt").readText() shouldBe "the user's"
            sandbox.assertNothingOutsideAppChanged()
        }
    }
}

/** What the browser profile cleanup deletes, and above all what it keeps. */
class BrowserProfileCleanupTest {
    private val records = listOf(
        "History", "History-journal", "Top Sites", "Top Sites-journal", "Favicons", "Favicons-journal",
        "Visited Links", "Network Action Predictor", "Network Action Predictor-journal", "Shortcuts",
        "Shortcuts-journal", "chrome_debug.log",
    )

    private val kept = listOf(
        "Cookies", "Cookies-journal", "Local State", "Preferences", "Login Data", "Web Data",
        "Network Persistent State", "TransportSecurity", "Trust Tokens",
    )

    private fun File.file(name: String) = File(this, name).apply {
        parentFile.mkdirs()
        writeText("x")
    }

    @Test
    fun `deletes history, caches and the log in the profile and its Default folder`(@TempDir root: File) {
        for (folder in listOf(root, File(root, "Default"))) {
            records.forEach { folder.file(it) }
            folder.file("Cache/Cache_Data/data_0")
            folder.file("Code Cache/js/index")
            folder.file("Network/Reporting and NEL")
            folder.file("Network/Reporting and NEL-journal")
        }

        BrowserProfileCleanup.clean(root)

        for (folder in listOf(root, File(root, "Default"))) {
            records.forEach { File(folder, it).exists() shouldBe false }
            File(folder, "Cache").exists() shouldBe false
            File(folder, "Code Cache").exists() shouldBe false
            File(folder, "Network/Reporting and NEL").exists() shouldBe false
            File(folder, "Network/Reporting and NEL-journal").exists() shouldBe false
        }
    }

    @Test
    fun `keeps cookies, site storage and Local State`(@TempDir root: File) {
        for (folder in listOf(root, File(root, "Default"))) {
            kept.forEach { folder.file(it) }
            folder.file("Network/Cookies")
            folder.file("Local Storage/leveldb/000003.log")
            folder.file("IndexedDB/https_example.org_0.indexeddb.leveldb/CURRENT")
            folder.file("Session Storage/CURRENT")
        }
        // Named like a record, but not in a folder the cleanup looks at
        root.file("Extension State/History")

        BrowserProfileCleanup.clean(root)

        for (folder in listOf(root, File(root, "Default"))) {
            kept.forEach { File(folder, it).exists() shouldBe true }
            File(folder, "Network/Cookies").exists() shouldBe true
            File(folder, "Local Storage/leveldb/000003.log").exists() shouldBe true
            File(folder, "IndexedDB/https_example.org_0.indexeddb.leveldb/CURRENT").exists() shouldBe true
            File(folder, "Session Storage/CURRENT").exists() shouldBe true
        }
        File(root, "Extension State/History").exists() shouldBe true
    }

    @Test
    fun `a folder named like a record file is kept, and a file named like a cache folder too`(@TempDir root: File) {
        root.file("History/keep")
        root.file("Cache")

        BrowserProfileCleanup.clean(root)

        File(root, "History/keep").exists() shouldBe true
        File(root, "Cache").exists() shouldBe true
    }

    @Test
    fun `does not follow a link out of the profile`(@TempDir dir: File) {
        val outside = File(dir, "outside").apply { mkdirs() }
        outside.file("History")
        outside.file("data")
        val root = File(dir, "browser").apply { mkdirs() }
        val linked = runCatching {
            Files.createSymbolicLink(File(root, "Cache").toPath(), outside.toPath())
            Files.createSymbolicLink(File(root, "Default").toPath(), outside.toPath())
        }.isSuccess
        if (!linked) return // Windows without the right to make links

        BrowserProfileCleanup.clean(root)

        File(outside, "History").exists() shouldBe true
        File(outside, "data").exists() shouldBe true
    }

    @Test
    fun `a missing profile is not an error`(@TempDir dir: File) {
        BrowserProfileCleanup.clean(File(dir, "missing")) shouldBe 0
    }

    @Test
    fun `records are matched by name`() {
        BrowserProfileCleanup.isRecord("History-journal") shouldBe true
        BrowserProfileCleanup.isRecord("Reporting and NEL") shouldBe true
        BrowserProfileCleanup.isRecord("Cookies") shouldBe false
        BrowserProfileCleanup.isRecord("Local State") shouldBe false
    }

    @Test
    fun `clearing all browser data empties the profile and follows no link in it`(@TempDir temp: File) {
        FileSystemSandbox.create(temp).use { sandbox ->
            val profile = File(sandbox.dataFolder, "cache/browser-profile")
            profile.file("Default/Local Storage/leveldb/000003.log")
            profile.file("Cache/Cache_Data/data_0")
            profile.file("Local State")
            // Links in the profile to the user's folder and to one of the user's files
            val folderLink = sandbox.link(File(profile, "Default/linked"), sandbox.userFolder)
            assumeTrue(folderLink != null, "symbolic links cannot be made here")
            sandbox.link(File(profile, "linked-file"), File(sandbox.userFolder, "notes.txt"))
            sandbox.record()

            BrowserProfileCleanup.clearAll(profile)

            profile.isDirectory shouldBe true
            profile.listFiles().orEmpty().toList() shouldBe emptyList()
            sandbox.assertNothingOutsideAppChanged()
        }
    }

    @Test
    fun `clearing all browser data leaves a profile folder that is a link alone`(@TempDir temp: File) {
        FileSystemSandbox.create(temp).use { sandbox ->
            val profile = File(sandbox.dataFolder, "cache/browser-profile")
            assumeTrue(sandbox.link(profile, sandbox.userFolder) != null, "symbolic links cannot be made here")
            sandbox.record()

            // The user asked for it to be cleared: they are told it was not, not that it was
            val error = io.kotest.assertions.throwables.shouldThrow<IOException> { BrowserProfileCleanup.clearAll(profile) }
            error.message shouldBe zoukan.desktop.compat.localized(zoukan.i18n.desktop.DMR.strings.is_a_link_and_was_not_cleared, profile.name)

            Files.isSymbolicLink(profile.toPath()) shouldBe true
            sandbox.assertNothingOutsideAppChanged()
        }
    }

    @Test
    fun `clearing all browser data of a profile folder that cannot be checked says so, not that it is a link`(@TempDir temp: File) {
        val profile = File(temp, "browser-profile").apply { mkdirs() }
        val cookies = File(profile, "Cookies").apply { writeText("x") }

        // The folder is there, but where it really is cannot be read (a permission, a disk error)
        val error = io.kotest.assertions.throwables.shouldThrow<IOException> {
            BrowserProfileCleanup.clearAll(profile) { xyz.nulldev.androidcompat.util.NoFollowDelete.LinkState.UNKNOWN }
        }
        error.message shouldBe zoukan.desktop.compat.localized(zoukan.i18n.desktop.DMR.strings.could_not_be_checked_and_was_not_cleared, profile.name)

        // Nothing in it was touched
        cookies.readText() shouldBe "x"
    }

    @Test
    fun `clearing all browser data that leaves some behind says so instead of reporting success`(@TempDir temp: File) {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows") && System.getProperty("user.name") != "root", "needs a folder that cannot be listed")
        val profile = File(temp, "browser-profile").apply { mkdirs() }
        File(profile, "Cache/data_0").apply { parentFile.mkdirs() }.writeText("x")
        val locked = File(profile, "Cache")
        locked.setReadable(false)
        try {
            val error = io.kotest.assertions.throwables.shouldThrow<IOException> {
                BrowserProfileCleanup.clearAll(profile, chromiumMayHoldFiles = false)
            }
            error.message shouldBe zoukan.desktop.compat.localized(zoukan.i18n.desktop.DMR.strings.browser_data_not_all_cleared, 1, profile.name)
        } finally {
            locked.setReadable(true)
        }

        // While Chromium runs and may hold a file, what is left is no failure
        locked.setReadable(false)
        try {
            BrowserProfileCleanup.clearAll(profile, chromiumMayHoldFiles = true)
        } finally {
            locked.setReadable(true)
        }
    }

    @Test
    fun `clearing all browser data of a missing profile is not an error`(@TempDir temp: File) {
        BrowserProfileCleanup.clearAll(File(temp, "absent"))
        File(temp, "absent").exists() shouldBe false
    }

    /**
     * Java reports a Windows junction as a plain folder, not a link: a `Cache` junction was walked
     * into and the files it points to deleted, and a `Default` junction was cleaned through. Not
     * run on macOS or Linux: junctions are Windows only (the symbolic link test covers those).
     */
    @Test
    fun `does not follow a Windows junction out of the profile`(@TempDir dir: File) {
        assumeTrue(System.getProperty("os.name").orEmpty().lowercase().contains("win"), "junctions are Windows only")
        val outside = File(dir, "outside").apply { mkdirs() }
        outside.file("History")
        outside.file("data")
        val root = File(dir, "browser").apply { mkdirs() }
        for (name in listOf("Cache", "Default")) {
            val made = ProcessBuilder("cmd", "/c", "mklink", "/J", File(root, name).absolutePath, outside.absolutePath)
                .redirectErrorStream(true).start().waitFor()
            assumeTrue(made == 0, "mklink /J failed")
        }

        BrowserProfileCleanup.clean(root)

        File(outside, "History").exists() shouldBe true
        File(outside, "data").exists() shouldBe true
    }
}

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
