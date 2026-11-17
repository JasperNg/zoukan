package eu.kanade.tachiyomi.data.track.shikimori

import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.track.checkTokenAccepted
import eu.kanade.tachiyomi.data.track.clearLoginExpired
import eu.kanade.tachiyomi.data.track.shikimori.dto.SMOAuth
import eu.kanade.tachiyomi.data.track.shikimori.dto.isExpired
import eu.kanade.tachiyomi.data.track.throwIfLoginExpired
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.io.IOException

class ShikimoriInterceptor(private val shikimori: Shikimori) : Interceptor {

    private val json: Json by injectLazy()

    /**
     * OAuth object used for authenticated requests.
     */
    private var oauth: SMOAuth? = shikimori.restoreToken()

    override fun intercept(chain: Interceptor.Chain): Response {
        // ZOUKAN: a refused token marks the login expired, as MAL does (TrackerAuthExpired.kt)
        shikimori.throwIfLoginExpired()
        val originalRequest = chain.request()

        // ZOUKAN -->
        // IOExceptions, which OkHttp hands to the caller; a plain Exception or `!!` crashed its thread
        val currAuth = oauth ?: throw IOException("Not authenticated with Shikimori")

        // Refresh access token if expired.
        if (currAuth.isExpired()) {
            val refreshToken = currAuth.refreshToken ?: throw IOException("Shikimori: No refresh token")
            val response = shikimori.checkTokenAccepted(chain.proceed(ShikimoriApi.refreshTokenRequest(refreshToken)))
            if (response.isSuccessful) {
                newAuth(
                    runCatching { json.decodeFromString<SMOAuth>(response.body.string()) }
                        .getOrElse { throw IOException("Shikimori: Failed to refresh account token", it) },
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
            .build()

        // ZOUKAN: a 401 marks the login expired
        return shikimori.checkTokenAccepted(chain.proceed(authRequest))
    }

    fun newAuth(oauth: SMOAuth?) {
        this.oauth = oauth
        shikimori.saveToken(oauth)
        // ZOUKAN: a new token, from signing in or a refresh, is no longer expired
        if (oauth != null) shikimori.clearLoginExpired()
    }
}
