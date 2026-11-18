package zoukan.desktop.network

import eu.kanade.tachiyomi.network.isDohQueryThread
import eu.kanade.tachiyomi.network.isLocalOnlyHostName
import eu.kanade.tachiyomi.network.isSystemProxyHost
import okhttp3.Dns
import xyz.nulldev.androidcompat.util.rethrowIfFatal
import java.net.InetAddress
import java.net.UnknownHostException
import java.net.spi.InetAddressResolver
import java.net.spi.InetAddressResolver.LookupPolicy
import java.net.spi.InetAddressResolverProvider
import java.util.function.Supplier
import java.util.stream.Stream

/**
 * Sends the JDK's own name lookups (`InetAddress`, so `URL.openConnection`, `ImageIO.read(URL)`,
 * `java.net.http` and an extension's code that does not use OkHttp) through the app's DNS, so with
 * DNS over HTTPS on they reach the provider and not the system's DNS.
 *
 * Registered in `META-INF/services` and so always loaded, it decides at each lookup:
 * - DNS over HTTPS off, or the app not started yet ([setDnsProvider] not called): the JDK's
 *   built-in resolver, as without this class;
 * - this computer's own name, and [local-only names][isLocalOnlyHostName] (`localhost`, `nas`,
 *   `nas.local`): the built-in resolver, as only the local network can answer them;
 * - the system proxy's own host name ([isSystemProxyHost]): the built-in resolver, as the proxy may
 *   be known only to the local network and a DoH query for it could itself need the proxy;
 * - a lookup made on a DNS over HTTPS query's thread ([isDohQueryThread]: the JDK resolving a SOCKS
 *   proxy's name for the query's connection): the built-in resolver, so it never waits on DoH;
 * - a lookup made while this thread is already in one (the app's DNS calling the system's): the
 *   built-in resolver, so a lookup can never loop;
 * - anything else: the app's DNS. When that fails the lookup fails; it never falls back to the
 *   system's DNS.
 *
 * Reverse lookups (address to name) fail while DNS over HTTPS is on, so callers get the address
 * text and the address never goes to the system's DNS.
 *
 * Nothing here throws anything but [UnknownHostException]: a failure of the app's DNS becomes one
 * (a fatal error, such as running out of memory, goes on up as it is).
 */
class AppDnsResolverProvider : InetAddressResolverProvider() {
    override fun get(configuration: Configuration): InetAddressResolver {
        val builtin = configuration.builtinResolver()
        return try {
            AppDnsResolver(builtin, configuration.lookupLocalHostName(), ::currentDns)
        } catch (_: Throwable) {
            // Never stop InetAddress from loading: without this resolver, the JDK's own
            builtin
        }
    }

    override fun name(): String = "Zoukan app DNS"

    companion object {
        @Volatile
        private var dnsProvider: Supplier<Dns?>? = null

        /**
         * Set by the app at startup: its client's DNS (`NetworkHelper.client.dns`), asked at each
         * lookup. Null (the default) leaves every lookup to the JDK's built-in resolver.
         */
        fun setDnsProvider(provider: Supplier<Dns?>?) {
            dnsProvider = provider
        }

        /**
         * The DNS a public name goes to: null for the built-in resolver (the app not started, or DNS
         * over HTTPS off). Throws when the app's DNS cannot be had.
         */
        private fun currentDns(): Dns? {
            val dns = dnsProvider?.get() ?: return null
            return if (dns === Dns.SYSTEM) null else dns
        }
    }
}

internal class AppDnsResolver(
    private val builtin: InetAddressResolver,
    localHostName: String,
    private val appDns: () -> Dns?,
) : InetAddressResolver {
    private val localHostName = localHostName.trimEnd('.').lowercase()

    override fun lookupByName(host: String, lookupPolicy: LookupPolicy): Stream<InetAddress> {
        val dns = if (inLookup.get() || isDohQueryThread() || isLocalName(host)) null else appDnsOrFail(host)
        if (dns == null) return builtin.lookupByName(host, lookupPolicy)
        val addresses = withLookupGuard(host) { dns.lookup(host) }
        val chosen = applyPolicy(addresses, lookupPolicy.characteristics())
        if (chosen.isEmpty()) throw UnknownHostException(host)
        return chosen.stream()
    }

    override fun lookupByAddress(addr: ByteArray): String {
        // While DNS over HTTPS is on, an address is never sent to the system's DNS: InetAddress
        // then gives the address text as the name
        val dohOn = !inLookup.get() && runCatching { appDns() }.let { it.isFailure || it.getOrNull() != null }
        if (dohOn) throw UnknownHostException("Reverse lookups are off while DNS over HTTPS is on")
        return builtin.lookupByAddress(addr)
    }

    private fun isLocalName(host: String): Boolean =
        isLocalOnlyHostName(host) || host.trimEnd('.').equals(localHostName, ignoreCase = true) || isSystemProxyHost(host)

    /** The app's DNS, or null for the built-in resolver; when it cannot be had, the lookup fails. */
    private fun appDnsOrFail(host: String): Dns? = try {
        appDns()
    } catch (e: Throwable) {
        e.rethrowIfFatal()
        throw unknownHost(host, e)
    }

    private inline fun <T> withLookupGuard(host: String, block: () -> T): T {
        inLookup.set(true)
        return try {
            block()
        } catch (e: UnknownHostException) {
            throw e
        } catch (e: Throwable) {
            e.rethrowIfFatal()
            throw unknownHost(host, e)
        } finally {
            inLookup.set(false)
        }
    }

    private fun unknownHost(host: String, cause: Throwable): UnknownHostException {
        if (cause is InterruptedException) Thread.currentThread().interrupt()
        return UnknownHostException(host).apply { initCause(cause) }
    }

    internal companion object {
        /** Whether this thread is inside a lookup this resolver sent to the app's DNS. */
        private val inLookup: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }

        /** [addresses] filtered and ordered as [characteristics] (a [LookupPolicy]'s) ask. */
        fun applyPolicy(addresses: List<InetAddress>, characteristics: Int): List<InetAddress> {
            val v4 = (characteristics and LookupPolicy.IPV4) != 0
            val v6 = (characteristics and LookupPolicy.IPV6) != 0
            val allowed = addresses.filter {
                (v4 && it.address.size == IPV4_SIZE) || (v6 && it.address.size != IPV4_SIZE)
            }
            return when {
                (characteristics and LookupPolicy.IPV4_FIRST) != 0 -> allowed.sortedBy { it.address.size != IPV4_SIZE }
                (characteristics and LookupPolicy.IPV6_FIRST) != 0 -> allowed.sortedBy { it.address.size == IPV4_SIZE }
                else -> allowed
            }
        }

        private const val IPV4_SIZE = 4
    }
}
