package zoukan.desktop.network

import eu.kanade.tachiyomi.network.SelectedProxyRecorder
import eu.kanade.tachiyomi.network.desktopDohDns
import eu.kanade.tachiyomi.network.isDohQueryThread
import eu.kanade.tachiyomi.network.isSystemProxyHost
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.dnsoverhttps.DnsOverHttps
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.SocketAddress
import java.net.URI
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.Callable
import kotlin.concurrent.thread

/**
 * A system proxy set by host name must keep working with DNS over HTTPS on: its name goes to the
 * system's DNS (the provider cannot know it, and its query would itself need the proxy), and the
 * DoH queries can connect through it.
 */
class DohProxyTest {
    private val proxyName = "proxy.zoukan-test.example"
    private val loopback: InetAddress = InetAddress.getLoopbackAddress()
    private val server = ServerSocket(0, 50, loopback)
    private val proxyRequests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var previousSelector: ProxySelector? = null

    /** The system's DNS: knows only the proxy's name, as a company network's resolver would. */
    private val systemLookups: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val systemDns = Dns { name ->
        systemLookups += name
        if (name == proxyName) listOf(loopback) else throw UnknownHostException(name)
    }

    @BeforeEach
    fun setUp() {
        thread(isDaemon = true, name = "fake proxy") { serveProxy() }
        previousSelector = ProxySelector.getDefault()
        // The proxy as the OS settings would give it: by name, not resolved
        ProxySelector.setDefault(
            object : ProxySelector() {
                override fun select(uri: URI?): List<Proxy> =
                    listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(proxyName, server.localPort)))

                override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
            },
        )
    }

    @AfterEach
    fun tearDown() {
        ProxySelector.setDefault(previousSelector)
        server.close()
    }

    /** Answers a plain request with "proxied" and refuses a CONNECT (the DoH query's tunnel). */
    private fun serveProxy() {
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (_: IOException) {
                return
            }
            socket.use {
                val reader = it.getInputStream().bufferedReader()
                val requestLine = reader.readLine() ?: return@use
                proxyRequests += requestLine
                // The headers, up to the blank line
                do {
                    val header = reader.readLine()
                } while (!header.isNullOrEmpty())
                val response = if (requestLine.startsWith("CONNECT")) {
                    "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                } else {
                    "HTTP/1.1 200 OK\r\nContent-Length: 7\r\nConnection: close\r\n\r\nproxied"
                }
                it.getOutputStream().apply {
                    write(response.toByteArray())
                    flush()
                }
            }
        }
    }

    /** Quad9 as its provider function builds it before the desktop wiring (bootstrap addresses only). */
    private fun upstreamQuad9(): DnsOverHttps = DnsOverHttps.Builder()
        .client(OkHttpClient())
        .url("https://dns.quad9.net/dns-query".toHttpUrl())
        .bootstrapDnsHosts(InetAddress.getByName("9.9.9.9"), InetAddress.getByName("149.112.112.112"))
        .build()

    @Test
    fun `the system proxy's host name is recognised from the proxy settings`() {
        isSystemProxyHost(proxyName) shouldBe true
        isSystemProxyHost("PROXY.zoukan-test.example.") shouldBe true
        isSystemProxyHost("example.com") shouldBe false
        isSystemProxyHost("") shouldBe false
        // A changed setting is seen at once
        ProxySelector.setDefault(null)
        isSystemProxyHost(proxyName) shouldBe false
    }

    @Test
    fun `an HTTP proxy set by host name works with DNS over HTTPS on`() {
        val dns = desktopDohDns(upstreamQuad9(), systemDns)
        // The app's requests: the proxy's name goes to the system's DNS, not to DoH
        dns.lookup(proxyName) shouldContainExactly listOf(loopback)
        val client = OkHttpClient.Builder().dns(dns).build()
        client.newCall(Request("http://manga.zoukan-test.example/page".toHttpUrl())).execute().use {
            it.body.string() shouldBe "proxied"
        }
        proxyRequests.first() shouldStartWith "GET http://manga.zoukan-test.example/page"

        // A DoH query connects through the proxy too: its client resolves the proxy's name, where
        // OkHttp's bootstrap DNS refused every name but the DoH server's
        proxyRequests.clear()
        assertThrows<UnknownHostException> { dns.lookup("public.zoukan-test.example") }
        withClue(proxyRequests) { proxyRequests shouldContain "CONNECT dns.quad9.net:443 HTTP/1.1" }
        systemLookups.toSet() shouldBe setOf(proxyName)
    }

    @Test
    fun `the DoH query client resolves only its server and the proxy, on marked threads`() {
        val doh = desktopDohDns(upstreamQuad9(), systemDns).doh
        doh.client.dns.lookup("dns.quad9.net") shouldContain InetAddress.getByName("9.9.9.9")
        doh.client.dns.lookup(proxyName) shouldContainExactly listOf(loopback)
        assertThrows<UnknownHostException> { doh.client.dns.lookup("example.com") }
        // The server's own name never goes to the system's DNS
        systemLookups shouldContainExactly listOf(proxyName)
        // The queries run on threads the JDK's resolver can tell apart (AppDnsResolver)
        doh.client.dispatcher.executorService.submit(Callable { isDohQueryThread() }).get() shouldBe true
        isDohQueryThread() shouldBe false
    }
}

