// ZOUKAN -->
package eu.kanade.tachiyomi.network

import android.content.Context
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import logcat.LogPriority
import okhttp3.Cache
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okio.Path.Companion.toPath
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.net.Proxy
import java.util.concurrent.atomic.AtomicBoolean

internal actual fun cloudflareInterceptor(
    context: Context,
    cookieJar: AndroidCookieJar,
    defaultUserAgentProvider: () -> String,
): Interceptor = CloudflareInterceptor(context, cookieJar, defaultUserAgentProvider)

/**
 * A 5 MiB HTTP cache, as Android's, but held only in memory ([MemoryNetworkCache]): Android's on
 * disk kept full request addresses (search queries) and API responses in plain text in the data
 * folder. The folder earlier versions left is deleted, once per run.
 */
internal actual fun networkCache(context: Context): Cache? {
    OldNetworkCache.removeOnce(context)
    return MemoryNetworkCache.cache
}

/** [SelectedProxyRecorder]. */
internal actual fun networkEventListener(): EventListener? = SelectedProxyRecorder

/**
 * Remembers the proxies OkHttp picks for each request, as [isSystemProxyHost]'s proxy names: the
 * system's proxy settings can give a different proxy per site (a PAC script), which the probe
 * addresses [isSystemProxyHost] asks for would miss, and its name would then go to DNS over HTTPS.
 * OkHttp reports the pick before it looks the proxy's name up, so the lookup already sees it.
 */
object SelectedProxyRecorder : EventListener() {
    override fun proxySelectEnd(call: Call, url: HttpUrl, proxies: List<Proxy>) {
        SystemProxyHosts.remember(proxies)
    }
}

/** The one cache all of [NetworkHelper]'s clients share, created when first needed. */
private object MemoryNetworkCache {
    private const val MAX_SIZE = 5L * 1024 * 1024 // 5 MiB, as Android's

    val cache: Cache? by lazy {
        try {
            val fileSystem = MemoryFileSystem(
                // Room for the cache itself and for responses being stored at the same time
                maxTotalBytes = 3 * MAX_SIZE,
                // A larger response could not be kept anyway
                maxFileBytes = MAX_SIZE,
                // The cache's own small journal is never refused, or the cache would stop storing
                uncapped = { it.name.startsWith("journal") },
            )
            Cache(fileSystem, "/network_cache".toPath(), MAX_SIZE)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Could not create the network cache" }
            null
        }
    }
}

private object OldNetworkCache {
    private val removed = AtomicBoolean(false)

    fun removeOnce(context: Context) {
        if (!removed.compareAndSet(false, true)) return
        try {
            val old = File(context.cacheDir, "network_cache")
            if (old.exists() && !old.deleteRecursively()) logcat(LogPriority.WARN) { "Could not delete the old network cache" }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Could not delete the old network cache" }
        }
    }
}
// ZOUKAN <--
