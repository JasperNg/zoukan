package eu.kanade.tachiyomi.extension.all.mangadex

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Headers
import okhttp3.OkHttpClient

/**
 * A stand-in for the MangaDex extension, for tests that need a MangaDex source offline. The app
 * recognizes the extension by this class name and runs it inside its own enhanced MangaDex (see
 * DelegatedSources), which is what shows the MangaDex settings. Nothing here reaches the network:
 * the address is unreachable and loading pages fails.
 */
class MangaDex : HttpSource() {
    override val name = "MangaDex"
    override val lang = "en"
    override val supportsLatest = true
    override val baseUrl = "http://127.0.0.1:9"
    override val client = OkHttpClient()
    override fun headersBuilder() = Headers.Builder()
    override suspend fun getPageList(chapter: SChapter): List<Page> = error("The MangaDex stand-in has no pages")
}
