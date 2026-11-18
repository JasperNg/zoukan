package zoukan.desktop.network

import eu.kanade.tachiyomi.network.SelectedProxyRecorder
import eu.kanade.tachiyomi.network.desktopDohDns
import eu.kanade.tachiyomi.network.isSystemProxyHost
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
import kotlin.concurrent.thread

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
