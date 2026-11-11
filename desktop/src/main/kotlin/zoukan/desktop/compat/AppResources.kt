// ZOUKAN -->
package eu.kanade.tachiyomi

/**
 * The Android app's generated R and BuildConfig, as far as the shared app code reads them. Drawable
 * ids only need to be distinct: the desktop UI shows tracker names, not logos.
 */
@Suppress("ClassName", "ktlint:standard:class-naming", "ktlint:standard:property-naming")
object R {
    object drawable {
        const val brand_anilist = 1
        const val brand_bangumi = 2
        const val brand_kavita = 3
        const val brand_kitsu = 4
        const val brand_komga = 5
        const val brand_mangadex = 6
        const val brand_mangaupdates = 7
        const val brand_myanimelist = 8
        const val brand_shikimori = 9
        const val brand_suwayomi = 10
    }
}

object BuildConfig {
    const val APPLICATION_ID = "app.komikku"
    const val DEBUG = false

    /** Trackers send it in their user agent */
    val VERSION_NAME: String get() = AppInfo.getVersionName()
}
// ZOUKAN <--
