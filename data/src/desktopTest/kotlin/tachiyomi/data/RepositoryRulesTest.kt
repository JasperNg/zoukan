package tachiyomi.data

import eu.kanade.tachiyomi.source.model.UpdateStrategy
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import tachiyomi.data.category.CategoryRepositoryImpl
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.model.CategoryUpdate
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import java.io.File

/**
 * The rules the manga, chapter and category repositories apply when writing, on a real database:
 * what a browse result may overwrite, what a partial update leaves alone, and that a failed batch
 * writes nothing.
 */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class RepositoryRulesTest {

    @TempDir
    lateinit var temp: File

    private lateinit var database: TestDatabase
    private lateinit var mangas: MangaRepositoryImpl
    private lateinit var chapters: ChapterRepositoryImpl
    private lateinit var categories: CategoryRepositoryImpl

    @BeforeEach
    fun open() {
        registerNoCustomMangaInfo()
        database = TestDatabase(temp)
        mangas = MangaRepositoryImpl(database.handler)
        chapters = ChapterRepositoryImpl(database.handler)
        categories = CategoryRepositoryImpl(database.handler)
    }

    @AfterEach
    fun close() {
        database.close()
    }

    private fun networkManga(
        title: String = "Title",
        cover: String? = "https://example.org/cover.jpg",
        initialized: Boolean = true,
        description: String? = "Description",
        genre: List<String>? = listOf("Action", "Drama"),
    ) = Manga.create().copy(
        source = 42,
        url = "/manga/1",
        ogTitle = title,
        ogThumbnailUrl = cover,
        ogDescription = description,
        ogGenre = genre,
        ogStatus = 1,
        initialized = initialized,
        updateStrategy = UpdateStrategy.ALWAYS_UPDATE,
    )

    private suspend fun insert(manga: Manga, updateInfo: Boolean = true) =
        mangas.insertNetworkManga(listOf(manga), updateInfo).single()

    @Test
    fun `a browse result inserts a new entry once and returns the stored row`() = runBlocking<Unit> {
        val first = insert(networkManga())
        val again = insert(networkManga())
        again.id shouldBe first.id
        first.ogGenre shouldBe listOf("Action", "Drama")
        mangas.getAll().size shouldBe 1
    }

    @Test
    fun `a browse result refreshes an entry that is not in the library`() = runBlocking<Unit> {
        val stored = insert(networkManga())
        insert(networkManga(title = "New title", description = "New description"))
        val read = mangas.getMangaById(stored.id)
        read.ogTitle shouldBe "New title"
        read.ogDescription shouldBe "New description"
    }

    @Test
    fun `a browse result never overwrites an entry in the library`() = runBlocking<Unit> {
        val stored = insert(networkManga())
        mangas.update(MangaUpdate(id = stored.id, favorite = true)) shouldBe true
        insert(networkManga(title = "Other title", cover = "https://example.org/other.jpg", description = "Other"))
        val read = mangas.getMangaById(stored.id)
        read.ogTitle shouldBe "Title"
        read.ogThumbnailUrl shouldBe "https://example.org/cover.jpg"
        read.ogDescription shouldBe "Description"
        read.favorite shouldBe true
    }

    @Test
    fun `a blank title, a missing cover or uninitialized details do not wipe what is stored`() = runBlocking<Unit> {
        val stored = insert(networkManga())
        insert(networkManga(title = " ", cover = null, initialized = false, description = null, genre = null))
        val read = mangas.getMangaById(stored.id)
        read.ogTitle shouldBe "Title"
        read.ogThumbnailUrl shouldBe "https://example.org/cover.jpg"
        read.ogDescription shouldBe "Description"
        read.ogGenre shouldBe listOf("Action", "Drama")
    }

    @Test
    fun `without updateInfo an existing entry is returned as it is`() = runBlocking<Unit> {
        val stored = insert(networkManga())
        insert(networkManga(title = "Ignored"), updateInfo = false).ogTitle shouldBe "Title"
        mangas.getMangaById(stored.id).ogTitle shouldBe "Title"
    }

    @Test
    fun `a partial update changes only the fields it names and keeps the memo and notes`() = runBlocking<Unit> {
        val memo = buildJsonObject { put("mihon.key", "value") }
        val stored = insert(networkManga().copy(memo = memo))
        mangas.update(MangaUpdate(id = stored.id, notes = "My notes")) shouldBe true
        mangas.update(MangaUpdate(id = stored.id, genre = emptyList(), viewerFlags = 3)) shouldBe true
        val read = mangas.getMangaById(stored.id)
        read.ogTitle shouldBe "Title"
        read.ogDescription shouldBe "Description"
        read.notes shouldBe "My notes"
        read.memo shouldBe memo
        read.viewerFlags shouldBe 3
        read.ogGenre shouldBe emptyList()
    }

    @Test
    fun `library entries list their categories, and an entry in none is in the default one`() = runBlocking<Unit> {
        val stored = insert(networkManga())
        mangas.update(MangaUpdate(id = stored.id, favorite = true))
        mangas.getLibraryManga().single().categories shouldBe listOf(0L)

        val a = categories.insert(Category(id = 0, name = "A", order = 1, flags = 0, hidden = false))
        val b = categories.insert(Category(id = 0, name = "B", order = 2, flags = 0, hidden = false))
        mangas.setMangaCategories(stored.id, listOf(a, b))
        mangas.getLibraryManga().single().categories.sorted() shouldBe listOf(a, b).sorted()

        // Setting the categories replaces them; none puts the entry back in the default one
        mangas.setMangaCategories(stored.id, listOf(b))
        mangas.getLibraryManga().single().categories shouldBe listOf(b)
        mangas.setMangaCategories(stored.id, emptyList())
        mangas.getLibraryManga().single().categories shouldBe listOf(0L)
    }

    @Test
    fun `a category's partial update keeps the fields it leaves out`() = runBlocking<Unit> {
        val id = categories.insert(Category(id = 0, name = "Reading", order = 4, flags = 8, hidden = true))
        categories.updatePartial(CategoryUpdate(id = id, name = "Renamed"))
        categories.get(id) shouldBe Category(id = id, name = "Renamed", order = 4, flags = 8, hidden = true)
        categories.updatePartial(listOf(CategoryUpdate(id = id, hidden = false), CategoryUpdate(id = id, order = 1)))
        categories.get(id) shouldBe Category(id = id, name = "Renamed", order = 1, flags = 8, hidden = false)
    }

    @Test
    fun `added chapters get their ids and a partial update keeps the read progress`() = runBlocking<Unit> {
        val manga = insert(networkManga())
        val added = chapters.addAll(
            listOf(
                Chapter.create().copy(mangaId = manga.id, url = "/c/1", name = "Chapter 1", chapterNumber = 1.0),
                Chapter.create().copy(mangaId = manga.id, url = "/c/2", name = "Chapter 2", chapterNumber = 2.0),
            ),
        )
        added.map { it.id }.distinct().size shouldBe 2
        added.all { it.id > 0 } shouldBe true

        val first = added.first()
        chapters.update(ChapterUpdate(id = first.id, read = true, lastPageRead = 7))
        chapters.update(ChapterUpdate(id = first.id, name = "Chapter 1 (renamed)"))
        val read = chapters.getChapterById(first.id)!!
        read.read shouldBe true
        read.lastPageRead shouldBe 7
        read.name shouldBe "Chapter 1 (renamed)"
        read.url shouldBe "/c/1"
    }

    @Test
    fun `a chapter batch with one bad chapter writes none of them and returns an empty list`() = runBlocking<Unit> {
        val manga = insert(networkManga())
        val result = chapters.addAll(
            listOf(
                Chapter.create().copy(mangaId = manga.id, url = "/c/1", name = "Good"),
                // No such manga: the foreign key refuses it
                Chapter.create().copy(mangaId = manga.id + 1000, url = "/c/2", name = "Bad"),
            ),
        )
        result shouldBe emptyList()
        chapters.getChapterByMangaId(manga.id, applyFilter = false) shouldBe emptyList()
    }

    @Test
    fun `deleting an entry deletes its chapters with it`() = runBlocking<Unit> {
        val manga = insert(networkManga())
        val other = insert(networkManga().copy(url = "/manga/2"))
        chapters.addAll(listOf(Chapter.create().copy(mangaId = manga.id, url = "/c/1", name = "One")))
        chapters.addAll(listOf(Chapter.create().copy(mangaId = other.id, url = "/c/1", name = "Kept")))
        mangas.deleteManga(manga.id)
        chapters.getChapterByUrl("/c/1").map { it.name } shouldBe listOf("Kept")
    }
}
