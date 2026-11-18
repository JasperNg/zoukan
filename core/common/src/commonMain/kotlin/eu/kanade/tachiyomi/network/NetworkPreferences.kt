package eu.kanade.tachiyomi.network

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

class NetworkPreferences(
    private val preferenceStore: PreferenceStore,
    // ZOUKAN -->
    /**
     * The DNS over HTTPS provider used while none is stored: -1 (off) as on Android; the desktop
     * app passes Quad9. Only the default changes, so a stored choice (off included) is kept.
     */
    private val defaultDohProvider: Int = -1,
    // ZOUKAN <--
) {

    /* KMK --> fun verboseLogging(): Preference<Boolean> {
        return preferenceStore.getBoolean("verbose_logging", verboseLogging)
    } KMK <-- */

    fun dohProvider(): Preference<Int> {
        return preferenceStore.getInt("doh_provider", /* ZOUKAN --> */defaultDohProvider/* ZOUKAN <-- */)
    }

    fun defaultUserAgent(): Preference<String> {
        return preferenceStore.getString(
            "default_user_agent",
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36",
        )
    }
}
