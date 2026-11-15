package eu.kanade.tachiyomi.network

import logcat.LogPriority
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import tachiyomi.core.common.util.system.logcat
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Based on https://github.com/square/okhttp/blob/ef5d0c83f7bbd3a0c0534e7ca23cbc4ee7550f3b/okhttp-dnsoverhttps/src/test/java/okhttp3/dnsoverhttps/DohProviders.java
 */

const val PREF_DOH_CLOUDFLARE = 1
const val PREF_DOH_GOOGLE = 2
const val PREF_DOH_ADGUARD = 3
const val PREF_DOH_QUAD9 = 4
const val PREF_DOH_ALIDNS = 5
const val PREF_DOH_DNSPOD = 6
const val PREF_DOH_360 = 7
// ZOUKAN: Quad101 and Shecan were removed; their values stay reserved (backups store the number)
// and are read as Cloudflare (see supportedDohProvider)
const val PREF_DOH_QUAD101 = 8
const val PREF_DOH_MULLVAD = 9
const val PREF_DOH_CONTROLD = 10
const val PREF_DOH_NJALLA = 11
const val PREF_DOH_SHECAN = 12

fun OkHttpClient.Builder.dohCloudflare() = dns(
    DnsOverHttps.Builder().client(/* ZOUKAN --> */dohQueryClient()/* ZOUKAN <-- */)
        .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
        .bootstrapDnsHosts(
            InetAddress.getByName("162.159.36.1"),
            InetAddress.getByName("162.159.46.1"),
            InetAddress.getByName("1.1.1.1"),
            InetAddress.getByName("1.0.0.1"),
            InetAddress.getByName("162.159.132.53"),
            InetAddress.getByName("2606:4700:4700::1111"),
            InetAddress.getByName("2606:4700:4700::1001"),
            InetAddress.getByName("2606:4700:4700::0064"),
            InetAddress.getByName("2606:4700:4700::6400"),
        )
        .build(),
)

fun OkHttpClient.Builder.dohGoogle() = dns(
    DnsOverHttps.Builder().client(/* ZOUKAN --> */dohQueryClient()/* ZOUKAN <-- */)
        .url("https://dns.google/dns-query".toHttpUrl())
        .bootstrapDnsHosts(
            InetAddress.getByName("8.8.4.4"),
            InetAddress.getByName("8.8.8.8"),
            InetAddress.getByName("2001:4860:4860::8888"),
            InetAddress.getByName("2001:4860:4860::8844"),
        )
        .build(),
)

// AdGuard "Default" DNS works too but for the sake of making sure no site is blacklisted,
// we use "Unfiltered"
fun OkHttpClient.Builder.dohAdGuard() = dns(
    DnsOverHttps.Builder().client(/* ZOUKAN --> */dohQueryClient()/* ZOUKAN <-- */)
        .url("https://dns-unfiltered.adguard.com/dns-query".toHttpUrl())
        .bootstrapDnsHosts(
            InetAddress.getByName("94.140.14.140"),
            InetAddress.getByName("94.140.14.141"),
            InetAddress.getByName("2a10:50c0::1:ff"),
            InetAddress.getByName("2a10:50c0::2:ff"),
        )
        .build(),
)

fun OkHttpClient.Builder.dohQuad9() = dns(
    DnsOverHttps.Builder().client(/* ZOUKAN --> */dohQueryClient()/* ZOUKAN <-- */)
        .url("https://dns.quad9.net/dns-query".toHttpUrl())
        .bootstrapDnsHosts(
            InetAddress.getByName("9.9.9.9"),
            InetAddress.getByName("149.112.112.112"),
            InetAddress.getByName("2620:fe::fe"),
            InetAddress.getByName("2620:fe::9"),
        )
        .build(),
)

fun OkHttpClient.Builder.dohAliDNS() = dns(
    DnsOverHttps.Builder().client(/* ZOUKAN --> */dohQueryClient()/* ZOUKAN <-- */)
        .url("https://dns.alidns.com/dns-query".toHttpUrl())
        .bootstrapDnsHosts(
            InetAddress.getByName("223.5.5.5"),
            InetAddress.getByName("223.6.6.6"),
            InetAddress.getByName("2400:3200::1"),
            InetAddress.getByName("2400:3200:baba::1"),
        )
        .build(),
)

