package eu.kanade.tachiyomi.data.track.kitsu

import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.track.checkTokenAccepted
import eu.kanade.tachiyomi.data.track.clearLoginExpired
import eu.kanade.tachiyomi.data.track.kitsu.dto.KitsuOAuth
import eu.kanade.tachiyomi.data.track.kitsu.dto.isExpired
import eu.kanade.tachiyomi.data.track.throwIfLoginExpired
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.io.IOException

class KitsuInterceptor(private val kitsu: Kitsu) : Interceptor {

    private val json: Json by injectLazy()

    /**
     * OAuth object used for authenticated requests.
     */
    private var oauth: KitsuOAuth? = kitsu.restoreToken()

    override fun intercept(chain: Interceptor.Chain): Response {
        // ZOUKAN: a refused token marks the login expired, as MAL does (TrackerAuthExpired.kt)
        kitsu.throwIfLoginExpired()
        val originalRequest = chain.request()

        // ZOUKAN -->
        // IOExceptions, which OkHttp hands to the caller; a plain Exception or `!!` crashed its thread
        val currAuth = oauth ?: throw IOException("Not authenticated with Kitsu")

        // Refresh access token if expired.
        if (currAuth.isExpired()) {
            val refreshToken = currAuth.refreshToken ?: throw IOException("Kitsu: No refresh token")
            val response = kitsu.checkTokenAccepted(chain.proceed(KitsuApi.refreshTokenRequest(refreshToken)))
            if (response.isSuccessful) {
                newAuth(
                    runCatching { json.decodeFromString<KitsuOAuth>(response.body.string()) }
                        .getOrElse { throw IOException("Kitsu: Failed to refresh account token", it) },
                )
                // ZOUKAN <--
            } else {
                response.close()
            }
        }

        // Add the authorization header to the original request.
        val authRequest = originalRequest.newBuilder()
            .addHeader("Authorization", "Bearer ${oauth!!.accessToken}")
            .header("User-Agent", "Komikku v${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})")
            .header("Accept", "application/vnd.api+json")
            .header("Content-Type", "application/vnd.api+json")
            .build()

        // ZOUKAN: a 401 marks the login expired
        return kitsu.checkTokenAccepted(chain.proceed(authRequest))
    }

    fun newAuth(oauth: KitsuOAuth?) {
        this.oauth = oauth
        kitsu.saveToken(oauth)
        // ZOUKAN: a new token, from signing in or a refresh, is no longer expired
        if (oauth != null) kitsu.clearLoginExpired()
    }
}
