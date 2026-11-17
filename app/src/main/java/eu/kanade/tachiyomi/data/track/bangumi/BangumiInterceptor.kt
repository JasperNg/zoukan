package eu.kanade.tachiyomi.data.track.bangumi

import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.track.bangumi.dto.BGMOAuth
import eu.kanade.tachiyomi.data.track.bangumi.dto.isExpired
import eu.kanade.tachiyomi.data.track.checkTokenAccepted
import eu.kanade.tachiyomi.data.track.clearLoginExpired
import eu.kanade.tachiyomi.data.track.throwIfLoginExpired
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.io.IOException

class BangumiInterceptor(private val bangumi: Bangumi) : Interceptor {

    private val json: Json by injectLazy()

    /**
     * OAuth object used for authenticated requests.
     */
    private var oauth: BGMOAuth? = bangumi.restoreToken()

    override fun intercept(chain: Interceptor.Chain): Response {
        // ZOUKAN: a refused token marks the login expired, as MAL does (TrackerAuthExpired.kt)
        bangumi.throwIfLoginExpired()
        val originalRequest = chain.request()

        // ZOUKAN -->
        // IOExceptions, which OkHttp hands to the caller; a plain Exception or `!!` crashed its thread
        var currAuth: BGMOAuth = oauth ?: throw IOException("Not authenticated with Bangumi")

        if (currAuth.isExpired()) {
            val refreshToken = currAuth.refreshToken ?: throw IOException("Bangumi: No refresh token")
            val response = bangumi.checkTokenAccepted(chain.proceed(BangumiApi.refreshTokenRequest(refreshToken)))
            if (response.isSuccessful) {
                currAuth = runCatching { json.decodeFromString<BGMOAuth>(response.body.string()) }
                    .getOrElse { throw IOException("Bangumi: Failed to refresh account token", it) }
                // ZOUKAN <--
                newAuth(currAuth)
            } else {
                response.close()
            }
        }

        return originalRequest.newBuilder()
            .header(
                "User-Agent",
                "Komikku v${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})",
            )
            .apply {
                addHeader("Authorization", "Bearer ${currAuth.accessToken}")
            }
            .build()
            .let(chain::proceed)
            // ZOUKAN: a 401 marks the login expired
            .let(bangumi::checkTokenAccepted)
    }

    fun newAuth(oauth: BGMOAuth?) {
        this.oauth = if (oauth == null) {
            null
        } else {
            BGMOAuth(
                oauth.accessToken,
                oauth.tokenType,
                System.currentTimeMillis() / 1000,
                oauth.expiresIn,
                oauth.refreshToken,
                this.oauth?.userId,
            )
        }

        bangumi.saveToken(oauth)
        // ZOUKAN: a new token, from signing in or a refresh, is no longer expired
        if (oauth != null) bangumi.clearLoginExpired()
    }
}
