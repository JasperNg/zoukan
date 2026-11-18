package zoukan.desktop.network

import eu.kanade.tachiyomi.network.desktopDohDns
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.UnknownHostException
import java.net.spi.InetAddressResolver
import java.net.spi.InetAddressResolver.LookupPolicy
import java.util.Collections
import java.util.concurrent.Callable
import java.util.stream.Stream

/** The JDK's own lookups go through the app's DNS (over HTTPS when on), never silently around it. */
class AppDnsResolverTest {

    private val appAddress = InetAddress.getByAddress("public.example", byteArrayOf(203.toByte(), 0, 113, 7))
    private val appAddress6 = InetAddress.getByAddress(
        "public.example",
        ByteArray(16).also {
            it[0] = 0x20
            it[15] = 1
        },
    )
    private val builtinAddress = InetAddress.getByAddress("builtin", byteArrayOf(127, 0, 0, 1))

    /** The JDK's resolver: records each name and answers 127.0.0.1. */
    private class FakeBuiltin(private val address: InetAddress) : InetAddressResolver {
        val names: MutableList<String> = Collections.synchronizedList(mutableListOf())
        var reverse = 0

        override fun lookupByName(host: String, lookupPolicy: LookupPolicy): Stream<InetAddress> {
            names += host
            return Stream.of(address)
        }

        override fun lookupByAddress(addr: ByteArray): String {
            reverse++
            return "builtin-name"
        }
    }

    private val ipv4AndIpv6 = LookupPolicy.of(LookupPolicy.IPV4 or LookupPolicy.IPV6)

    private fun resolver(builtin: InetAddressResolver, dns: () -> Dns?) = AppDnsResolver(builtin, "My-Computer.lan", dns)

    @Test
    fun `public names go to the app's DNS, local ones to the built-in resolver`() {
        val builtin = FakeBuiltin(builtinAddress)
        val asked = mutableListOf<String>()
        val resolver = resolver(builtin) {
            Dns {
                asked += it
                listOf(appAddress)
            }
        }
        resolver.lookupByName("public.example", ipv4AndIpv6).toList() shouldContainExactly listOf(appAddress)
        for (local in listOf("localhost", "nas", "nas.local", "my-computer.lan", "My-Computer.lan.")) {
            resolver.lookupByName(local, ipv4AndIpv6).toList() shouldContainExactly listOf(builtinAddress)
        }
        asked shouldContainExactly listOf("public.example")
        builtin.names shouldContainExactly listOf("localhost", "nas", "nas.local", "my-computer.lan", "My-Computer.lan.")
    }

    @Test
    fun `with DNS over HTTPS off or the app not started, the built-in resolver answers`() {
        val builtin = FakeBuiltin(builtinAddress)
        resolver(builtin) { null }.lookupByName("public.example", ipv4AndIpv6).toList() shouldContainExactly listOf(builtinAddress)
        resolver(builtin) { null }.lookupByAddress(byteArrayOf(1, 2, 3, 4)) shouldBe "builtin-name"
        builtin.reverse shouldBe 1
    }

    @Test
    fun `a failing app DNS fails the lookup with UnknownHostException only, never falling back`() {
        val builtin = FakeBuiltin(builtinAddress)
        val failures: List<() -> Dns?> = listOf(
            { Dns { throw UnknownHostException(it) } },
            { Dns { throw IllegalStateException("broken") } },
            { Dns { throw java.io.InterruptedIOException("timeout") } },
            { Dns { throw StackOverflowError() } },
            { Dns { emptyList() } },
            { throw IllegalStateException("network not set up") },
        )
        for (dns in failures) {
            assertThrows<UnknownHostException> { resolver(builtin, dns).lookupByName("public.example", ipv4AndIpv6) }
        }
        builtin.names.shouldBeEmpty()
    }

    @Test
    fun `a lookup made from inside the app's DNS goes to the built-in resolver instead of looping`() {
        val builtin = FakeBuiltin(builtinAddress)
        lateinit var resolver: AppDnsResolver
        var depth = 0
        resolver = resolver(builtin) {
            Dns { name ->
                depth++
                // As Dns.SYSTEM would, through InetAddress
                resolver.lookupByName("nested.example", ipv4AndIpv6).toList() + listOf(appAddress).filter { name == "public.example" }
            }
        }
        resolver.lookupByName("public.example", ipv4AndIpv6).toList() shouldContainExactly listOf(builtinAddress, appAddress)
        depth shouldBe 1
        builtin.names shouldContainExactly listOf("nested.example")
        // The guard is cleared afterwards: the next lookup goes to the app's DNS again
        resolver.lookupByName("public.example", ipv4AndIpv6).toList() shouldContainExactly listOf(builtinAddress, appAddress)
        depth shouldBe 2
    }

