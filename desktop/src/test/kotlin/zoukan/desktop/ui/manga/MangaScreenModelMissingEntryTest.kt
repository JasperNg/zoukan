package zoukan.desktop.ui.manga

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.model.Manga
import zoukan.desktop.compat.localized
import zoukan.desktop.app.DesktopPreferences
import zoukan.i18n.desktop.DMR
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** An entry that is gone, or goes while its screen is open, shows an error instead of failing. */
class MangaScreenModelMissingEntryTest {

    // The model's scope runs on Main; a thread of its own stands in for the UI thread
    private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
    private val uncaught = CopyOnWriteArrayList<Throwable>()
    private val models = mutableListOf<MangaScreenModel>()

    init {
        Dispatchers.setMain(main)
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
    }

    @AfterEach
    fun tearDown() {
        models.forEach { it.onDispose() }
        Dispatchers.resetMain()
        main.close()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    private fun newModel(getMangaWithChapters: GetMangaWithChapters): MangaScreenModel {
        val downloadedOnly = mockk<tachiyomi.core.common.preference.Preference<Boolean>> { every { changes() } returns flowOf(false) }
        val preferences = mockk<DesktopPreferences> { every { downloadedOnly() } returns downloadedOnly }
        return MangaScreenModel(
            mangaId = 99,
            getMangaWithChapters = getMangaWithChapters,
            sourceManager = mockk(relaxed = true),
            updateChapter = mockk(relaxed = true),
            refreshManga = mockk(relaxed = true),
            downloadManager = mockk(relaxed = true) {
                every { changes } returns MutableStateFlow(0)
                every { queue } returns MutableStateFlow(emptyList())
            },
            getTracks = mockk { every { subscribe(any<Long>()) } returns flowOf(emptyList()) },
            trackPreferences = mockk(relaxed = true),
            trackerManager = mockk(relaxed = true),
            trackChapters = mockk(relaxed = true),
            getMergedChaptersByMangaId = mockk(relaxed = true),
            getFlatMetadataById = mockk { every { subscribe(any<Long>()) } returns flowOf() },
            addToLibraryFlow = mockk(relaxed = true),
            getCategories = mockk(relaxed = true),
            setMangaCategories = mockk(relaxed = true),
            chapterSettings = mockk(relaxed = true) { every { excludedScanlators(any()) } returns flowOf(emptySet()) },
            filterChaptersForDownload = mockk(relaxed = true),
            updateErrors = mockk(relaxed = true),
            refreshTracks = mockk(relaxed = true),
            chaptersByEntry = mockk(relaxed = true),
            desktopPreferences = lazyOf(preferences),
        ).also { models += it }
    }

    /** Waits for [condition] or for 5 s; a failure on the way to the default handler gets time to arrive. */
    private fun waitUntil(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < end) Thread.sleep(10)
        Thread.sleep(100)
    }

    @Test
    fun `opening an entry that no longer exists shows an error instead of failing`() {
        val interactor = mockk<GetMangaWithChapters> {
            // What executeAsOne throws for a missing row
            coEvery { awaitManga(99L) } throws NullPointerException("ResultSet returned null")
        }
        val model = newModel(interactor)
        waitUntil { model.state.value.error != null }

        model.state.value.error shouldBe localized(DMR.strings.this_entry_no_longer_exists)
        uncaught.shouldBeEmpty()
    }

    @Test
    fun `an entry deleted while its screen is open shows an error`() {
        val manga = Manga.create().copy(id = 99, initialized = true)
        val interactor = mockk<GetMangaWithChapters> {
            coEvery { awaitManga(99L) } returns manga
            coEvery { subscribe(99L) } returns flow {
                emit(manga to listOf(Chapter.create().copy(id = 1, mangaId = 99)))
                throw NullPointerException("ResultSet returned null")
            }
        }
        val model = newModel(interactor)
        waitUntil { model.state.value.error != null }

        model.state.value.error shouldBe localized(DMR.strings.this_entry_no_longer_exists)
        uncaught.shouldBeEmpty()
    }
}
