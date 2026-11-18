// ZOUKAN -->
package eu.kanade.tachiyomi.network

import android.content.Context
import okhttp3.Cache
import okhttp3.EventListener
import okhttp3.Interceptor

/** The interceptor that solves Cloudflare challenges for [NetworkHelper.client]. */
internal expect fun cloudflareInterceptor(
    context: Context,
    cookieJar: AndroidCookieJar,
    defaultUserAgentProvider: () -> String,
): Interceptor

/**
 * The HTTP cache of [NetworkHelper]'s clients: on Android a 5 MiB disk cache in the app's cache
 * folder, as upstream; on desktop a 5 MiB cache held only in memory, since one on disk would keep
 * request addresses (search queries) and responses in plain text outside the OS's app sandbox.
 */
internal expect fun networkCache(context: Context): Cache?

/**
 * The event listener of [NetworkHelper]'s clients, or null for OkHttp's default (none): on Android
 * none, as upstream; on desktop one that learns the proxies OkHttp picks for each request, so a
 * proxy a PAC script picks per site is recognised by [isSystemProxyHost] before its name is looked up.
 */
internal expect fun networkEventListener(): EventListener?
// ZOUKAN <--
