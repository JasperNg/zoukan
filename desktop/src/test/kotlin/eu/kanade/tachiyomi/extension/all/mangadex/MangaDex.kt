package eu.kanade.tachiyomi.extension.all.mangadex

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Headers
import okhttp3.OkHttpClient

/**
 * A stand-in for the MangaDex extension (named like it), for tests that need a MangaDex source:
 * the app's enhanced MangaDex wraps it, as DelegatedSources wraps the real extension, and that is
 * what shows the MangaDex settings.
 *
 * This class itself never reaches the network: its address is unreachable and loading pages
 * fails. The enhanced MangaDex around it does: it calls api.mangadex.org through its own service
 * to search, sign in or sync. A test stays offline only by showing its screens and dialogs and
 * never confirming one of those actions.
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