/**
 * A proxy that the system's settings (a PAC script) give only for some sites is not among the
 * proxies [isSystemProxyHost] probes for; once OkHttp picks it for a request, its name must still
 * go to the system's DNS, not to DNS over HTTPS.
 */
class PacProxyTest {
    private val siteHost = "manga.pac-test.example"
    private val proxyName = "site-proxy.pac-test.example"
    private val loopback: InetAddress = InetAddress.getLoopbackAddress()
    private val server = ServerSocket(0, 50, loopback)
    private val proxyRequests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var previousSelector: ProxySelector? = null

    private val systemLookups: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val systemDns = Dns { name ->
        systemLookups += name
        if (name == proxyName) listOf(loopback) else throw UnknownHostException(name)
    }

    @BeforeEach
    fun setUp() {
        thread(isDaemon = true, name = "fake site proxy") { serveProxy() }
        previousSelector = ProxySelector.getDefault()
        // As a PAC script: the proxy for one site only, a direct connection for everything else
        ProxySelector.setDefault(
            object : ProxySelector() {
                override fun select(uri: URI?): List<Proxy> = if (uri?.host == siteHost) {
                    listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(proxyName, server.localPort)))
                } else {
                    listOf(Proxy.NO_PROXY)
                }

                override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
            },
        )
    }

    @AfterEach
    fun tearDown() {
        ProxySelector.setDefault(previousSelector)
        server.close()
    }

    private fun serveProxy() {
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (_: IOException) {
                return
            }
            socket.use {
                val reader = it.getInputStream().bufferedReader()
                val requestLine = reader.readLine() ?: return@use
                proxyRequests += requestLine
                do {
                    val header = reader.readLine()
                } while (!header.isNullOrEmpty())
                it.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Length: 7\r\nConnection: close\r\n\r\nproxied".toByteArray())
                    flush()
                }
            }
        }
    }

    private fun upstreamQuad9(): DnsOverHttps = DnsOverHttps.Builder()
        .client(OkHttpClient())
        .url("https://dns.quad9.net/dns-query".toHttpUrl())
        .bootstrapDnsHosts(InetAddress.getByName("9.9.9.9"), InetAddress.getByName("149.112.112.112"))
        .build()

    @Test
    fun `a proxy picked for one site is resolved with the system's DNS`() {
        // The probes see no proxy at all
        isSystemProxyHost(proxyName) shouldBe false

        val client = OkHttpClient.Builder()
            .eventListener(SelectedProxyRecorder)
            .dns(desktopDohDns(upstreamQuad9(), systemDns))
            .build()
        client.newCall(Request("http://$siteHost/page".toHttpUrl())).execute().use {
            it.body.string() shouldBe "proxied"
        }

        proxyRequests.first() shouldStartWith "GET http://$siteHost/page"
        systemLookups shouldContainExactly listOf(proxyName)
        isSystemProxyHost(proxyName) shouldBe true
        isSystemProxyHost("SITE-PROXY.pac-test.example.") shouldBe true
        isSystemProxyHost(siteHost) shouldBe false
    }
}

