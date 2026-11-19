package zoukan.desktop.network

import android.content.Context
import coil3.PlatformContext
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import eu.kanade.tachiyomi.data.track.kavita.KavitaApi
import eu.kanade.tachiyomi.data.track.kavita.KavitaInterceptor
import eu.kanade.tachiyomi.network.DOH_PROVIDERS
import eu.kanade.tachiyomi.network.LocalNamesSystemDns
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.network.PREF_DOH_CLOUDFLARE
import eu.kanade.tachiyomi.network.PREF_DOH_MULLVAD
import eu.kanade.tachiyomi.network.PREF_DOH_QUAD101
import eu.kanade.tachiyomi.network.PREF_DOH_QUAD9
import eu.kanade.tachiyomi.network.PREF_DOH_SHECAN
import eu.kanade.tachiyomi.network.desktopDohDns
import eu.kanade.tachiyomi.network.dnsOverHttps
import eu.kanade.tachiyomi.network.dohProviderBootstrapAddress
import eu.kanade.tachiyomi.network.dohProviderHostResolverRule
import eu.kanade.tachiyomi.network.dohProviderUrl
import eu.kanade.tachiyomi.network.isDohQueryThread
import eu.kanade.tachiyomi.network.isLocalOnlyHostName
import eu.kanade.tachiyomi.network.migrateRemovedDohProvider
import eu.kanade.tachiyomi.network.withSystemFallback
import exh.uconfig.EHConfigurator
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import zoukan.desktop.backup.DesktopBackups
import zoukan.desktop.testing.FreshInjekt
import zoukan.desktop.ui.common.Images
import java.io.File
import java.io.IOException
import java.net.CookieHandler
import java.net.CookieManager
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

/**
 * With DNS over HTTPS on, every client the app makes must resolve names through
 * [NetworkHelper.client]'s DNS, never the system's (which would leak each host name).
 */
class DohClientsTest {
    @JvmField
    @RegisterExtension
    val injekt = FreshInjekt()

    @TempDir
    lateinit var dir: File

    private fun networkHelper(provider: Int): NetworkHelper {
        val store = InMemoryPreferenceStore(sequenceOf(InMemoryPreference("doh_provider", provider, -1)))
        return networkHelper(NetworkPreferences(store))
    }

    private fun networkHelper(preferences: NetworkPreferences): NetworkHelper {
        val context = mockk<Context> { every { cacheDir } returns dir }
        return NetworkHelper(context, preferences)
    }

    @Test
    fun `DNS over HTTPS is on with Quad9 on desktop until the user chooses otherwise`() {
        // Never set: the desktop default (DesktopAppModule passes Quad9)
        val fresh = NetworkPreferences(InMemoryPreferenceStore(), defaultDohProvider = PREF_DOH_QUAD9)
        fresh.dohProvider().get() shouldBe PREF_DOH_QUAD9
        networkHelper(fresh).client.dns.dnsOverHttps.shouldNotBeNull().url.toString() shouldBe dohProviderUrl(PREF_DOH_QUAD9)
        // A stored choice is kept: off stays off, another provider stays that provider
        for (stored in listOf(-1, PREF_DOH_CLOUDFLARE, PREF_DOH_MULLVAD)) {
            val store = InMemoryPreferenceStore(sequenceOf(InMemoryPreference("doh_provider", stored, -1)))
            NetworkPreferences(store, defaultDohProvider = PREF_DOH_QUAD9).dohProvider().get() shouldBe stored
        }
        val off = InMemoryPreferenceStore(sequenceOf(InMemoryPreference("doh_provider", -1, -1)))
        networkHelper(NetworkPreferences(off, defaultDohProvider = PREF_DOH_QUAD9)).client.dns shouldBeSameInstanceAs Dns.SYSTEM
        // Android's default stays off
        NetworkPreferences(InMemoryPreferenceStore()).dohProvider().get() shouldBe -1
        // A restore writes the backup's choice only when the user confirms it
        DesktopBackups.SENSITIVE_KEYS shouldContain "doh_provider"
    }

