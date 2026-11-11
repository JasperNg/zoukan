package zoukan.desktop.exh

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import exh.source.ExhPreferences
import exh.ui.login.EhLoginActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.core.common.util.lang.withNonCancellableContext
import zoukan.desktop.browser.BrowserWindow
import java.net.CookieHandler
import java.net.CookieManager
import java.net.HttpCookie
import java.net.URI

/**
 * Signs in to E-Hentai for ExHentai, as EhLoginActivity: the forum sign-in page opens in the
 * built-in browser, and once its member id and password hash cookies are set, a visit to ExHentai
 * gets the igneous cookie. The three go into ExhPreferences, which E-Hentai sends with every request.
 */
class EhLogin(private val exhPreferences: ExhPreferences, private val network: NetworkHelper) {

    /** Returns true when signed in; false when the user closed the window first. */
    suspend fun login(customIgneous: String? = null): Boolean = withNonCancellableContext {
        // Not cancellable: a sign-in finished after the user left Settings must still be saved
        if (!inBrowser.compareAndSet(false, true)) error("The E-Hentai sign-in window is already open")
        try {
            browserLogin(customIgneous)
        } finally {
            inBrowser.set(false)
        }
    }

    private val inBrowser = java.util.concurrent.atomic.AtomicBoolean(false)

    private suspend fun browserLogin(customIgneous: String?): Boolean {
        val opened = withContext(Dispatchers.IO) {
            val window = BrowserWindow(
                LOGIN_URL,
                userAgent = null,
                message = "Sign in to E-Hentai. The window closes by itself when you are signed in.",
            )
            window.open().also { if (it) window.await(LOGIN_TIMEOUT_MS) { hasForumCookies() } }
        }
        if (!opened) error("The built-in browser could not start")
        if (!hasForumCookies()) return false
        return finish(customIgneous)
    }

    /** Signs in with cookies copied from another browser. */
    suspend fun loginWithCookies(memberId: String, passHash: String, igneous: String?): Boolean {
        val store = cookieManager()?.cookieStore ?: error("No cookie store")
        listOf(EH, FORUMS, EXH).forEach { uri ->
            store.add(uri, cookie(EhLoginActivity.MEMBER_ID_COOKIE, memberId, uri))
            store.add(uri, cookie(EhLoginActivity.PASS_HASH_COOKIE, passHash, uri))
        }
        return finish(igneous?.takeIf { it.isNotBlank() })
    }

    private suspend fun finish(customIgneous: String?): Boolean {
        val memberId = cookie(FORUMS, EhLoginActivity.MEMBER_ID_COOKIE) ?: cookie(EH, EhLoginActivity.MEMBER_ID_COOKIE)
        val passHash = cookie(FORUMS, EhLoginActivity.PASS_HASH_COOKIE) ?: cookie(EH, EhLoginActivity.PASS_HASH_COOKIE)
        if (memberId == null || passHash == null) return false
        val store = cookieManager()?.cookieStore
        store?.add(EXH, cookie(EhLoginActivity.MEMBER_ID_COOKIE, memberId, EXH))
        store?.add(EXH, cookie(EhLoginActivity.PASS_HASH_COOKIE, passHash, EXH))
        // ExHentai sets igneous on the first visit of a member with access
        val igneous = customIgneous ?: withContext(Dispatchers.IO) {
            runCatching { network.client.newCall(GET(EXH.toString())).execute().close() }
            cookie(EXH, EhLoginActivity.IGNEOUS_COOKIE)?.takeIf { it.isNotBlank() && it != "mystery" }
        } ?: error("ExHentai did not give access to this account. Check that it can open exhentai.org.")
        exhPreferences.memberIdVal().set(memberId)
        exhPreferences.passHashVal().set(passHash)
        exhPreferences.igneousVal().set(igneous)
        exhPreferences.enableExhentai().set(true)
        return true
    }

    fun logout() {
        exhPreferences.enableExhentai().set(false)
        exhPreferences.memberIdVal().delete()
        exhPreferences.passHashVal().delete()
        exhPreferences.igneousVal().delete()
        exhPreferences.exhSettingsProfile().delete()
        exhPreferences.exhSettingsKey().delete()
        exhPreferences.exhSessionCookie().delete()
        exhPreferences.exhHathPerksCookies().delete()
        val store = cookieManager()?.cookieStore ?: return
        listOf(EH, FORUMS, EXH).forEach { uri ->
            store.get(uri).filter { it.name in COOKIES }.forEach { store.remove(uri, it) }
        }
    }

    val isLoggedIn: Boolean get() = exhPreferences.enableExhentai().get()

    private fun hasForumCookies(): Boolean =
        (cookie(FORUMS, EhLoginActivity.MEMBER_ID_COOKIE) ?: cookie(EH, EhLoginActivity.MEMBER_ID_COOKIE)) != null &&
            (cookie(FORUMS, EhLoginActivity.PASS_HASH_COOKIE) ?: cookie(EH, EhLoginActivity.PASS_HASH_COOKIE)) != null

    private fun cookie(uri: URI, name: String): String? =
        cookieManager()?.cookieStore?.get(uri)?.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value
            ?.takeIf { it.isNotBlank() && it != "0" }

    private fun cookieManager(): CookieManager? = CookieHandler.getDefault() as? CookieManager

    private fun cookie(name: String, value: String, uri: URI) = HttpCookie(name, value).apply {
        domain = ".${uri.host.removePrefix("forums.")}"
        path = "/"
        maxAge = 365L * 24 * 60 * 60
    }

    companion object {
        private const val LOGIN_URL = "https://forums.e-hentai.org/index.php?act=Login"
        private const val LOGIN_TIMEOUT_MS = 15 * 60 * 1000L
        private val EH = URI("https://e-hentai.org/")
        private val FORUMS = URI("https://forums.e-hentai.org/")
        private val EXH = URI("https://exhentai.org/")
        private val COOKIES = setOf(
            EhLoginActivity.MEMBER_ID_COOKIE,
            EhLoginActivity.PASS_HASH_COOKIE,
            EhLoginActivity.IGNEOUS_COOKIE,
        )
    }
}
