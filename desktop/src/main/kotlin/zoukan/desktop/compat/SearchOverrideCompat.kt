// ZOUKAN -->
// The Android app's SearchOverride: a search for an address opens that entry. Android adds it
// through GalleryAdder; desktop maps the address to the entry and fetches its details, and the
// browse screen adds it to the database as for any search result.
@file:JvmName("SearchOverride")

package exh.util

import android.content.Context
import android.net.Uri
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.UrlImportableSource
import rx.Observable
import tachiyomi.core.common.util.lang.runAsObservable

fun UrlImportableSource.urlImportFetchSearchManga(
    context: Context,
    query: String,
    fail: () -> Observable<MangasPage>,
): Observable<MangasPage> = if (isAddress(query)) runAsObservable { importAddress(query) } else fail()

suspend fun UrlImportableSource.urlImportFetchSearchMangaSuspend(
    context: Context,
    query: String,
    fail: suspend () -> MangasPage,
): MangasPage = if (isAddress(query)) importAddress(query) else fail()

private fun isAddress(query: String) = query.startsWith("http://") || query.startsWith("https://")

private suspend fun UrlImportableSource.importAddress(address: String): MangasPage {
    val uri = Uri.parse(address)
    if (!runCatching { matchesUri(uri) }.getOrDefault(false)) return MangasPage(emptyList(), false)
    val mangaUrl = runCatching { mapUrlToMangaUrl(uri) }.getOrNull() ?: return MangasPage(emptyList(), false)
    val manga = SManga.create().apply { url = cleanMangaUrl(mangaUrl) }
    val details = getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false).manga
    details.url = manga.url
    return MangasPage(listOf(details), false)
}
// ZOUKAN <--