    @Test
    fun `local-only names resolve with the system's DNS while DNS over HTTPS is on`() {
        val dns = networkHelper(PREF_DOH_QUAD9).client.dns
        // OkHttp's DoH alone refuses them (no public suffix), so the local test store would break
        assertThrows<UnknownHostException> { dns.dnsOverHttps.shouldNotBeNull().lookup("localhost") }
        dns.lookup("localhost").all { it.isLoopbackAddress } shouldBe true
        dns.lookup("LOCALHOST.").all { it.isLoopbackAddress } shouldBe true
        val local = listOf(
            "localhost", "nas", "NAS.", "media.local", "server.lan", "box.home", "nas.home.arpa", "host.internal",
            "pc.localdomain", "app.localhost",
            // Never delegated in public DNS, and a home router's own name
            "wiki.corp", "portal.intranet", "nas.private", "app.test", "fritz.box", "NAS.FRITZ.BOX.",
        )
        for (name in local) {
            withClue(name) { isLocalOnlyHostName(name) shouldBe true }
        }
        val public = listOf(
            "example.com", "mangadex.org", "local.example.com", "lan.party.net", "home.example", "internal.example.org",
            "corp.example.com", "test.example.org", "fritz.box.example.com", "privatebox.net", "box.fritz",
        )
        for (name in public) {
            withClue(name) { isLocalOnlyHostName(name) shouldBe false }
        }
    }

    @Test
    fun `every provider gives the client DNS over HTTPS with its bootstrap addresses`() {
        for (provider in PROVIDERS) {
            val network = networkHelper(provider)
            // DoH for public names; local-only names go to the system's DNS (LocalNamesSystemDns)
            network.client.dns.shouldBeInstanceOf<LocalNamesSystemDns>()
            val doh = network.client.dns.dnsOverHttps.shouldNotBeNull()
            // Chromium gets the same server (ChromiumDns)
            doh.url.toString() shouldBe dohProviderUrl(provider)
            // The DoH server's own name never goes to the system's DNS: its client knows its addresses
            val bootstrap = doh.client.dns.lookup(doh.url.host)
            check(bootstrap.isNotEmpty()) { "Provider $provider has no bootstrap addresses" }
            assertThrows<UnknownHostException> { doh.client.dns.lookup("example.com") }
            // The queries run on their own, marked threads (AppDnsResolver sends their lookups to the system's DNS)
            doh.client.dispatcher shouldNotBe network.client.dispatcher
            doh.client.dispatcher.executorService.submit(Callable { isDohQueryThread() }).get() shouldBe true
            // Chromium connects to the server at one of the same addresses
            val address = InetAddress.getByName(dohProviderBootstrapAddress(provider))
            bootstrap shouldContain address
            dohProviderHostResolverRule(provider) shouldBe "MAP ${doh.url.host} ${address.hostAddress}"
            // The queries carry none of the app's cookies, cache, user agent or logging
            doh.client.interceptors shouldBe emptyList()
            doh.client.networkInterceptors shouldBe emptyList()
            doh.client.cookieJar shouldBeSameInstanceAs CookieJar.NO_COOKIES
            doh.client.cache shouldBe null
            // The client for sync and E-Hentai's settings shares it
            network.plainClient.dns shouldBeSameInstanceAs network.client.dns
        }
    }

    /** Preferences with a stored provider that keeps what is set (InMemoryPreferenceStore hands out copies). */
    private fun dohPreferences(provider: Int): NetworkPreferences {
        val pref = InMemoryPreference("doh_provider", provider, -1)
        return mockk { every { dohProvider() } returns pref }
    }

    @Test
    fun `a stored removed provider moves to Cloudflare instead of the system DNS`() {
        for (removed in listOf(PREF_DOH_QUAD101, PREF_DOH_SHECAN)) {
            // Before the migration runs, the client already uses Cloudflare
            networkHelper(removed).client.dns.dnsOverHttps.shouldNotBeNull().url.toString() shouldBe
                dohProviderUrl(PREF_DOH_CLOUDFLARE)
            val preferences = dohPreferences(removed)
            preferences.migrateRemovedDohProvider() shouldBe true
            preferences.dohProvider().get() shouldBe PREF_DOH_CLOUDFLARE
            preferences.migrateRemovedDohProvider() shouldBe false
        }
        // Other choices, off included, stay as they are
        for (provider in PROVIDERS + -1) {
            val preferences = dohPreferences(provider)
            preferences.migrateRemovedDohProvider() shouldBe false
            preferences.dohProvider().get() shouldBe provider
        }
    }

