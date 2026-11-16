package zoukan.desktop.browser

import android.app.Application
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.interceptor.ClearanceUserAgents
import eu.kanade.tachiyomi.network.interceptor.DesktopBrowser
import logcat.LogPriority
import okhttp3.HttpUrl.Companion.toHttpUrl
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

private object ClearanceRecording

/**
 * Remembers [userAgent] (Chromium's own when null) for the cf_clearance cookie the app holds for
 * [url], when it has no record yet: the user passed the check in a browser window or an extension's
 * WebView, so Cloudflare accepts the cookie only with that user agent. Never throws: it runs on
 * Chromium's threads.
 */
fun recordClearanceUserAgent(url: String, userAgent: String?) {
    runCatching {
        val clearance = Injekt.get<NetworkHelper>().cookieJar.get(url.toHttpUrl()).firstOrNull { it.name == "cf_clearance" }
        ClearanceUserAgents.forContext(Injekt.get<Application>())
            .recordIfUnknown(clearance, userAgent ?: DesktopBrowser.userAgent)
    }.onFailure { ClearanceRecording.logcat(LogPriority.WARN, it) { "Remembering the user agent of a browser clearance failed" } }
}