fun OkHttpClient.Builder.dohDNSPod() = dns(
    DnsOverHttps.Builder().client(/* ZOUKAN --> */dohQueryClient()/* ZOUKAN <-- */)
        .url("https://doh.pub/dns-query".toHttpUrl())
        .bootstrapDnsHosts(
            InetAddress.getByName("1.12.12.12"),
            InetAddress.getByName("120.53.53.53"),
        )
        .build(),
)

fun OkHttpClient.Builder.doh360() = dns(
    DnsOverHttps.Builder().client(/* ZOUKAN --> */dohQueryClient()/* ZOUKAN <-- */)
        .url("https://doh.360.cn/dns-query".toHttpUrl())
        .bootstrapDnsHosts(
            InetAddress.getByName("101.226.4.6"),
            InetAddress.getByName("218.30.118.6"),
            InetAddress.getByName("123.125.81.6"),
            InetAddress.getByName("140.207.198.6"),
            InetAddress.getByName("180.163.249.75"),
            InetAddress.getByName("101.199.113.208"),
            InetAddress.getByName("36.99.170.86"),
        )
        .build(),
)

// ZOUKAN: dohQuad101() removed (Quad101 is no longer offered)

/*
 * Mullvad DoH
 * without ad blocking option
 * Source: https://mullvad.net/en/help/dns-over-https-and-dns-over-tls
 */
fun OkHttpClient.Builder.dohMullvad() = dns(
    DnsOverHttps.Builder().client(/* ZOUKAN --> */dohQueryClient()/* ZOUKAN <-- */)
        .url(" https://dns.mullvad.net/dns-query".toHttpUrl())
        .bootstrapDnsHosts(
            InetAddress.getByName("194.242.2.2"),
            InetAddress.getByName("2a07:e340::2"),
        )
        .build(),
)

/*
 * Control D
 * unfiltered option
 * Source: https://controld.com/free-dns/?
 */
fun OkHttpClient.Builder.dohControlD() = dns(
    DnsOverHttps.Builder().client(/* ZOUKAN --> */dohQueryClient()/* ZOUKAN <-- */)
        .url("https://freedns.controld.com/p0".toHttpUrl())
        .bootstrapDnsHosts(
            InetAddress.getByName("76.76.2.0"),
            InetAddress.getByName("76.76.10.0"),
            InetAddress.getByName("2606:1a40::"),
            InetAddress.getByName("2606:1a40:1::"),
        )
        .build(),
)

/*
 * Njalla
 * Non logging and uncensored
 */
fun OkHttpClient.Builder.dohNajalla() = dns(
    DnsOverHttps.Builder().client(/* ZOUKAN --> */dohQueryClient()/* ZOUKAN <-- */)
        .url("https://dns.njal.la/dns-query".toHttpUrl())
        .bootstrapDnsHosts(
            InetAddress.getByName("95.215.19.53"),
            InetAddress.getByName("2001:67c:2354:2::53"),
        )
        .build(),
)

// ZOUKAN: dohShecan() removed (Shecan is no longer offered)

// ZOUKAN -->
/**
 * The client that sends the DoH queries: this builder's client (its timeouts, proxy and connection
 * pool) without the app's cookies, cache or interceptors (user agent, logging), so a query to the
 * DNS provider carries nothing that identifies the user or the sites they visit.
 */
internal fun OkHttpClient.Builder.dohQueryClient(): OkHttpClient =
    build().newBuilder()
        .apply {
            interceptors().clear()
            networkInterceptors().clear()
        }
        .cookieJar(CookieJar.NO_COOKIES)
        .cache(null)
        .build()

/** The providers offered, in the settings' order (Quad101's 8 and Shecan's 12 are no longer used). */
val DOH_PROVIDERS = listOf(
    PREF_DOH_CLOUDFLARE,
    PREF_DOH_GOOGLE,
    PREF_DOH_ADGUARD,
    PREF_DOH_QUAD9,
    PREF_DOH_ALIDNS,
    PREF_DOH_DNSPOD,
    PREF_DOH_360,
    PREF_DOH_MULLVAD,
    PREF_DOH_CONTROLD,
    PREF_DOH_NJALLA,
)

