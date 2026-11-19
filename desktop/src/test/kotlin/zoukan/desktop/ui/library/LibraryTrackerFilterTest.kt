package zoukan.desktop.ui.library

import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.library.service.LibraryPreferences
import xyz.nulldev.androidcompat.io.sharedprefs.ForTestsOnly
import xyz.nulldev.androidcompat.io.sharedprefs.JavaSharedPreferences
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.testing.Await
import java.util.concurrent.Executors

@OptIn(ForTestsOnly::class, ExperimentalCoroutinesApi::class)
class LibraryTrackerFilterTest {

    private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val models = mutableListOf<LibraryScreenModel>()

    init {
        Dispatchers.setMain(main)
    }

    @AfterEach
    fun tearDown() {
        try {
            models.forEach { it.onDispose() }
            runBlocking {
                withTimeout(5_000) {
                    models.forEach { model ->
                        val job = model.screenModelScope.coroutineContext.job
                        job.cancelChildren()
                        job.children.toList().joinAll()
                    }
                }
            }
            JavaSharedPreferences.awaitPendingSaves()
        } finally {
            Dispatchers.resetMain()
            main.close()
        }
    }

    private fun tracker(id: Long): BaseTracker = mockk(relaxed = true) {
        every { this@mockk.id } returns id
    }

    private fun newModel(loggedIn: MutableStateFlow<List<BaseTracker>>): LibraryScreenModel {
        val suffix = System.nanoTime()
        val libraryPrefs = LibraryPreferences(DesktopPreferenceStore(JavaSharedPreferences("lib_tracker_filter_$suffix")))
        val desktopPrefs = DesktopPreferences(DesktopPreferenceStore(JavaSharedPreferences("desktop_tracker_filter_$suffix")))
        return LibraryScreenModel(
            getLibraryManga = mockk { every { subscribe() } returns flowOf(emptyList()) },
            getCategories = mockk { every { subscribe() } returns flowOf(emptyList()) },
            getTracksPerManga = mockk { every { subscribe() } returns flowOf(emptyMap()) },
            preferences = libraryPrefs,
            downloadManager = mockk { every { changes } returns MutableStateFlow(0) },
            sourceManager = mockk(relaxed = true),
            trackerManager = mockk<TrackerManager>(relaxed = true) {
                every { loggedInTrackers() } answers { loggedIn.value }
                every { loggedInTrackersFlow() } returns loggedIn
            },
            setSortModeForCategory = mockk(),
            setMangaCategories = mockk(),
            getChaptersByMangaId = mockk(),
            getMergedChaptersByMangaId = mockk(),
            updateChapter = mockk(),
            getNextChapters = mockk(),
            getManga = mockk(),
            mangaRepository = mockk(),
            setCustomMangaInfo = mockk(),
            sourcePreferences = mockk(relaxed = true),
            librarySearch = mockk(relaxed = true),
            extensionManager = mockk {
                every { installedExtensionsFlow } returns MutableStateFlow(emptyMap())
                every { availableExtensionsFlow } returns MutableStateFlow(emptyMap())
            },
            chaptersByEntry = mockk(relaxed = true),
            desktopPreferences = lazyOf(desktopPrefs),
            libraryUpdater = lazyOf(mockk(relaxed = true)),
        ).also { models += it }
    }

    @Test
    fun `logging in a tracker after init makes its filter preference update state`() {
        val loggedIn = MutableStateFlow<List<BaseTracker>>(emptyList())
        val model = newModel(loggedIn)

        Await.until("initial library load", 5_000, { model.state.value }) { !model.state.value.loading }
        model.state.value.settings.tracking shouldBe emptyMap()

        val tracker = tracker(TrackerManager.ANILIST)
        loggedIn.value = listOf(tracker)
        Await.until("tracker appears in settings", 5_000, { model.state.value.settings.tracking }) {
            tracker.id in model.state.value.settings.tracking
        }

        model.preferences.filterTracking(tracker.id.toInt()).set(TriState.ENABLED_IS)
        Await.until("tracker filter updates", 5_000, { model.state.value.settings.tracking }) {
            model.state.value.settings.tracking[tracker.id] == TriState.ENABLED_IS
        }
        model.state.value.settings.tracking shouldBe mapOf(tracker.id to TriState.ENABLED_IS)
    }

    @Test
    fun `logging out a tracker removes its entry from settings tracking`() {
        val anilist = tracker(TrackerManager.ANILIST)
        val kitsu = tracker(TrackerManager.KITSU)
        val loggedIn = MutableStateFlow(listOf(anilist, kitsu))
        val model = newModel(loggedIn)

        model.preferences.filterTracking(anilist.id.toInt()).set(TriState.ENABLED_IS)
        model.preferences.filterTracking(kitsu.id.toInt()).set(TriState.ENABLED_NOT)

        Await.until("both trackers loaded", 5_000, { model.state.value.settings.tracking }) {
            model.state.value.settings.tracking == mapOf(anilist.id to TriState.ENABLED_IS, kitsu.id to TriState.ENABLED_NOT)
        }

        loggedIn.value = listOf(kitsu)
        Await.until("logged-out tracker removed", 5_000, { model.state.value.settings.tracking }) {
            anilist.id !in model.state.value.settings.tracking
        }
        model.state.value.settings.tracking shouldBe mapOf(kitsu.id to TriState.ENABLED_NOT)

        loggedIn.value = emptyList()
        Await.until("all trackers removed", 5_000, { model.state.value.settings.tracking }) {
            model.state.value.settings.tracking.isEmpty()
        }
        model.state.value.settings.tracking shouldBe emptyMap()
    }
}