    @Test
    fun `the system proxy's host name goes to the built-in resolver`() {
        val proxyName = "proxy.corp-network.example"
        val builtin = FakeBuiltin(builtinAddress)
        val asked = Collections.synchronizedList(mutableListOf<String>())
        val resolver = resolver(builtin) {
            Dns {
                asked += it
                listOf(appAddress)
            }
        }
        val previous = ProxySelector.getDefault()
        ProxySelector.setDefault(
            object : ProxySelector() {
                override fun select(uri: URI?): List<Proxy> =
                    listOf(Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(proxyName, 1080)))

                override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
            },
        )
        try {
            resolver.lookupByName(proxyName, ipv4AndIpv6).toList() shouldContainExactly listOf(builtinAddress)
            resolver.lookupByName("Proxy.Corp-Network.Example.", ipv4AndIpv6).toList() shouldContainExactly listOf(builtinAddress)
            resolver.lookupByName("public.example", ipv4AndIpv6).toList() shouldContainExactly listOf(appAddress)
        } finally {
            ProxySelector.setDefault(previous)
        }
        builtin.names shouldContainExactly listOf(proxyName, "Proxy.Corp-Network.Example.")
        asked shouldContainExactly listOf("public.example")
    }

    @Test
    fun `lookups on a DNS over HTTPS query's thread go to the built-in resolver`() {
        val builtin = FakeBuiltin(builtinAddress)
        val asked = Collections.synchronizedList(mutableListOf<String>())
        val resolver = resolver(builtin) {
            Dns {
                asked += it
                listOf(appAddress)
            }
        }
        // The dispatcher of the app's DoH query clients, as NetworkHelper builds them on desktop
        val doh = DnsOverHttps.Builder()
            .client(OkHttpClient())
            .url("https://dns.quad9.net/dns-query".toHttpUrl())
            .bootstrapDnsHosts(InetAddress.getByName("9.9.9.9"))
            .build()
        val queryThreads = desktopDohDns(doh).doh.client.dispatcher.executorService
        // As the JDK resolving a SOCKS proxy's name for a query's connection would
        val onQueryThread = queryThreads.submit(Callable { resolver.lookupByName("socks.example", ipv4AndIpv6).toList() }).get()
        onQueryThread shouldContainExactly listOf(builtinAddress)
        resolver.lookupByName("public.example", ipv4AndIpv6).toList() shouldContainExactly listOf(appAddress)
        builtin.names shouldContainExactly listOf("socks.example")
        asked shouldContainExactly listOf("public.example")
    }

    @Test
    fun `reverse lookups fail while DNS over HTTPS is on`() {
        val builtin = FakeBuiltin(builtinAddress)
        assertThrows<UnknownHostException> {
            resolver(builtin) { Dns { listOf(appAddress) } }.lookupByAddress(byteArrayOf(1, 2, 3, 4))
        }
        assertThrows<UnknownHostException> {
            resolver(builtin) { throw IllegalStateException() }.lookupByAddress(byteArrayOf(1, 2, 3, 4))
        }
        builtin.reverse shouldBe 0
    }

    @Test
    fun `the lookup policy filters and orders the addresses`() {
        val both = listOf(appAddress6, appAddress)
        AppDnsResolver.applyPolicy(both, LookupPolicy.IPV4) shouldContainExactly listOf(appAddress)
        AppDnsResolver.applyPolicy(both, LookupPolicy.IPV6) shouldContainExactly listOf(appAddress6)
        AppDnsResolver.applyPolicy(both, LookupPolicy.IPV4 or LookupPolicy.IPV6 or LookupPolicy.IPV4_FIRST) shouldContainExactly
            listOf(appAddress, appAddress6)
        AppDnsResolver.applyPolicy(listOf(appAddress, appAddress6), LookupPolicy.IPV4 or LookupPolicy.IPV6 or LookupPolicy.IPV6_FIRST) shouldContainExactly
            listOf(appAddress6, appAddress)
        val builtin = FakeBuiltin(builtinAddress)
        assertThrows<UnknownHostException> {
            resolver(builtin) { Dns { listOf(appAddress6) } }.lookupByName("public.example", LookupPolicy.of(LookupPolicy.IPV4))
        }
    }

    @Test
    fun `the provider is registered, so InetAddress lookups use the app's DNS once it is set`() {
        val name = "resolver-check.zoukan-test.example"
        val address = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 44))
        // Anything else (another test's lookup) goes to the system's DNS, through the loop guard
        AppDnsResolverProvider.setDnsProvider { Dns { if (it == name) listOf(address) else Dns.SYSTEM.lookup(it) } }
        try {
            InetAddress.getAllByName(name).toList() shouldContainExactly listOf(InetAddress.getByAddress(name, address.address))
            InetAddress.getByName("localhost").isLoopbackAddress shouldBe true
            // A failure is an UnknownHostException, as for any lookup
            AppDnsResolverProvider.setDnsProvider { Dns { throw IllegalStateException("broken") } }
            assertThrows<UnknownHostException> { InetAddress.getByName("other.zoukan-test.example") }
        } finally {
            AppDnsResolverProvider.setDnsProvider(null)
        }
    }
}
