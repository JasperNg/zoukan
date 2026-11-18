package eu.kanade.tachiyomi.network

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * Which host names count as the system's proxy, so their lookups skip DNS over HTTPS. A mistake
 * either leaks a lookup past DNS over HTTPS or makes every request through a proxy fail, and a
 * proxy setting that cannot be read must never throw into a lookup.
 *
 * The remembered names live for the whole run, so each test uses names of its own.
 */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class SystemProxyHostsTest {

    private var previous: ProxySelector? = null

    @BeforeEach
    fun saveSelector() {
        previous = ProxySelector.getDefault()
    }

    @AfterEach
    fun restoreSelector() {
        ProxySelector.setDefault(previous)
    }

    private class FixedSelector(private val pick: (URI) -> List<Proxy>) : ProxySelector() {
        override fun select(uri: URI): List<Proxy> = pick(uri)
        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
    }

    private fun http(host: String, port: Int = 8080) = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, port))

    @Test
    fun `the proxy the system settings give is a proxy host, whatever its case or trailing dot`() {
        ProxySelector.setDefault(FixedSelector { listOf(http("Proxy.Corp.Example.")) })
        isSystemProxyHost("proxy.corp.example") shouldBe true
        isSystemProxyHost("PROXY.corp.example.") shouldBe true
        isSystemProxyHost("other.corp.example") shouldBe false
        isSystemProxyHost("") shouldBe false
        isSystemProxyHost(".") shouldBe false
    }

    @Test
    fun `a SOCKS proxy counts and a direct connection does not`() {
        ProxySelector.setDefault(
            FixedSelector { uri ->
                if (uri.scheme == "socket") {
                    listOf(Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("socks.example", 1080)))
                } else {
                    listOf(Proxy.NO_PROXY)
                }
            },
        )
        isSystemProxyHost("socks.example") shouldBe true
        isSystemProxyHost("example.org") shouldBe false
    }

    @Test
    fun `a new selector is read at once rather than after the cached answer expires`() {
        ProxySelector.setDefault(FixedSelector { listOf(http("first-proxy.example")) })
        isSystemProxyHost("first-proxy.example") shouldBe true
        ProxySelector.setDefault(FixedSelector { listOf(http("second-proxy.example")) })
        isSystemProxyHost("second-proxy.example") shouldBe true
        isSystemProxyHost("first-proxy.example") shouldBe false
    }

    @Test
    fun `settings that cannot be read make no name a proxy host and never throw`() {
        ProxySelector.setDefault(FixedSelector { throw IllegalStateException("broken PAC script") })
        isSystemProxyHost("anything.example") shouldBe false
        ProxySelector.setDefault(null)
        isSystemProxyHost("anything.example") shouldBe false
    }

    @Test
    fun `proxies picked for requests are remembered, the most recent 32 at most`() {
        ProxySelector.setDefault(null)
        SystemProxyHosts.remember(listOf(http("Picked-By-Pac.example."), Proxy.NO_PROXY))
        isSystemProxyHost("picked-by-pac.example") shouldBe true

        val names = (1..40).map { "remembered-$it.example" }
        names.forEach { SystemProxyHosts.remember(listOf(http(it))) }
        SystemProxyHosts.remembered().size shouldBe 32
        isSystemProxyHost("remembered-40.example") shouldBe true
        isSystemProxyHost("remembered-9.example") shouldBe true
        isSystemProxyHost("remembered-8.example") shouldBe false
        isSystemProxyHost("picked-by-pac.example") shouldBe false
    }

    @Test
    fun `remembering a direct connection or an unreadable proxy changes nothing`() {
        val before = SystemProxyHosts.remembered()
        SystemProxyHosts.remember(listOf(Proxy.NO_PROXY))
        SystemProxyHosts.remember(emptyList())
        SystemProxyHosts.remember(listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(".", 1))))
        SystemProxyHosts.remembered() shouldBe before
    }
}
