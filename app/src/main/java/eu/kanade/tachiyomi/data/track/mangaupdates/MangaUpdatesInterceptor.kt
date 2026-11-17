package eu.kanade.tachiyomi.data.track.mangaupdates

import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.track.checkTokenAccepted
import eu.kanade.tachiyomi.data.track.clearLoginExpired
import eu.kanade.tachiyomi.data.track.throwIfLoginExpired
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

class MangaUpdatesInterceptor(
    // ZOUKAN: kept, to mark the login expired
    private val mangaUpdates: MangaUpdates,
) : Interceptor {

    private var token: String? = mangaUpdates.restoreSession()

    override fun intercept(chain: Interceptor.Chain): Response {
        // ZOUKAN: a refused session marks the login expired, as MAL does (TrackerAuthExpired.kt)
        mangaUpdates.throwIfLoginExpired()
        val originalRequest = chain.request()

        val token = token ?: throw IOException("Not authenticated with MangaUpdates")

        // Add the authorization header to the original request.
        val authRequest = originalRequest.newBuilder()
            .addHeader("Authorization", "Bearer $token")
            .header("User-Agent", "Komikku v${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})")
            .build()

        // ZOUKAN: a 401 marks the login expired
        return mangaUpdates.checkTokenAccepted(chain.proceed(authRequest))
    }

    fun newAuth(token: String?) {
        this.token = token
        // ZOUKAN: a new session is no longer expired
        if (token != null) mangaUpdates.clearLoginExpired()
    }
}
