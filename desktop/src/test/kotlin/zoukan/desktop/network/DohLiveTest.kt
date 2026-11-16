package zoukan.desktop.network

import android.content.Context
import eu.kanade.tachiyomi.network.DOH_PROVIDERS
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import java.io.File
import java.net.CookieHandler
import java.net.CookieManager

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
