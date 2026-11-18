package zoukan.desktop.browser

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import me.friwi.jcefmaven.CefBuildInfo
import okhttp3.Cache
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import zoukan.desktop.browser.ChromiumNatives.forDownload
import zoukan.desktop.testing.FileSystemSandbox
import java.io.File
import java.io.IOException
import java.net.UnknownHostException
import java.nio.file.Files
import java.util.concurrent.TimeUnit

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
