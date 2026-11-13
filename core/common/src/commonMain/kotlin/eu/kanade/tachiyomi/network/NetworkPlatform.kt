// ZOUKAN -->
package eu.kanade.tachiyomi.network

import android.content.Context
import okhttp3.Interceptor

/** The interceptor that solves Cloudflare challenges for [NetworkHelper.client]. */
internal expect fun cloudflareInterceptor(
    context: Context,
    cookieJar: AndroidCookieJar,
    defaultUserAgentProvider: () -> String,
): Interceptor
// ZOUKAN <--
