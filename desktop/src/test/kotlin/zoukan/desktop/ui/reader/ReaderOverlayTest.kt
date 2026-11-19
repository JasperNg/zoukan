package zoukan.desktop.ui.reader

import eu.kanade.tachiyomi.source.model.Page
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.app.ReadingModes
import zoukan.desktop.download.DownloadManager
import java.util.concurrent.Executors

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderOverlayTest {

    private val main = Executors.newSingleThreadExecutor { Thread(it, "test-main") }.asCoroutineDispatcher()
    private val app = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val models = mutableListOf<ReaderScreenModel>()

    init {
        Dispatchers.setMain(main)
    }

    @AfterEach
    fun tearDown() {
        models.forEach { it.onDispose() }
        app.cancel()
        Dispatchers.resetMain()
        main.close()
    }

    private class SetCapableStore(base: PreferenceStore = InMemoryPreferenceStore()) : PreferenceStore by base {
        override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> =
            InMemoryPreferenceStore.InMemoryPreference(key, null, defaultValue)
    }

    @Test
    fun `ReaderOverlay transitions keep at most one overlay active`() {
        val target = PageActionsTarget(page = 1, extra = 2, leftFirst = true)
        var overlay: ReaderOverlay = ReaderOverlay.Hidden

        overlay = overlay.toggleMenu()
        overlay shouldBe ReaderOverlay.Menu

        // Switching to Settings replaces Menu instead of stacking
        overlay = overlay.toggleSettings()
        overlay shouldBe ReaderOverlay.Settings

        // setMenu(false) does not dismiss an unrelated dialog
        overlay = overlay.setMenu(false)
        overlay shouldBe ReaderOverlay.Settings

        // Switching to Chapters or Help replaces the active overlay
        overlay = overlay.toggleChapters()
        overlay shouldBe ReaderOverlay.Chapters
        overlay = overlay.toggleHelp()
        overlay shouldBe ReaderOverlay.Help

        // Toggling the active overlay dismisses it to Hidden
        overlay = overlay.toggleHelp()
        overlay shouldBe ReaderOverlay.Hidden

        // PageActions is only cleared by clearPageActions or dismiss
        overlay = ReaderOverlay.PageActions(target)
        overlay.toggleChapters() shouldBe ReaderOverlay.Chapters
        overlay.clearPageActions() shouldBe ReaderOverlay.Hidden
        ReaderOverlay.Settings.clearPageActions() shouldBe ReaderOverlay.Settings
        ReaderOverlay.PageActions(target).dismiss() shouldBe ReaderOverlay.Hidden
    }

    @Test
    fun `ReaderScreenModel overlay state handles menu, dialogs, and ready page actions`() = runBlocking<Unit> {
        val store = SetCapableStore()
        val manga = Manga.create().copy(id = 1, source = 4242, initialized = true)
        val chapter = Chapter.create().copy(id = 10, mangaId = 1, name = "Chapter 1", url = "/c/1")
        val source = mockk<eu.kanade.tachiyomi.source.online.HttpSource>(relaxed = true) {
            coEvery { getPageList(any()) } returns listOf(Page(0, imageUrl = "https://example.invalid/0.png"))
        }
        val model = ReaderScreenModel(
            mangaId = manga.id,
            initialChapterId = chapter.id,
            getManga = mockk { coEvery { await(manga.id) } returns manga },
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
            trackChapters = lazyOf(mockk(relaxed = true)),
            appScope = app,
        ).also { models += it }

        withTimeout(5_000) { model.state.first { it.pages.size == 1 && !it.loading } }
        model.overlay.value shouldBe ReaderOverlay.Hidden

        // Page actions cannot open for a page that is not Ready
        model.openPageActions(0)
        model.overlay.value shouldBe ReaderOverlay.Hidden

        model.state.value.pages[0].setState(ReaderPage.State.Ready(PageImage(100, 150, emptyList())))
        model.toggleMenu()
        model.overlay.value shouldBe ReaderOverlay.Menu

        model.openPageActions(0)
        model.overlay.value shouldBe ReaderOverlay.PageActions(PageActionsTarget(0))

        model.openSettings()
        model.overlay.value shouldBe ReaderOverlay.Settings

        model.dismissOverlay()
        model.overlay.value shouldBe ReaderOverlay.Hidden
    }

    @Test
    fun `ReaderConfig helpers route crop and nav invert by reading mode`() {
        val config = ReaderConfig(
            cropPaged = true,
            cropWebtoon = false,
            cropContinuousVertical = true,
            paged = PagedReaderConfig(navInverted = "HORIZONTAL"),
            webtoon = WebtoonReaderConfig(navInverted = "VERTICAL"),
        )
        config.cropEnabled(ReadingModes.RIGHT_TO_LEFT) shouldBe true
        config.cropEnabled(ReadingModes.WEBTOON) shouldBe false
        config.cropEnabled(ReadingModes.CONTINUOUS_VERTICAL) shouldBe true
        config.navInvert(webtoonMode = false) shouldBe "HORIZONTAL"
        config.navInvert(webtoonMode = true) shouldBe "VERTICAL"
        config.cropKey shouldBe Triple(true, false, true)
    }
}
