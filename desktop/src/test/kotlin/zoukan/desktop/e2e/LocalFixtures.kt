package zoukan.desktop.e2e

import eu.kanade.tachiyomi.source.CatalogueSource
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import mihon.domain.manga.model.toDomainManga
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.download.CbzWriter
import zoukan.desktop.download.ComicInfoXml
import zoukan.desktop.library.LibraryUpdater
import zoukan.desktop.local.LocalSource
import zoukan.desktop.storage.ZoukanStorage
import zoukan.desktop.testing.Await
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * Files for the local source: CBZ chapters whose pages are solid colours, so a journey can check a
 * local page drew with `pageDrew`, as it does for the fixture source's pages.
 */
object LocalFixtures {

    private const val PAGE_WIDTH = 600
    private const val PAGE_HEIGHT = 900

    /** Writes a CBZ at [file] with one PNG per page of [pages], filled with the page's colour. */
    fun cbz(file: File, pages: List<FixturePage>) {
        CbzWriter(file).use { writer ->
            pages.forEachIndexed { index, page ->
                val image = BufferedImage(PAGE_WIDTH, PAGE_HEIGHT, BufferedImage.TYPE_INT_RGB)
                val g = image.createGraphics()
                g.color = java.awt.Color(page.red, page.green, page.blue)
                g.fillRect(0, 0, PAGE_WIDTH, PAGE_HEIGHT)
                g.dispose()
                writer.write("%03d.png".format(index + 1), ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray())
            }
        }
    }

    /**
     * [count] pages with a colour each, the same for the same [label] and (all but surely)
     * different for another label. The colours of one chapter differ from each other by more than
     * the check's tolerance in at least one channel, so `pageDrew` tells the pages apart.
     */
    fun pages(label: String, count: Int): List<FixturePage> {
        val random = Random(label.hashCode().toLong())
        val pages = mutableListOf<FixturePage>()
        while (pages.size < count) {
            // Channels stay between 40 and 215: far from the white and black of the reader's background
            val page = FixturePage(
                file = "%03d.png".format(pages.size + 1),
                red = 40 + random.nextInt(176),
                green = 40 + random.nextInt(176),
                blue = 40 + random.nextInt(176),
            )
            val distinct = pages.none {
                maxOf(abs(it.red - page.red), abs(it.green - page.green), abs(it.blue - page.blue)) <= MIN_DISTANCE
            }
            if (distinct) pages += page
        }
        return pages
    }

    /** More than the tolerance of Journey's colour check (24). */
    private const val MIN_DISTANCE = 48

    /**
     * Makes `<local folder>/<title>/Chapter N.cbz` for N in 1..[chapters], each with
     * [pagesPerChapter] pages ([pages] of "<title> N"), and a ComicInfo.xml when [comicInfo] is
     * given (its XML text, as [ComicInfoXml.write] makes it). Returns the entry's folder.
     */
    fun localEntry(
        storage: ZoukanStorage,
        title: String,
        chapters: Int,
        pagesPerChapter: Int = 4,
        comicInfo: String? = null,
    ): File {
        val dir = File(storage.local, title).apply { mkdirs() }
        if (comicInfo != null) File(dir, ComicInfoXml.FILE).writeText(comicInfo)
        (1..chapters).forEach { addChapter(dir, title, it, pagesPerChapter) }
        return dir
    }

    /** Writes `Chapter [number].cbz` in [dir], with the pages [pages] gives for "[title] [number]". */
    fun addChapter(dir: File, title: String, number: Int, pagesPerChapter: Int = 4): List<FixturePage> =
        pages("$title $number", pagesPerChapter).also { cbz(File(dir, "Chapter $number.cbz"), it) }

    /**
     * The library most library, reader and settings journeys start from: Alpha (3 chapters),
     * Bravo (2) and Charlie (4), 4 pages each, all favourites and updated once. Needs a started
     * [Journey]; returns the 3 entries.
     */
    fun seedParityLibrary(storage: ZoukanStorage): List<Manga> {
        listOf("Alpha" to 3, "Bravo" to 2, "Charlie" to 4).forEach { (title, chapters) ->
            localEntry(storage, title, chapters, pagesPerChapter = 4)
        }
        Injekt.get<LibraryPreferences>().autoUpdateMangaRestrictions().set(emptySet())
        return addLocalToLibrary().also { it.size shouldBe 3 }
    }

    /** Every chapter of [favorites], for counts that used to be literals. */
    fun chapterCount(favorites: List<Manga>): Int = runBlocking {
        favorites.sumOf { Injekt.get<GetChaptersByMangaId>().await(it.id).size }
    }

    /**
     * Adds every title the local source lists to the library (the popular list, saved as entries
     * and made favourites), then updates the library and waits for the result, which must have no
     * failures. Returns the entries.
     */
    fun addLocalToLibrary(): List<Manga> {
        val added = runBlocking {
            val source = Injekt.get<SourceManager>().get(LocalSource.ID) as CatalogueSource
            val found = source.getPopularManga(1).mangas.map { it.toDomainManga(LocalSource.ID) }
            Injekt.get<NetworkToLocalManga>()(found).onEach { manga ->
                Injekt.get<MangaRepository>().update(MangaUpdate(id = manga.id, favorite = true, dateAdded = System.currentTimeMillis()))
            }
        }
        val updater = Injekt.get<LibraryUpdater>()
        check(updater.start()) { "The library update did not start (another update, a sync or a restore is running)" }
        // A failed run logs and never sets lastResult, so the wait is bounded
        Await.until("the library update's result; see the log", 60_000) {
            updater.progress.value == null && updater.lastResult.value != null
        }
        updater.lastResult.value!!.failures.shouldBeEmpty()
        return added
    }
}
