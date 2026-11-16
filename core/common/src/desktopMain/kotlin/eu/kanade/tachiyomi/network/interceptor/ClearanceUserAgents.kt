// ZOUKAN -->
package eu.kanade.tachiyomi.network.interceptor

import android.content.Context
import eu.kanade.tachiyomi.util.lang.Hash
import okhttp3.Cookie

/**
 * Remembers which user agent each cf_clearance cookie was obtained with. Cloudflare accepts the
 * cookie only with that user agent, so a request is sent with it when its own differs.
 *
 * Records are kept per cookie domain, with a hash of the cookie's value: a record whose hash does
 * not match the current cookie (it expired, was cleared or replaced) is ignored.
 */
class ClearanceUserAgents(private val store: Store) {

    /** Persistent string storage; the app backs it with SharedPreferences. */
    interface Store {
        fun get(key: String): String?
        fun put(key: String, value: String)
    }

    /** Records that [cookie] was obtained with [userAgent]. */
    fun record(cookie: Cookie, userAgent: String) {
        store.put(cookie.domain, "${fingerprint(cookie)}$SEPARATOR$userAgent")
    }

    /** Records [userAgent] for [clearance] when it has no record yet: a clearance the user got in a browser window. */
    fun recordIfUnknown(clearance: Cookie?, userAgent: String?) {
        if (clearance == null || userAgent == null || userAgentOf(clearance) != null) return
        record(clearance, userAgent)
    }

    /** The user agent [cookie] was obtained with, or null when it is not known. */
    fun userAgentOf(cookie: Cookie): String? {
        val value = store.get(cookie.domain) ?: return null
        val fingerprint = value.substringBefore(SEPARATOR, missingDelimiterValue = "")
        if (fingerprint != fingerprint(cookie)) return null
        return value.substringAfter(SEPARATOR).takeIf { it.isNotEmpty() }
    }

    private fun fingerprint(cookie: Cookie): String = Hash.sha256(cookie.value)

    companion object {
        /** The app's records, kept in the `cloudflare_clearance` preferences. */
        fun forContext(context: Context): ClearanceUserAgents {
            val preferences = context.getSharedPreferences("cloudflare_clearance", Context.MODE_PRIVATE)
            return ClearanceUserAgents(
                object : Store {
                    override fun get(key: String): String? = preferences.getString(key, null)
                    override fun put(key: String, value: String) = preferences.edit().putString(key, value).apply()
                },
            )
        }

        // A user agent never contains a line break
        private const val SEPARATOR = '\n'

        /**
         * The user agent to send a request with [requestUserAgent] with, to a host whose clearance
         * was obtained with [clearanceUserAgent]; null keeps the request's own.
         *
         * A clearance of unknown origin (obtained before records were kept) was solved with
         * Chromium's own user agent, [browserUserAgent].
         */
        fun overrideFor(requestUserAgent: String?, clearanceUserAgent: String?, browserUserAgent: String?): String? {
            val obtainedWith = clearanceUserAgent ?: browserUserAgent ?: return null
            return obtainedWith.takeIf { it != requestUserAgent }
        }

        /**
         * The user agents to try a challenge with, in order: the request's own first, like Android's
         * WebView does, so the clearance works with the extension's user agent; then Chromium's,
         * which Cloudflare accepts when it rejects a user agent that does not match the browser
         * (an Android one on desktop Chromium, say). The last one is used for the browser window.
         */
        fun solveOrder(requestUserAgent: String, browserUserAgent: String?): List<String> =
            listOfNotNull(requestUserAgent, browserUserAgent).distinct()
    }
}
// ZOUKAN <--