    @Test
    fun `without a provider the system DNS is used`() {
        networkHelper(-1).client.dns shouldBeSameInstanceAs Dns.SYSTEM
        dohProviderUrl(-1) shouldBe null
        dohProviderHostResolverRule(-1) shouldBe null
    }

    @Test
    fun `the plain client has no cookies, cache or interceptors`() {
        val plain = networkHelper(1).plainClient
        plain.interceptors shouldBe emptyList()
        plain.networkInterceptors shouldBe emptyList()
        plain.cache shouldBe null
        plain.callTimeoutMillis shouldBe 0
    }

    @Test
    fun `image addresses load through the app's client`() {
        val dns = injektNetwork(dir)
        Images.init(File(dir, "covers"), imageCache, zoukan.desktop.secrets.FileCipher(ByteArray(32)))
        val request = ImageRequest.Builder(PlatformContext.INSTANCE).data("http://dns-leak-check.invalid/icon.png").build()
        val result = runBlocking { Images.loader.execute(request) }.shouldBeInstanceOf<ErrorResult>()
        withClue(result.throwable.stackTraceToString()) { dns.lookups shouldContain "dns-leak-check.invalid" }
    }

    @Test
    fun `the loader still decodes images without Coil's service loader`() {
        injektNetwork(dir)
        Images.init(File(dir, "covers"), imageCache, zoukan.desktop.secrets.FileCipher(ByteArray(32)))
        val png = File(dir, "pixel.png")
        javax.imageio.ImageIO.write(java.awt.image.BufferedImage(3, 2, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", png)
        val request = ImageRequest.Builder(PlatformContext.INSTANCE).data(png.absolutePath).build()
        val result = runBlocking { Images.loader.execute(request) }.shouldBeInstanceOf<SuccessResult>()
        result.image.width shouldBe 3
    }

    @Test
    fun `Coil keeps images in the app's cache folder, not the system's temporary folder`() {
        injektNetwork(dir)
        // Without the file key Coil keeps nothing on disk
        Images.init(File(dir, "cache/covers"), imageCache, zoukan.desktop.secrets.FileCipher(ByteArray(32)))
        val directory = Images.loader.diskCache.shouldNotBeNull().directory.toFile()
        // The loader is built once per JVM, so possibly from another test's folder
        directory shouldBe imageCache
        // ZoukanApp passes the data folder's cache/images; without it, the default is beside the covers
        directory.name shouldNotBe "coil3_disk_cache"
        Images.clearDiskCache()
    }

    @Test
    fun `E-Hentai's settings client uses the app's DNS`() {
        val dns = injektNetwork(dir)
        val configurator = EHConfigurator(mockk(relaxed = true))
        val field = EHConfigurator::class.java.getDeclaredField("configuratorClient").apply { isAccessible = true }
        (field.get(configurator) as OkHttpClient).dns shouldBeSameInstanceAs dns
    }

    @Test
    fun `Kavita resolves public names over HTTPS and falls back to the system's DNS only for local names`() {
        val dns = RecordingDns(known = mapOf("kavita.example.com" to listOf(InetAddress.getByName("203.0.113.7"))))
        val api = KavitaApi(OkHttpClient.Builder().dns(dns).build(), mockk<KavitaInterceptor>(relaxed = true))
        val field = KavitaApi::class.java.getDeclaredField("authClient").apply { isAccessible = true }
        val authDns = (field.get(api) as OkHttpClient).dns
        authDns.lookup("kavita.example.com") shouldBe listOf(InetAddress.getByName("203.0.113.7"))
        // A home server's name only the local resolver knows
        authDns.lookup("localhost").all { it.isLoopbackAddress } shouldBe true
        dns.lookups shouldContainAll listOf("kavita.example.com", "localhost")
        // A public name DoH cannot resolve is never sent to the system's DNS instead
        assertThrows<UnknownHostException> { authDns.lookup("missing.example.com") }
    }

    @Test
    fun `the system fallback is the system DNS itself when DoH is off`() {
        Dns.SYSTEM.withSystemFallback() shouldBeSameInstanceAs Dns.SYSTEM
    }

    /** Records each name looked up; resolves only [known] ones, like DoH for a name it cannot find. */
    private class RecordingDns(private val known: Map<String, List<InetAddress>> = emptyMap()) : Dns {
        val lookups: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override fun lookup(hostname: String): List<InetAddress> {
            lookups += hostname
            return known[hostname] ?: throw UnknownHostException(hostname)
        }
    }

    private class FakeNetworkHelper(dir: File, dns: Dns) : NetworkHelper(
        mockk<Context> { every { cacheDir } returns dir },
        NetworkPreferences(InMemoryPreferenceStore()),
    ) {
        override val client: OkHttpClient = OkHttpClient.Builder().dns(dns).build()
    }

    private companion object {
        val PROVIDERS = DOH_PROVIDERS

        /**
         * Coil's disk cache, for the whole JVM: the loader is built once, and on Windows its open
         * journal would keep a test's temporary folder from being deleted.
         */
        val imageCache: File by lazy {
            java.nio.file.Files.createTempDirectory("zoukan-coil-test").toFile().resolve("cache/images")
        }

        /** Registers a fresh [FakeNetworkHelper] in the current [Injekt] scope and returns its [RecordingDns]. */
        fun injektNetwork(dir: File): RecordingDns = RecordingDns().also {
            Injekt.addSingleton<NetworkHelper>(FakeNetworkHelper(dir, it))
        }
    }
}

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
            { Dns { emptyList() } },
            { throw IllegalStateException("network not set up") },
        )
        for (dns in failures) {
            assertThrows<UnknownHostException> { resolver(builtin, dns).lookupByName("public.example", ipv4AndIpv6) }
        }
        builtin.names.shouldBeEmpty()
    }

    @Test
    fun `a fatal error in the app's DNS goes on up as it is`() {
        val builtin = FakeBuiltin(builtinAddress)
        assertThrows<OutOfMemoryError> { resolver(builtin) { Dns { throw OutOfMemoryError() } }.lookupByName("public.example", ipv4AndIpv6) }
        assertThrows<StackOverflowError> { resolver(builtin) { Dns { throw StackOverflowError() } }.lookupByName("public.example", ipv4AndIpv6) }
        assertThrows<OutOfMemoryError> { resolver(builtin) { throw OutOfMemoryError() }.lookupByName("public.example", ipv4AndIpv6) }
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

/**
 * Resolves real names through every DNS over HTTPS provider, with the client NetworkHelper builds.
 * Needs the internet, so it runs only when asked:
 *   ZOUKAN_LIVE=true ./gradlew :desktop:test --tests "*DohLiveTest"
 * (or -Dzoukan.live=true on the test JVM).
 */
class DohLiveTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `every provider resolves real names`() {
        assumeTrue(System.getProperty("zoukan.live") == "true" || System.getenv("ZOUKAN_LIVE") == "true")
        // The app installs its cookie store (PersistentCookieStore); the clients' cookie jar needs one
        if (CookieHandler.getDefault() == null) CookieHandler.setDefault(CookieManager())
        val failures = mutableListOf<String>()
        for (provider in DOH_PROVIDERS) {
            val context = mockk<Context> { every { cacheDir } returns File(dir, "$provider") }
            val store = InMemoryPreferenceStore(sequenceOf(InMemoryPreference("doh_provider", provider, -1)))
            val dns = NetworkHelper(context, NetworkPreferences(store)).client.dns
            for (host in HOSTS) {
                val start = System.nanoTime()
                val result = runCatching { dns.lookup(host) }
                val ms = (System.nanoTime() - start) / 1_000_000
                val line = "provider $provider $host (${ms}ms): " +
                    result.fold({ it.joinToString { a -> a.hostAddress } }, { "FAILED ${it::class.simpleName}: ${it.message} " + causes(it) })
                println(line)
                if (result.isFailure || result.getOrNull().isNullOrEmpty()) failures += line
            }
        }
        check(failures.isEmpty()) { "DNS over HTTPS failed:\n" + failures.joinToString("\n") }
    }

    /** What DnsOverHttps puts behind its UnknownHostException: the causes and suppressed errors. */
    private fun causes(e: Throwable): String = (generateSequence(e.cause) { it.cause } + e.suppressed.asSequence())
        .joinToString(" / ") { "${it::class.simpleName}: ${it.message}" }

    private companion object {
        val HOSTS = listOf("example.com", "mangadex.org")
    }
}

