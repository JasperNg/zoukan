// ZOUKAN -->
package eu.kanade.tachiyomi.network

import android.content.Context
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import okhttp3.Cache
import okhttp3.EventListener
import okhttp3.Interceptor
import java.io.File

internal actual fun cloudflareInterceptor(
    context: Context,
    cookieJar: AndroidCookieJar,
    defaultUserAgentProvider: () -> String,
): Interceptor = CloudflareInterceptor(context, cookieJar, defaultUserAgentProvider)

internal actual fun networkCache(context: Context): Cache? = Cache(
    directory = File(context.cacheDir, "network_cache"),
    maxSize = 5L * 1024 * 1024, // 5 MiB
)

internal actual fun networkEventListener(): EventListener? = null
// ZOUKAN <--
