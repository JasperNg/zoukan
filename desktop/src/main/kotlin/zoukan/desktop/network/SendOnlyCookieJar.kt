package zoukan.desktop.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * Sends the cookies [delegate] has for a request but keeps none that the response sets. For images
 * fetched by address (extension icons, tracker covers, description images from any host): a sign-in
 * or a Cloudflare clearance still reaches them, but an image host or ad server cannot leave a
 * tracking cookie in the store that every source shares.
 */
class SendOnlyCookieJar(private val delegate: CookieJar) : CookieJar {
    override fun loadForRequest(url: HttpUrl): List<Cookie> = delegate.loadForRequest(url)

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
}
