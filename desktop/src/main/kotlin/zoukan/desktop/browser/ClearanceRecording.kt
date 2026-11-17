package zoukan.desktop.browser

import android.app.Application
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.interceptor.ClearanceUserAgents
import eu.kanade.tachiyomi.network.interceptor.DesktopBrowser
import logcat.LogPriority
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

private object ClearanceRecording

/**
 * Remembers [userAgent] (Chromium's own when null) for the cf_clearance cookie the app holds for
 * [url], when it has no record yet: the user passed the check in a browser window or an extension's
 * WebView, so Cloudflare accepts the cookie only with that user agent. Pages that aren't http(s),
 * such as the `about:blank` every browser window loads first, hold no cookies and are skipped.
 * Never throws: it runs on Chromium's threads.
 */
fun recordClearanceUserAgent(url: String, userAgent: String?) {
    val httpUrl = clearanceUrlOrNull(url) ?: return
    runCatching {
        val clearance = Injekt.get<NetworkHelper>().cookieJar.get(httpUrl).firstOrNull { it.name == "cf_clearance" }
        ClearanceUserAgents.forContext(Injekt.get<Application>())
            .recordIfUnknown(clearance, userAgent ?: DesktopBrowser.userAgent)
    }.onFailure { ClearanceRecording.logcat(LogPriority.WARN, it) { "Remembering the user agent of a browser clearance failed" } }
}

/** [url] as an http(s) URL, or null for `about:blank`, `data:`, `chrome://` and other pages without cookies. */
internal fun clearanceUrlOrNull(url: String): HttpUrl? = url.toHttpUrlOrNull()
