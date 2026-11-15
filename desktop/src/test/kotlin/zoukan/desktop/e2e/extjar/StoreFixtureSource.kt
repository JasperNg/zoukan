package zoukan.desktop.e2e.extjar

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.Request
import okhttp3.Response
import java.util.Properties

/**
 * The source of the extension jar that [zoukan.desktop.e2e.FixtureStore] builds, over the
 * FixtureServer catalogue. This class is never used from the test class path: FixtureStore copies
 * the compiled classes of this package into the jar under another package name
 * ([zoukan.desktop.e2e.FixtureStore.JAR_PACKAGE]), so the app's extension class loader, which asks
 * the app's class path first, can only find it in the jar.
 *
 * Its name and the fixture server's address come from [CONFIG], a file in the same jar, read
 * through the extension's class loader as an extension reads its own resources.
 */
class StoreFixtureSource : HttpSource() {

    private val config = Properties().apply {
        val stream = StoreFixtureSource::class.java.getResourceAsStream("/$CONFIG")
            ?: throw IllegalStateException("$CONFIG is not in the extension jar")
        stream.use { load(it) }
    }

    override val name: String = config.getProperty("name")
    override val baseUrl: String = config.getProperty("baseUrl")
    override val lang = "en"
    override val supportsLatest = true

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/catalog", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val entries = Json.parseToJsonElement(response.body.string()).jsonArray.map { it.jsonObject }
            // The bonus title is the in-app fixture source's setting; this source never lists it
            .filterNot { it["bonus"]?.jsonPrimitive?.booleanOrNull == true }
            .map { json ->
                SManga.create().apply {
                    url = "/manga/${json.string("key")}"
                    title = json.string("title")
                    thumbnail_url = json.string("cover")
                }
            }
            .sortedBy { it.title }
        return MangasPage(entries, hasNextPage = false)
    }

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = popularMangaRequest(page)

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

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

    override suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> = emptyList()

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
}

/** The jar file the source reads its name and base URL from. */
const val CONFIG = "zoukan-store-fixture.properties"
