// ZOUKAN -->
package eu.kanade.tachiyomi.network

import android.content.Context
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import okhttp3.Interceptor

internal actual fun cloudflareInterceptor(
    context: Context,
    cookieJar: AndroidCookieJar,
    defaultUserAgentProvider: () -> String,
): Interceptor = CloudflareInterceptor(context, cookieJar, defaultUserAgentProvider)
// ZOUKAN <--
