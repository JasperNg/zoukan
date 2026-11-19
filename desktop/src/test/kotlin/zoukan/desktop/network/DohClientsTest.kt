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
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.Callable

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