/**
 * The app's HTTP must follow the proxy set in the OS: the run task and the installers start the
 * JVM with `java.net.useSystemProxies=true`, and no client may pick a proxy of its own (such as
 * `Proxy.NO_PROXY`, which would send traffic around the user's proxy).
 */
class SystemProxySourceScanTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    /** The sources the desktop app compiles (Android-only source sets left out). */
    private val sourceDirs = listOf(
        "desktop/src/main/kotlin",
        "desktop/build/generated/app-shared",
        "core/common/src/commonMain",
        "core/common/src/desktopMain",
        "data/src/commonMain",
        "data/src/desktopMain",
        "source-api/src/commonMain",
        "source-api/src/desktopMain",
        "domain/src/commonMain",
        "extension-jvm/src/main",
        "android-compat/src/main/java/xyz",
        "android-compat/src/main/java/suwayomi",
    )

    private val forbidden = listOf(
        Regex("""\bNO_PROXY\b"""),
        Regex("""\bProxy\.Type\.DIRECT\b"""),
        Regex("""\.proxy\s*\("""),
        Regex("""\.proxySelector\s*\("""),
        Regex("""\bProxySelector\.setDefault\s*\("""),
        Regex("""\buseSystemProxies\b"""),
        Regex("""\b(http|https|socks)\.?[pP]roxy(Host|Port)\b"""),
    )

    @Test
    fun `no app client chooses its own proxy`() {
        check(File(root, "desktop/src/main/kotlin").isDirectory) { "Not run from the zoukan checkout: $root" }
        val violations = sourceDirs.map { File(root, it) }.filter { it.isDirectory }
            .flatMap { dir -> dir.walk().filter { it.isFile && (it.extension == "kt" || it.extension == "java") } }
            .flatMap { file ->
                val path = file.relativeTo(root).invariantSeparatorsPath
                code(file.readText()).lineSequence().withIndex().flatMap { (i, line) ->
                    forbidden.filter { it.containsMatchIn(line) }.map { "$path:${i + 1}: ${line.trim()}" }
                }.toList()
            }
        check(violations.isEmpty()) {
            "HTTP must follow the OS proxy settings (java.net.useSystemProxies in desktop/build.gradle.kts):\n" +
                violations.joinToString("\n")
        }
    }

    @Test
    fun `the run task and the installers use the OS proxy settings`() {
        val build = File(root, "desktop/build.gradle.kts").readText()
        val jvmFlags = Regex("""val jvmFlags = listOf\((.*?)\n\)""", RegexOption.DOT_MATCHES_ALL)
            .find(build)?.groupValues?.get(1)
        checkNotNull(jvmFlags) { "No jvmFlags list in desktop/build.gradle.kts" }
        check("\"-Djava.net.useSystemProxies=true\"" in jvmFlags) {
            "jvmFlags (the run task's and the installers' JVM options) must set java.net.useSystemProxies=true"
        }
        check("applicationDefaultJvmArgs = jvmFlags" in build) { "The run task no longer uses jvmFlags" }
        check("extra[\"zoukanJvmFlags\"] = jvmFlags" in build) { "The installers no longer get jvmFlags" }
        val packaging = File(root, "desktop/packaging.gradle.kts").readText()
        check(Regex("""zoukanJvmFlags\b.*--java-options""").containsMatchIn(packaging)) {
            "packaging.gradle.kts no longer passes zoukanJvmFlags to jpackage as --java-options"
        }
    }

    /** [text] with comments blanked out, keeping line numbers. */
    private fun code(text: String): String = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)) { m -> m.value.replace(Regex("[^\n]"), " ") }
        .lineSequence()
        .joinToString("\n") { line -> line.replace(Regex("""(^|\s)//.*$"""), "") }
}
