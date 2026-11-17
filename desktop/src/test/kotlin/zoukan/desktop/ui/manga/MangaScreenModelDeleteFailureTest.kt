package zoukan.desktop.ui.manga

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.model.Manga
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.app.Toasts
import zoukan.desktop.compat.localized
import zoukan.desktop.domain.ChaptersByEntry
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.storage.StorageUnavailableException
import zoukan.i18n.desktop.DMR
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** Deleting downloads with the storage drive unplugged says so, instead of failing without a word. */
class MangaScreenModelDeleteFailureTest {

    private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
    private val uncaught = CopyOnWriteArrayList<Throwable>()
    private val toasts = CopyOnWriteArrayList<String>()
    private val collector = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val app = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val models = mutableListOf<MangaScreenModel>()

    private val manga = Manga.create().copy(id = 99, source = 1, initialized = true)
    private val chapter = Chapter.create().copy(id = 1, mangaId = 99)

    init {
        Dispatchers.setMain(main)
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
        collector.launch(start = CoroutineStart.UNDISPATCHED) { Toasts.flow.collect { toasts += it } }
    }

    @AfterEach
    fun tearDown() {
        models.forEach { it.onDispose() }
        collector.cancel()
        app.cancel()
        Dispatchers.resetMain()
        main.close()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    private fun newModel(): MangaScreenModel {
        val unplugged = StorageUnavailableException(File("/unplugged/Zoukan"))
        val downloads = mockk<DownloadManager>(relaxed = true) {
            every { changes } returns MutableStateFlow(0)
            every { queue } returns MutableStateFlow(emptyList())
            coEvery { delete(any(), any()) } throws unplugged
            coEvery { deleteManga(any(), any()) } throws unplugged
        }
        val downloadedOnly = mockk<tachiyomi.core.common.preference.Preference<Boolean>> { every { changes() } returns flowOf(false) }
        val preferences = mockk<DesktopPreferences> { every { downloadedOnly() } returns downloadedOnly }
        return MangaScreenModel(
            mangaId = 99,
            getMangaWithChapters = mockk {
                coEvery { awaitManga(99L) } returns manga
                coEvery { subscribe(99L) } returns flowOf(manga to listOf(chapter))
            },
            sourceManager = mockk(relaxed = true),
            updateChapter = mockk(relaxed = true),
            refreshManga = mockk(relaxed = true),
            downloadManager = downloads,
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
            libraryPreferences = tachiyomi.domain.library.service.LibraryPreferences(InMemoryPreferenceStore()),
            uiPreferences = eu.kanade.domain.ui.UiPreferences(InMemoryPreferenceStore()),
            chaptersByEntry = mockk<ChaptersByEntry> { coEvery { group(any(), any()) } answers { listOf(firstArg<Manga>() to secondArg<List<Chapter>>()) } },
            desktopPreferences = lazyOf(preferences),
            getChaptersByMangaId = lazyOf(mockk<GetChaptersByMangaId> { coEvery { await(any(), any()) } returns listOf(chapter) }),
            appScope = app,
        ).also { models += it }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < end) Thread.sleep(10)
        Thread.sleep(100)
    }

    private val failed get() = localized(DMR.strings.screen_action_failed_delete_downloads)

    @Test
    fun `Delete downloads with the drive unplugged shows the error`() {
        val model = newModel()
        waitUntil { model.state.value.manga != null }

        model.deleteDownloads(listOf(chapter))
        waitUntil { toasts.any { it.startsWith(failed) } }

        toasts.any { it.startsWith(failed) && "/unplugged/Zoukan" in it } shouldBe true
        uncaught.shouldBeEmpty()
    }

    @Test
    fun `Clear manga with the drive unplugged shows the error`() {
        val model = newModel()
        waitUntil { model.state.value.manga != null }

        model.clearManga(deleteDownload = true, removeChapters = false)
        waitUntil { toasts.any { it.startsWith(failed) } }

        toasts.any { it.startsWith(failed) } shouldBe true
        uncaught.shouldBeEmpty()
    }
}
