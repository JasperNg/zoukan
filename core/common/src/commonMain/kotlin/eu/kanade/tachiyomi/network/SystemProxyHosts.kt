// ZOUKAN -->
package eu.kanade.tachiyomi.network

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI

/**
 * Whether [hostname] is the host name of a proxy the system's proxy settings currently give
 * ([ProxySelector.getDefault], asked for a few probe addresses). Such a name must go to the
 * system's DNS: only the local network may know it, and with DNS over HTTPS on, its query to the
 * provider would itself need the proxy. A trailing dot and case are ignored.
 *
 * The probe addresses miss a proxy that a PAC script picks only for some sites; so the proxies
 * OkHttp actually picked for the app's requests count too ([SystemProxyHosts.remember], called on
 * desktop before OkHttp looks the proxy's name up).
 *
 * The answer is kept for a few seconds (and again whenever the default selector changes), so a
 * lookup costs no more than a comparison. This only reads the proxy settings, never sets them,
 * and never throws: when the settings cannot be read, no name is a proxy host.
 */
fun isSystemProxyHost(hostname: String): Boolean {
    val name = hostname.trimEnd('.').lowercase()
    if (name.isEmpty()) return false
    return name in SystemProxyHosts.current() || name in SystemProxyHosts.remembered()
}

internal object SystemProxyHosts {
    private const val MAX_AGE_NANOS = 10_000_000_000L // 10 s

    /** Addresses a proxy is asked for: each scheme the app's clients and the JDK's sockets use. */
    private val PROBES = listOf("http", "https", "socket").mapNotNull {
        runCatching { URI("$it://zoukan-proxy-probe.example/") }.getOrNull()
    }

    private class Snapshot(val selector: ProxySelector?, val hosts: Set<String>, val at: Long)

    /** At most this many proxy names picked for requests are kept, the oldest dropped first. */
    private const val MAX_REMEMBERED = 32

    @Volatile
    private var snapshot: Snapshot? = null

    /** Proxy names OkHttp picked for requests this run, oldest first; replaced, never changed. */
    @Volatile
    private var remembered: Set<String> = emptySet()
    private val rememberLock = Any()

    /** Set while this thread reads the proxy settings, so a lookup they might make cannot loop. */
    private val reading: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }

    fun current(): Set<String> {
        if (reading.get()) return snapshot?.hosts.orEmpty()
        val selector = try {
            ProxySelector.getDefault()
        } catch (_: Throwable) {
            null
        }
        val now = System.nanoTime()
        val cached = snapshot
        if (cached != null && cached.selector === selector && now - cached.at < MAX_AGE_NANOS) return cached.hosts
        val hosts = read(selector)
        snapshot = Snapshot(selector, hosts, now)
        return hosts
    }

    private fun read(selector: ProxySelector?): Set<String> {
        if (selector == null) return emptySet()
        reading.set(true)
        return try {
            PROBES.flatMapTo(mutableSetOf()) { uri ->
                val proxies = try {
                    selector.select(uri).orEmpty()
                } catch (_: Throwable) {
                    emptyList()
                }
                proxies.mapNotNull { proxy -> proxyHost(proxy) }
            }
        } catch (_: Throwable) {
            emptySet()
        } finally {
            reading.set(false)
        }
    }

    fun remembered(): Set<String> = remembered

    /**
     * Counts the proxies in [proxies] (what the system's proxy settings gave for a request) as
     * proxy hosts from now on, for the rest of the run. Never throws.
     */
    fun remember(proxies: List<Proxy>) {
        try {
            val names = proxies.mapNotNull(::proxyHost)
            if (names.isEmpty() || remembered.containsAll(names)) return
            synchronized(rememberLock) {
                val updated = LinkedHashSet(remembered)
                names.forEach { name ->
                    // Moved to the end: the most recently picked are kept
                    updated.remove(name)
                    updated.add(name)
                }
                while (updated.size > MAX_REMEMBERED) updated.remove(updated.first())
                remembered = updated
            }
        } catch (_: Throwable) {
            // A proxy that cannot be read is left out
        }
    }

    /** The host name of [proxy] as configured (never resolved), or null for a direct connection. */
    private fun proxyHost(proxy: Proxy?): String? {
        if (proxy == null) return null
        val type = proxy.type()
        if (type != Proxy.Type.HTTP && type != Proxy.Type.SOCKS) return null
        val address = proxy.address() as? InetSocketAddress ?: return null
        return address.hostString?.trimEnd('.')?.lowercase()?.takeIf { it.isNotEmpty() }
    }
}
// ZOUKAN <--
