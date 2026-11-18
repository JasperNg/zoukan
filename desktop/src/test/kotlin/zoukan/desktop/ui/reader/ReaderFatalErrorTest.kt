package zoukan.desktop.ui.reader

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.testing.Await
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * A fatal error while the reader loads goes on up to the crash report, but only after the reader
 * has left its loading state: a chapter or page left loading spins for good, and "Retry" passes
 * over a page that is still loading.
 */
class ReaderFatalErrorTest {

    private val main = Executors.newSingleThreadExecutor { Thread(it, "test-main") }.asCoroutineDispatcher()
    private val app = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
    private val uncaught = CopyOnWriteArrayList<Throwable>()
    private val models = mutableListOf<ReaderScreenModel>()

    init {
        Dispatchers.setMain(main)
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
    }

    @AfterEach
    fun tearDown() {
        models.forEach { it.onDispose() }
        app.cancel()
        Dispatchers.resetMain()
        main.close()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    /** The in-memory store has no string sets */
    private class SetCapableStore(base: PreferenceStore = InMemoryPreferenceStore()) : PreferenceStore by base {
        override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> =
            InMemoryPreferenceStore.InMemoryPreference(key, null, defaultValue)
    }

    private val manga = Manga.create().copy(id = 7, source = 4242, initialized = true)
    private val chapter = Chapter.create().copy(id = 70, mangaId = 7, name = "Chapter 1", url = "/c/1")

    private fun newModel(getManga: GetManga, source: eu.kanade.tachiyomi.source.Source): ReaderScreenModel {
        val store = SetCapableStore()
        return ReaderScreenModel(
            mangaId = manga.id,
            initialChapterId = chapter.id,
            getManga = getManga,
            getChapters = mockk { coEvery { await(manga.id) } returns listOf(chapter) },
            sourceManager = mockk { every { get(any<Long>()) } returns source },
            updateChapter = mockk(relaxed = true),
            upsertHistory = mockk(relaxed = true),
            mangaRepository = mockk(relaxed = true),
            downloadManager = mockk<DownloadManager>(relaxed = true) {
                every { findChapter(any(), any()) } returns null
                every { isDownloaded(any(), any()) } returns false
            },
            cbzPassword = mockk(relaxed = true),
            downloadPreferences = DownloadPreferences(store),
            libraryPreferences = LibraryPreferences(store),
            preferences = DesktopPreferences(store),
            incognitoPolicy = lazyOf(mockk { every { isOn(any()) } returns true }),
            syncManager = lazyOf(mockk(relaxed = true)),
            discordPresence = lazyOf(mockk(relaxed = true)),
            trackChapters = lazyOf(mockk(relaxed = true)),
            appScope = app,
        ).also { models += it }
    }

    @Test
    fun `running out of memory while a chapter loads shows an error and still reaches the crash report`() {
        val getManga = mockk<GetManga> { coEvery { await(any<Long>()) } returns manga }
        val source = mockk<HttpSource>(relaxed = true) { coEvery { getPageList(any()) } throws OutOfMemoryError("test") }
        val model = newModel(getManga, source)

        Await.until("the chapter's error", 10_000) { model.state.value.error != null && uncaught.isNotEmpty() }

        model.state.value.loading shouldBe false
        uncaught.single().shouldBeInstanceOf<OutOfMemoryError>()
    }

    @Test
    fun `running out of memory while a page loads leaves the page an error, not loading`() {
        val getManga = mockk<GetManga> { coEvery { await(any<Long>()) } returns manga }
        val source = mockk<HttpSource>(relaxed = true) {
            coEvery { getPageList(any()) } returns listOf(Page(0, imageUrl = "https://example.org/1.png"))
            coEvery { getImage(any()) } throws OutOfMemoryError("test")
        }
        val model = newModel(getManga, source)

        Await.until("the page's error", 10_000) {
            model.state.value.pages.firstOrNull()?.state?.value is ReaderPage.State.Error && uncaught.isNotEmpty()
        }

        model.state.value.pages.single().state.value.shouldBeInstanceOf<ReaderPage.State.Error>()
        model.state.value.pages.single().state.value shouldNotBe ReaderPage.State.Loading
        uncaught.map { it::class } shouldContain OutOfMemoryError::class
    }
}