/**
 * Fails when desktop code makes an HTTP client or connection of its own. Such a client resolves
 * names through the system's DNS, which leaks every host name when DNS over HTTPS is on; clients
 * must come from NetworkHelper (`client`, `plainClient` or their `newBuilder()`).
 */
class DnsLeakSourceScanTest {
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
        Regex("""\bOkHttpClient\(\)"""),
        Regex("""\bOkHttpClient\.Builder\(\)"""),
        Regex("""\bDns\.SYSTEM\b"""),
        Regex("""\bHttpURLConnection\b"""),
        Regex("""\bHttpsURLConnection\b"""),
        Regex("""\bjava\.net\.http\."""),
        Regex("""\bHttpClient\.new(HttpClient|Builder)\b"""),
        Regex("""\.openConnection\("""),
        Regex("""\bURL\([^)]*\)\.(openStream|readText|readBytes)\("""),
        Regex("""\bNetHttpTransport\b"""),
    )

    /** File (relative to the root) to the patterns it may use, with the reason. */
    private val allowed = mapOf(
        // The one place clients are built, with DNS over HTTPS
        "core/common/src/commonMain/kotlin/eu/kanade/tachiyomi/network/NetworkHelper.kt" to setOf("""\bOkHttpClient\.Builder\(\)"""),
        // The fallback for self-hosted servers' names, after DNS over HTTPS
        "core/common/src/commonMain/kotlin/eu/kanade/tachiyomi/network/DohProviders.kt" to setOf("""\bDns\.SYSTEM\b"""),
        // What extensions' own clients become (BytecodeEditor): the app's DNS, the system's only
        // while the app has set none
        "android-compat/src/main/java/xyz/nulldev/androidcompat/network/ExtensionHttp.java" to
            setOf("""\bOkHttpClient\.Builder\(\)""", """\bDns\.SYSTEM\b"""),
        // Compares the app's DNS with the system's, to hand those lookups to the JDK's own resolver
        "desktop/src/main/kotlin/zoukan/desktop/network/AppDnsResolver.kt" to setOf("""\bDns\.SYSTEM\b"""),
        // A jar held in memory, no network
        "extension-jvm/src/main/kotlin/zoukan/extension/ExtensionClassLoader.kt" to setOf("""\.openConnection\("""),
    )

    @Test
    fun `no HTTP client or connection bypasses NetworkHelper`() {
        val dirs = sourceDirs.map { File(root, it) }
        check(File(root, "desktop/src/main/kotlin").isDirectory) { "Not run from the zoukan checkout: $root" }
        val violations = dirs.filter { it.isDirectory }
            .flatMap { dir -> dir.walk().filter { it.isFile && (it.extension == "kt" || it.extension == "java") } }
            .flatMap { file ->
                val path = file.relativeTo(root).invariantSeparatorsPath
                val ok = allowed[path].orEmpty()
                code(file.readText()).lineSequence().withIndex().flatMap { (i, line) ->
                    forbidden.filter { it.pattern !in ok && it.containsMatchIn(line) }
                        .map { "$path:${i + 1}: ${line.trim()}" }
                }.toList()
            }
        check(violations.isEmpty()) {
            "Use NetworkHelper.client or plainClient (or their newBuilder()) instead, so DNS over HTTPS applies:\n" +
                violations.joinToString("\n")
        }
    }

    /** [text] with comments blanked out, keeping line numbers. */
    private fun code(text: String): String = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)) { m -> m.value.replace(Regex("[^\n]"), " ") }
        .lineSequence()
        .joinToString("\n") { line -> line.replace(Regex("""(^|\s)//.*$"""), "") }
}
