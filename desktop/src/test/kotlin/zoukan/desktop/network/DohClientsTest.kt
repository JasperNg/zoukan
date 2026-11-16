package zoukan.desktop.network

import android.content.Context
import coil3.PlatformContext
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import eu.kanade.tachiyomi.data.track.kavita.KavitaApi
import eu.kanade.tachiyomi.data.track.kavita.KavitaInterceptor
import eu.kanade.tachiyomi.network.DOH_PROVIDERS
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.network.PREF_DOH_CLOUDFLARE
import eu.kanade.tachiyomi.network.PREF_DOH_QUAD101
import eu.kanade.tachiyomi.network.PREF_DOH_SHECAN
import eu.kanade.tachiyomi.network.dohProviderBootstrapAddress
import eu.kanade.tachiyomi.network.dohProviderHostResolverRule
import eu.kanade.tachiyomi.network.dohProviderUrl
import eu.kanade.tachiyomi.network.migrateRemovedDohProvider
import eu.kanade.tachiyomi.network.withSystemFallback
import exh.uconfig.EHConfigurator
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
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
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import zoukan.desktop.ui.common.Images
import java.io.File
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Collections

/**
 * With DNS over HTTPS on, every client the app makes must resolve names through
 * [NetworkHelper.client]'s DNS, never the system's (which would leak each host name).
 */
class DohClientsTest {
    @TempDir
    lateinit var dir: File

    private fun networkHelper(provider: Int): NetworkHelper {
        val context = mockk<Context> { every { cacheDir } returns dir }
        val store = InMemoryPreferenceStore(sequenceOf(InMemoryPreference("doh_provider", provider, -1)))
        return NetworkHelper(context, NetworkPreferences(store))
    }

    @Test
    fun `every provider gives the client DNS over HTTPS with its bootstrap addresses`() {
        for (provider in PROVIDERS) {
            val network = networkHelper(provider)
            val doh = network.client.dns.shouldBeInstanceOf<DnsOverHttps>()
            // Chromium gets the same server (ChromiumDns)
            doh.url.toString() shouldBe dohProviderUrl(provider)
            // The DoH server's own name never goes to the system's DNS: its client knows its addresses
            val bootstrap = doh.client.dns.lookup(doh.url.host)
            check(bootstrap.isNotEmpty()) { "Provider $provider has no bootstrap addresses" }
            assertThrows<UnknownHostException> { doh.client.dns.lookup("example.com") }
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
            networkHelper(removed).client.dns.shouldBeInstanceOf<DnsOverHttps>().url.toString() shouldBe
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
        Images.init(File(dir, "covers"), imageCache)
        val request = ImageRequest.Builder(PlatformContext.INSTANCE).data("http://dns-leak-check.invalid/icon.png").build()
        val result = runBlocking { Images.loader.execute(request) }.shouldBeInstanceOf<ErrorResult>()
        withClue(result.throwable.stackTraceToString()) { dns.lookups shouldContain "dns-leak-check.invalid" }
    }

    @Test
    fun `the loader still decodes images without Coil's service loader`() {
        injektNetwork(dir)
        Images.init(File(dir, "covers"), imageCache)
        val png = File(dir, "pixel.png")
        javax.imageio.ImageIO.write(java.awt.image.BufferedImage(3, 2, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", png)
        val request = ImageRequest.Builder(PlatformContext.INSTANCE).data(png.absolutePath).build()
        val result = runBlocking { Images.loader.execute(request) }.shouldBeInstanceOf<SuccessResult>()
        result.image.width shouldBe 3
    }

    @Test
    fun `Coil keeps images in the app's cache folder, not the system's temporary folder`() {
        injektNetwork(dir)
        Images.init(File(dir, "cache/covers"), imageCache)
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
    fun `Kavita resolves public names over HTTPS and only falls back for names DoH cannot resolve`() {
        val dns = RecordingDns(known = mapOf("kavita.example.com" to listOf(InetAddress.getByName("203.0.113.7"))))
        val api = KavitaApi(OkHttpClient.Builder().dns(dns).build(), mockk<KavitaInterceptor>(relaxed = true))
        val field = KavitaApi::class.java.getDeclaredField("authClient").apply { isAccessible = true }
        val authDns = (field.get(api) as OkHttpClient).dns
        authDns.lookup("kavita.example.com") shouldBe listOf(InetAddress.getByName("203.0.113.7"))
        // A home server's name only the local resolver knows
        authDns.lookup("localhost").all { it.isLoopbackAddress } shouldBe true
        dns.lookups shouldContainAll listOf("kavita.example.com", "localhost")
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

        private var injekted: RecordingDns? = null

        /** The DNS of the NetworkHelper registered in Injekt (once for the JVM, as Injekt is global). */
        @Synchronized
        fun injektNetwork(dir: File): RecordingDns = injekted ?: RecordingDns().also {
            Injekt.addSingleton<NetworkHelper>(FakeNetworkHelper(dir, it))
            injekted = it
        }
    }
}