/**
 * [provider] as the app uses it: a removed provider (Quad101, Shecan) becomes Cloudflare, so a
 * stored choice of one keeps DNS over HTTPS on instead of falling back to the system's DNS.
 */
fun supportedDohProvider(provider: Int): Int = when (provider) {
    PREF_DOH_QUAD101, PREF_DOH_SHECAN -> PREF_DOH_CLOUDFLARE
    else -> provider
}

/**
 * Moves a stored choice of a removed provider (Quad101, Shecan) to Cloudflare, once at startup, so
 * the settings show the provider in use. Returns whether it changed the preference.
 */
fun NetworkPreferences.migrateRemovedDohProvider(): Boolean {
    val pref = dohProvider()
    val stored = pref.get()
    val supported = supportedDohProvider(stored)
    if (supported == stored) return false
    pref.set(supported)
    logcat(LogPriority.WARN) { "DNS over HTTPS provider $stored is no longer offered; using Cloudflare instead" }
    return true
}

/**
 * The DoH server URL of each provider above, for clients other than OkHttp (the desktop app's
 * Chromium). Null for none (-1) or an unknown provider.
 */
fun dohProviderUrl(provider: Int): String? = when (supportedDohProvider(provider)) {
    PREF_DOH_CLOUDFLARE -> "https://cloudflare-dns.com/dns-query"
    PREF_DOH_GOOGLE -> "https://dns.google/dns-query"
    PREF_DOH_ADGUARD -> "https://dns-unfiltered.adguard.com/dns-query"
    PREF_DOH_QUAD9 -> "https://dns.quad9.net/dns-query"
    PREF_DOH_ALIDNS -> "https://dns.alidns.com/dns-query"
    PREF_DOH_DNSPOD -> "https://doh.pub/dns-query"
    PREF_DOH_360 -> "https://doh.360.cn/dns-query"
    PREF_DOH_MULLVAD -> "https://dns.mullvad.net/dns-query"
    PREF_DOH_CONTROLD -> "https://freedns.controld.com/p0"
    PREF_DOH_NJALLA -> "https://dns.njal.la/dns-query"
    else -> null
}

/**
 * One of the bootstrap addresses above for each provider's DoH server (an IPv4 one, which every
 * network reaches), for clients other than OkHttp: Chromium would otherwise look the server's
 * name up with the system's DNS. Null for none (-1) or an unknown provider.
 */
fun dohProviderBootstrapAddress(provider: Int): String? = when (supportedDohProvider(provider)) {
    PREF_DOH_CLOUDFLARE -> "1.1.1.1"
    PREF_DOH_GOOGLE -> "8.8.8.8"
    PREF_DOH_ADGUARD -> "94.140.14.140"
    PREF_DOH_QUAD9 -> "9.9.9.9"
    PREF_DOH_ALIDNS -> "223.5.5.5"
    PREF_DOH_DNSPOD -> "1.12.12.12"
    PREF_DOH_360 -> "101.226.4.6"
    PREF_DOH_MULLVAD -> "194.242.2.2"
    PREF_DOH_CONTROLD -> "76.76.2.0"
    PREF_DOH_NJALLA -> "95.215.19.53"
    else -> null
}

/**
 * Chromium's `--host-resolver-rules` value that connects to [provider]'s DoH server at its
 * bootstrap address, so that in secure mode no name at all goes to the system's DNS. Null for none.
 */
fun dohProviderHostResolverRule(provider: Int): String? {
    val host = dohProviderUrl(provider)?.toHttpUrl()?.host ?: return null
    val address = dohProviderBootstrapAddress(provider) ?: return null
    return "MAP $host $address"
}

/**
 * This DNS, falling back to the system's for a name it cannot resolve. For self-hosted servers
 * (Komga, Kavita) on a home network, whose names only the local resolver knows: a public name
 * still goes through DNS over HTTPS when it is on, where `Dns.SYSTEM` would send every name to
 * the system resolver. (An IP address needs no lookup: OkHttp connects to it directly.)
 */
fun Dns.withSystemFallback(): Dns {
    if (this === Dns.SYSTEM) return this
    val dns = this
    return Dns { hostname ->
        try {
            dns.lookup(hostname)
        } catch (_: UnknownHostException) {
            Dns.SYSTEM.lookup(hostname)
        }
    }
}
// ZOUKAN <--
