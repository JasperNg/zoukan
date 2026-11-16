package zoukan.desktop.e2e

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.text.Html
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.newCachelessCallWithProgress
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.PagePreviewInfo
import eu.kanade.tachiyomi.source.PagePreviewPage
import eu.kanade.tachiyomi.source.PagePreviewSource
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
import okhttp3.CacheControl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * A source over [FixtureServer], written as an extension is: it builds requests and parses the
 * responses through the app's own HTTP client, and has settings the app draws from
 * [setupPreferenceScreen]:
 *
 * - "Show the bonus title" ([SHOW_BONUS], off by default) adds [FixtureCatalog.BONUS_TITLE] to
 *   the catalogue.
 * - "Popular order" ([ORDER]) lists the catalogue A to Z (the default) or Z to A.
 * - "Hidden genres" ([HIDDEN_GENRES], a multi-select, none by default) leaves out the titles of
 *   those genres.
 * - "Title prefix" ([TITLE_PREFIX], an edit text, empty by default) goes before every title in the
 *   catalogue.
 * - "Experimental mode" ([EXPERIMENTAL]) has a change listener that throws, as a buggy
 *   extension's does.
 * - "Image quality" ([QUALITY]) has a change listener that reads the stored value and shows an
 *   Android toast; picking "Auto" stores "High" through setValueIndex instead.
 *
 * It also does what extensions do outside the settings screen: it records the keys its
 * OnSharedPreferenceChangeListener hears ([changedKeys]), runs descriptions through
 * Html.fromHtml, and descrambles the pages of [FixtureCatalog.SCRAMBLED_TITLE] in an OkHttp
 * interceptor with android.graphics ([descrambled] counts them). Search lists the search-only
 * titles too.
 *
 * With [previews] it serves page previews for the entry screen, and with [related] related
 * titles. Both are off by default, so a journey that does not ask for them sees no extra requests.
 */
