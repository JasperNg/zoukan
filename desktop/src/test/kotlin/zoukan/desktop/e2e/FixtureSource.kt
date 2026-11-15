package zoukan.desktop.e2e

import androidx.preference.ListPreference
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.PreferenceScreen
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.Request
import okhttp3.Response

/**
 * A source over [FixtureServer], written as an extension is: it builds requests and parses the
 * responses through the app's own HTTP client, and has settings the app draws from
 * [setupPreferenceScreen]:
 *
 * - "Show the bonus title" ([SHOW_BONUS], off by default) adds [FixtureCatalog.BONUS_TITLE] to
 *   the catalogue.
 * - "Popular order" ([ORDER]) lists the catalogue A to Z (the default) or Z to A.
 */
class FixtureSource(override val baseUrl: String) :
    HttpSource(),
    ConfigurableSource {

    override val name = NAME
    override val lang = "en"
    override val supportsLatest = true

    private val preferences by lazy { getSourcePreferences() }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = SHOW_BONUS
            title = SHOW_BONUS_TITLE
            summary = "Adds ${FixtureCatalog.BONUS_TITLE} to the catalogue"
            setDefaultValue(false)
        }.also(screen::addPreference)
        ListPreference(screen.context).apply {
            key = ORDER
            title = ORDER_TITLE
            summary = "%s"
            entries = arrayOf(ORDER_A_TO_Z, ORDER_Z_TO_A)
            entryValues = arrayOf("az", "za")
            setDefaultValue("az")
        }.also(screen::addPreference)
    }

    // Popular, latest and search all read the catalogue

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/catalog", headers)

    override fun popularMangaParse(response: Response): MangasPage = catalog(response, query = "")

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        GET("$baseUrl/catalog?q=${java.net.URLEncoder.encode(query, "UTF-8")}", headers)

    override fun searchMangaParse(response: Response): MangasPage =
        catalog(response, query = response.request.url.queryParameter("q").orEmpty())

    private fun catalog(response: Response, query: String): MangasPage {
        val showBonus = preferences.getBoolean(SHOW_BONUS, false)
        val entries = Json.parseToJsonElement(response.body.string()).jsonArray.map { it.jsonObject }
            .filter { showBonus || !it.getValue("bonus").jsonPrimitive.boolean }
            .filter { query.isBlank() || it.string("title").contains(query, ignoreCase = true) }
            .map { json ->
                SManga.create().apply {
                    url = "/manga/${json.string("key")}"
                    title = json.string("title")
                    thumbnail_url = json.string("cover")
                }
            }
            .sortedBy { it.title }
        val ordered = if (preferences.getString(ORDER, "az") == "za") entries.reversed() else entries
        return MangasPage(ordered, hasNextPage = false)
    }

    override fun mangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val json = Json.parseToJsonElement(response.body.string()).jsonObject
        return SManga.create().apply {
            title = json.string("title")
            author = json.string("author")
            description = json.string("description")
            thumbnail_url = json.string("cover")
            status = SManga.ONGOING
            initialized = true
        }
    }

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val json = Json.parseToJsonElement(response.body.string()).jsonObject
        val mangaKey = json.string("key")
        // Newest first, as sources list them
        return json.getValue("chapters").jsonArray.map { it.jsonObject }.map { chapter ->
            SChapter.create().apply {
                url = "/chapter/$mangaKey/${chapter.string("key")}"
                name = chapter.string("name")
                chapter_number = chapter.getValue("number").jsonPrimitive.float
                date_upload = chapter.getValue("date").jsonPrimitive.long
            }
        }.reversed()
    }

    override fun pageListRequest(chapter: SChapter): Request = GET(baseUrl + chapter.url, headers)

    override fun pageListParse(response: Response): List<Page> =
        Json.parseToJsonElement(response.body.string()).jsonArray.mapIndexed { index, url ->
            Page(index, imageUrl = url.jsonPrimitive.content)
        }

    // The fixture titles have no related titles
    override suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> = emptyList()

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content

    companion object {
        const val NAME = "Zoukan Fixtures"
        const val SHOW_BONUS = "show_bonus"
        const val SHOW_BONUS_TITLE = "Show the bonus title"
        const val ORDER = "popular_order"
        const val ORDER_TITLE = "Popular order"
        const val ORDER_A_TO_Z = "A to Z"
        const val ORDER_Z_TO_A = "Z to A"
    }
}
