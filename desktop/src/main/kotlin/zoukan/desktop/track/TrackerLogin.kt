package zoukan.desktop.track

import android.net.Uri
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.anilist.AnilistApi
import eu.kanade.tachiyomi.data.track.bangumi.BangumiApi
import eu.kanade.tachiyomi.data.track.myanimelist.MyAnimeListApi
import eu.kanade.tachiyomi.data.track.shikimori.ShikimoriApi
import exh.md.utils.MdConstants
import exh.md.utils.MdUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.core.common.util.lang.withNonCancellableContext
import zoukan.desktop.browser.BrowserWindow
import java.util.concurrent.atomic.AtomicReference

/**
 * Tracker sign-ins on desktop.
 *
 * MyAnimeList, AniList, Shikimori and Bangumi sign in with OAuth. Their apps are registered with
 * redirect addresses in the app's own scheme (`komikku://anilist-auth`), which a desktop has no
 * handler for, so the sign-in page opens in the built-in browser and the app catches the redirect
 * there, before the browser would try to open it. That needs no change to the trackers' app
 * registrations. The result is handled as TrackLoginActivity handles it on Android.
 *
 * Kitsu and MangaUpdates take a user name and password ([loginWithPassword]); Komga, Kavita and
 * Suwayomi are enhanced trackers that sign in through their source.
 */
class TrackerLogin(private val trackerManager: TrackerManager) {

    enum class Kind { OAUTH, PASSWORD, ENHANCED }

    fun kindOf(tracker: Tracker): Kind = when (tracker.id) {
        trackerManager.myAnimeList.id, trackerManager.aniList.id, trackerManager.shikimori.id, trackerManager.bangumi.id,
        trackerManager.mdList.id,
        ->
            Kind.OAUTH
        trackerManager.kitsu.id, trackerManager.mangaUpdates.id -> Kind.PASSWORD
        else -> Kind.ENHANCED
    }

    /** What the password dialog calls the user name. */
    fun userNameLabel(tracker: Tracker): String = if (tracker.id == trackerManager.kitsu.id) "Email" else "User name"

    fun authUrl(tracker: Tracker): String? = when (tracker.id) {
        trackerManager.myAnimeList.id -> MyAnimeListApi.authUrl()
        trackerManager.aniList.id -> AnilistApi.authUrl()
        trackerManager.shikimori.id -> ShikimoriApi.authUrl()
        trackerManager.bangumi.id -> BangumiApi.authUrl()
        // MDList signs in to MangaDex, through the MangaDex extension
        trackerManager.mdList.id -> {
            MdUtil.getEnabledMangaDex() ?: error("Install the MangaDex extension first: MDList signs in through it")
            MdConstants.Login.authUrl(MdUtil.getPkceChallengeCode())
        }
        else -> null
    }?.toString()

    /**
     * Signs in to an OAuth tracker in the built-in browser. Returns true when signed in, false when
     * the user closed the window first. Throws when the browser cannot start or the tracker refuses.
     */
    suspend fun loginWithBrowser(tracker: Tracker): Boolean = withNonCancellableContext {
        // Not cancellable: the window stays open when the user leaves Settings, and a sign-in
        // finished there must still be saved. One window per tracker at a time.
        if (!inBrowser.add(tracker.id)) error("A sign-in window for ${tracker.name} is already open")
        try {
            browserLogin(tracker)
        } finally {
            inBrowser.remove(tracker.id)
        }
    }

    private val inBrowser = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    private suspend fun browserLogin(tracker: Tracker): Boolean {
        val url = authUrl(tracker) ?: error("${tracker.name} does not sign in with a browser")
        val redirect = AtomicReference<String?>(null)
        val opened = withContext(Dispatchers.IO) {
            val window = BrowserWindow(
                url,
                userAgent = null,
                message = "Sign in to ${tracker.name}. The window closes by itself when you are done.",
                interceptNavigation = { target ->
                    if (isRedirect(target)) {
                        redirect.set(target)
                        true
                    } else {
                        false
                    }
                },
            )
            window.open().also { if (it) window.await(LOGIN_TIMEOUT_MS) { redirect.get() != null } }
        }
        if (!opened) error("The built-in browser could not start")
        val result = redirect.get() ?: return false
        return handleRedirect(result)
    }

    /**
     * Handles a redirect address, as TrackLoginActivity does: the token or code is in the query or
     * the fragment. Also used when the user pastes the address from another browser.
     */
    suspend fun handleRedirect(address: String): Boolean {
        val uri = Uri.parse(address.trim())
        val data = parseParameters(uri.encodedQuery?.takeIf { it.isNotBlank() } ?: uri.encodedFragment)
        if (uri.host == "mangadex-auth") {
            val mangaDex = MdUtil.getEnabledMangaDex() ?: error("The MangaDex extension is not installed")
            val code = data["code"]
            return withContext(Dispatchers.IO) {
                if (code != null) mangaDex.login(code) else mangaDex.logout().let { false }
            } && trackerManager.mdList.isLoggedIn
        }
        val tracker = when (uri.host) {
            "anilist-auth" -> trackerManager.aniList
            "bangumi-auth" -> trackerManager.bangumi
            "myanimelist-auth" -> trackerManager.myAnimeList
            "shikimori-auth" -> trackerManager.shikimori
            else -> error("Not a tracker sign-in address: $address")
        }
        val value = if (tracker == trackerManager.aniList) data["access_token"] else data["code"]
        withContext(Dispatchers.IO) {
            when {
                value == null -> tracker.logout()
                tracker == trackerManager.aniList -> trackerManager.aniList.login(value)
                tracker == trackerManager.bangumi -> trackerManager.bangumi.login(value)
                tracker == trackerManager.myAnimeList -> trackerManager.myAnimeList.login(value)
                else -> trackerManager.shikimori.login(value)
            }
        }
        return value != null && tracker.isLoggedIn
    }

    suspend fun loginWithPassword(tracker: Tracker, userName: String, password: String) {
        withContext(Dispatchers.IO) { tracker.login(userName, password) }
    }

    companion object {
        private const val LOGIN_TIMEOUT_MS = 10 * 60 * 1000L
        private val SCHEMES = setOf("komikku", "mihon", "tachiyomisy")
        private val HOSTS = setOf("anilist-auth", "bangumi-auth", "myanimelist-auth", "shikimori-auth", "mangadex-auth")

        fun isRedirect(address: String): Boolean {
            val scheme = address.substringBefore("://", "").lowercase()
            if (scheme !in SCHEMES) return false
            val host = address.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
            return host.lowercase() in HOSTS
        }

        fun parseParameters(encoded: String?): Map<String, String?> = encoded.orEmpty()
            .split("&")
            .filter { it.isNotBlank() }
            .associate {
                val parts = it.split("=", limit = 2).map(Uri::decode)
                parts[0] to parts.getOrNull(1)
            }
    }
}