class FixtureSource(
    override val baseUrl: String,
    /**
     * Another name is another source: the id comes from the name. A second instance over the same
     * server ([SECOND_NAME]) is a merge or migration target that fetches real chapters.
     */
    override val name: String = NAME,
    /** Page previews on the entry screen ([PagePreviewSource]): the first chapter's pages. Off by default. */
    val previews: Boolean = false,
    /** Two other catalogue titles as the related titles, and no related titles by search. Off by default. */
    val related: Boolean = false,
) : HttpSource(),
    ConfigurableSource,
    PagePreviewSource {

    override val lang = "en"
    override val supportsLatest = true

    /** The keys the source's settings listener heard change, in order. */
    val changedKeys: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** How many scrambled pages the interceptor put back together. */
    val descrambled = AtomicInteger()

    // Held by the source: SharedPreferences keeps its listeners weakly, as Android does
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null) changedKeys += key
    }

    private val preferences by lazy {
        getSourcePreferences().also { it.registerOnSharedPreferenceChangeListener(preferenceListener) }
    }

    // As descrambling extensions do: the app's client with an interceptor that puts the pages back
    // together. Lazy, because the source is made before the app's network is up.
    override val client: OkHttpClient by lazy {
        network.client.newBuilder().addInterceptor(::descramble).build()
    }

    private fun descramble(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (!response.request.url.pathSegments.last().startsWith("scrambled-")) return response
        // The extensions' idiom: a body that is no image (a site's HTML page) goes on as it is
        val scrambled = BitmapFactory.decodeStream(response.body.byteStream()) ?: return response
        // The page is squeezed into the right half: stretch that half over the whole image
        val width = scrambled.width
        val height = scrambled.height
        val page = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(page).drawBitmap(scrambled, Rect(width / 2, 0, width, height), Rect(0, 0, width, height), null)
        val bytes = ByteArrayOutputStream().also { page.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        response.body.close()
        descrambled.incrementAndGet()
        return response.newBuilder().body(bytes.toResponseBody("image/png".toMediaType())).build()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val context = screen.context
        SwitchPreferenceCompat(context).apply {
            key = SHOW_BONUS
            title = SHOW_BONUS_TITLE
            summary = "Adds ${FixtureCatalog.BONUS_TITLE} to the catalogue"
            setDefaultValue(false)
        }.also(screen::addPreference)
        ListPreference(context).apply {
            key = ORDER
            title = ORDER_TITLE
            summary = "%s"
            entries = arrayOf(ORDER_A_TO_Z, ORDER_Z_TO_A)
            entryValues = arrayOf("az", "za")
            setDefaultValue("az")
        }.also(screen::addPreference)
        // The settings below come after the first two, which other journeys find first
        MultiSelectListPreference(context).apply {
            key = HIDDEN_GENRES
            title = HIDDEN_GENRES_TITLE
            entries = arrayOf(GENRE_ACTION, GENRE_COMEDY, GENRE_DRAMA)
            entryValues = arrayOf(FixtureCatalog.GENRE_ACTION, FixtureCatalog.GENRE_COMEDY, FixtureCatalog.GENRE_DRAMA)
            setDefaultValue(emptySet<String>())
        }.also(screen::addPreference)
        EditTextPreference(context).apply {
            key = TITLE_PREFIX
            title = TITLE_PREFIX_TITLE
            dialogTitle = TITLE_PREFIX_TITLE
            setDefaultValue("")
        }.also(screen::addPreference)
        SwitchPreferenceCompat(context).apply {
            key = EXPERIMENTAL
            title = EXPERIMENTAL_TITLE
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, _ -> throw IllegalStateException(EXPERIMENTAL_FAILURE) }
        }.also(screen::addPreference)
        ListPreference(context).apply {
            key = QUALITY
            title = QUALITY_TITLE
            summary = "%s"
            entries = arrayOf(QUALITY_AUTO, QUALITY_HIGH, QUALITY_LOW)
            entryValues = arrayOf("auto", "high", "low")
            setDefaultValue("low")
            setOnPreferenceChangeListener { pref, newValue ->
                pref as ListPreference
                // The stored value: the listener runs before the change is saved
                val old = pref.entries[pref.findIndexOfValue(pref.value)]
                if (newValue == "auto") {
                    // Auto means High here: stored without calling this listener again
                    pref.setValueIndex(1)
                    Toast.makeText(context, qualityToast(QUALITY_AUTO + " chose " + QUALITY_HIGH, old), Toast.LENGTH_SHORT).show()
                    false
                } else {
                    val new = pref.entries[pref.findIndexOfValue(newValue as String)]
                    Toast.makeText(context, qualityToast(new, old), Toast.LENGTH_SHORT).show()
                    true
                }
            }
        }.also(screen::addPreference)
        // Registers the settings listener before the first change, as an extension does at start
        preferences
    }

    // Popular, latest and search all read the catalogue

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/catalog", headers)

    override fun popularMangaParse(response: Response): MangasPage = catalog(response, query = "")

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = GET(searchUrl(baseUrl, query), headers)

    override fun searchMangaParse(response: Response): MangasPage =
        catalog(response, query = response.request.url.queryParameter("q").orEmpty())

    private fun catalog(response: Response, query: String): MangasPage = catalogPage(
        body = response.body.string(),
        query = query,
        showBonus = preferences.getBoolean(SHOW_BONUS, false),
        hiddenGenres = preferences.getStringSet(HIDDEN_GENRES, emptySet()).orEmpty(),
        prefix = preferences.getString(TITLE_PREFIX, "").orEmpty(),
        zToA = preferences.getString(ORDER, "az") == "za",
    )

    override fun mangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val json = Json.parseToJsonElement(response.body.string()).jsonObject
        return SManga.create().apply {
            title = json.string("title")
            author = json.string("author")
            // Sites send HTML descriptions; extensions turn them into text this way
            description = Html.fromHtml(json.string("description"), Html.FROM_HTML_MODE_LEGACY).toString().trim()
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

    // Page previews, as E-Hentai serves them: the pages of the title's first chapter (in reading
    // order), all on one preview page. A title that is not in the catalogue (a migrated entry) has none.

    override suspend fun getPagePreviewList(manga: SManga, chapters: List<SChapter>, page: Int): PagePreviewPage {
        val chapter = FixtureCatalog.manga(manga.url.removePrefix("/manga/"))?.chapters?.firstOrNull()
        if (!previews || chapter == null) return PagePreviewPage(page, emptyList(), hasNextPage = false, pagePreviewPages = 0)
        // Numbered from 1, as the app opens the reader at a preview's index - 1
        val infos = FixtureCatalog.pages(chapter.format).mapIndexed { i, p -> PagePreviewInfo(i + 1, "$baseUrl/images/${p.file}") }
        return PagePreviewPage(page, infos, hasNextPage = false, pagePreviewPages = 1)
    }

    // Through the source's client (the descrambling interceptor too), reporting progress to the preview
    override suspend fun fetchPreviewImage(page: PagePreviewInfo, cacheControl: CacheControl?): Response =
        client.newCachelessCallWithProgress(GET(page.imageUrl, headers, cacheControl ?: CacheControl.FORCE_NETWORK), page)
            .awaitSuccess()

    // Related titles from the source only: the app's search for the title's words would list the
    // catalogue as well, and hide whether these came through
    override val disableRelatedMangasBySearch: Boolean get() = related

    /**
     * How many times the app's related-titles lookup ([getRelatedMangaList]: the source's list,
     * and the app's search unless [disableRelatedMangasBySearch]) finished, every result handed on.
     */
    val relatedLookups = AtomicInteger()

    // The app's own lookup, only counted, so a journey can wait until every path has answered
    override suspend fun getRelatedMangaList(
        manga: SManga,
        exceptionHandler: (Throwable) -> Unit,
        pushResults: suspend (relatedManga: Pair<String, List<SManga>>, completed: Boolean) -> Unit,
    ) {
        try {
            super<HttpSource>.getRelatedMangaList(manga, exceptionHandler, pushResults)
        } finally {
            relatedLookups.incrementAndGet()
        }
    }

    override suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> {
        if (!related) return emptyList()
        return FixtureCatalog.manga.filter { !it.searchOnly && !it.bonus && "/manga/${it.key}" != manga.url }
            .take(2)
            .map { other ->
                SManga.create().apply {
                    url = "/manga/${other.key}"
                    title = other.title
                    thumbnail_url = "$baseUrl/images/${other.cover}"
                }
            }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    companion object {
        /** The search request's URL: the query URL-encoded as UTF-8, as extensions build it. */
        fun searchUrl(baseUrl: String, query: String): String = "$baseUrl/catalog?q=${URLEncoder.encode(query, "UTF-8")}"

        /**
         * The titles the source lists from the server's catalogue [body]: for a blank [query] the
         * catalogue, otherwise the titles (search-only ones too) that contain it. The other
         * arguments are the source's settings. Needs no app runtime, so tests call it directly.
         */
        fun catalogPage(
            body: String,
            query: String,
            showBonus: Boolean = false,
            hiddenGenres: Set<String> = emptySet(),
            prefix: String = "",
            zToA: Boolean = false,
        ): MangasPage {
            val entries = Json.parseToJsonElement(body).jsonArray.map { it.jsonObject }
                .filter { showBonus || !it.getValue("bonus").jsonPrimitive.boolean }
                .filter { query.isNotBlank() || !it.getValue("searchOnly").jsonPrimitive.boolean }
                .filter { query.isBlank() || it.string("title").contains(query, ignoreCase = true) }
                .filter { it.string("genre") !in hiddenGenres }
                .map { json ->
                    SManga.create().apply {
                        url = "/manga/${json.string("key")}"
                        title = prefix + json.string("title")
                        thumbnail_url = json.string("cover")
                    }
                }
                .sortedBy { it.title }
            return MangasPage(if (zToA) entries.reversed() else entries, hasNextPage = false)
        }

        private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content

        const val NAME = "Zoukan Fixtures"

        /** The name of a second instance ([Journey.addSource]): another source id over the same server. */
        const val SECOND_NAME = "Zoukan Fixtures Mirror"
        const val SHOW_BONUS = "show_bonus"
        const val SHOW_BONUS_TITLE = "Show the bonus title"
        const val ORDER = "popular_order"
        const val ORDER_TITLE = "Popular order"
        const val ORDER_A_TO_Z = "A to Z"
        const val ORDER_Z_TO_A = "Z to A"
        const val HIDDEN_GENRES = "hidden_genres"
        const val HIDDEN_GENRES_TITLE = "Hidden genres"
        const val GENRE_ACTION = "Action"
        const val GENRE_COMEDY = "Comedy"
        const val GENRE_DRAMA = "Drama"
        const val TITLE_PREFIX = "title_prefix"
        const val TITLE_PREFIX_TITLE = "Title prefix"
        const val EXPERIMENTAL = "experimental"
        const val EXPERIMENTAL_TITLE = "Experimental mode"
        const val EXPERIMENTAL_FAILURE = "The experimental mode is not ready"
        const val QUALITY = "image_quality"
        const val QUALITY_TITLE = "Image quality"
        const val QUALITY_AUTO = "Auto"
        const val QUALITY_HIGH = "High"
        const val QUALITY_LOW = "Low"

        /** The toast "Image quality" shows for a change to [new] from [old]. */
        fun qualityToast(new: CharSequence, old: CharSequence) = "Image quality: $new (was $old)"
    }
}
