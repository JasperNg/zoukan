// ZOUKAN -->
package eu.kanade.tachiyomi.network

import android.content.Context
import okhttp3.Interceptor

/** Whether network requests and responses are logged (the "extra" log level). */
internal expect fun isExtraNetworkLogging(): Boolean

/** The interceptor that solves Cloudflare challenges for [NetworkHelper.client]. */
internal expect fun cloudflareInterceptor(
    context: Context,
    cookieJar: AndroidCookieJar,
    defaultUserAgentProvider: () -> String,
): Interceptor
// ZOUKAN <--
