package zoukan.desktop.ui.manga

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import eu.kanade.domain.ui.UiPreferences
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.compat.localized
import zoukan.desktop.compat.localizedPlural
import zoukan.desktop.domain.ChaptersByEntry
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.testing.Await
import zoukan.desktop.testing.FreshInjekt
import zoukan.desktop.ui.e2eEffectContext
import zoukan.i18n.desktop.DMR
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Covers chapter multi-selection, read/unread/bookmark/mark-previous-read actions, unread &
 * bookmarked download menu queuing, deleting downloads confirmation dialog, and chapter settings
 * (Display mode, Scanlator filter, and Set as default).
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class MangaChapterActionsTest {

    @JvmField
    @RegisterExtension
    val injekt = FreshInjekt()

    private val main = Executors.newSingleThreadExecutor { Thread(it, "test-main") }.asCoroutineDispatcher()
    private val app = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val models = mutableListOf<MangaScreenModel>()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(main)
    }

    @AfterEach
    fun tearDown() {
        models.forEach { it.onDispose() }
        app.cancel()
        Dispatchers.resetMain()
        main.close()
    }

    private class ReactiveMemoryPreferenceStore : PreferenceStore {
        private val flows = ConcurrentHashMap<String, MutableStateFlow<Any?>>()
        private fun <T> pref(key: String, defaultValue: T): Preference<T> {
            val flow = flows.computeIfAbsent(key) { MutableStateFlow(null) }
            @Suppress("UNCHECKED_CAST")
            return object : Preference<T> {
                override fun key(): String = key
                override fun get(): T = (flow.value as T?) ?: defaultValue
                override fun isSet(): Boolean = flow.value != null
                override fun delete() {
                    flow.value = null
                }
                override fun defaultValue(): T = defaultValue
                override fun changes(): Flow<T> = flow.map { (it as T?) ?: defaultValue }
                override fun stateIn(scope: CoroutineScope): StateFlow<T> = changes().stateIn(scope, SharingStarted.Eagerly, get())
                override fun set(value: T) {
                    flow.value = value
                }
            }
        }
        override fun getString(key: String, defaultValue: String) = pref(key, defaultValue)
        override fun getLong(key: String, defaultValue: Long) = pref(key, defaultValue)
        override fun getInt(key: String, defaultValue: Int) = pref(key, defaultValue)
        override fun getFloat(key: String, defaultValue: Float) = pref(key, defaultValue)
        override fun getBoolean(key: String, defaultValue: Boolean) = pref(key, defaultValue)
        override fun getStringSet(key: String, defaultValue: Set<String>) = pref(key, defaultValue)
        override fun <T> getObjectFromString(key: String, defaultValue: T, serializer: (T) -> String, deserializer: (String) -> T) = pref(key, defaultValue)
        override fun <T> getObjectFromInt(key: String, defaultValue: T, serializer: (T) -> Int, deserializer: (Int) -> T) = pref(key, defaultValue)
        override fun getAll(): Map<String, *> = flows.mapValues { it.value.value }
    }

    private val manga = Manga.create().copy(id = 77, source = 4242L, ogTitle = "Formats", initialized = true)
    private val chapters = (1..12).map { n ->
        Chapter.create().copy(
            id = n.toLong(),
            mangaId = manga.id,
            name = "Chapter $n",
            url = "/c/$n",
            chapterNumber = n.toDouble(),
            sourceOrder = (12 - n).toLong(),
            read = n == 1,
            bookmark = n == 7 || n == 9,
            scanlator = if (n <= 6) "Alpha Scans" else "Beta Scans",
        )
    }

    private fun newModel(
        updateChapter: UpdateChapter = mockk(relaxed = true),
        downloads: DownloadManager = mockk(relaxed = true) {
            every { changes } returns MutableStateFlow(0)
            every { queue } returns MutableStateFlow(emptyList())
        },
        chapterSettings: ChapterSettings = mockk(relaxed = true) {
            every { excludedScanlators(any()) } returns flowOf(emptySet())
        },
    ): MangaScreenModel {
        val store = ReactiveMemoryPreferenceStore()
        Injekt.addSingleton(mockk<tachiyomi.domain.manga.interactor.GetMergedMangaById>(relaxed = true))
        val model = MangaScreenModel(
            mangaId = manga.id,
            getMangaWithChapters = mockk {
                coEvery { awaitManga(manga.id) } returns manga
                coEvery { subscribe(manga.id) } returns flowOf(manga to chapters)
            },
            sourceManager = mockk<SourceManager>(relaxed = true),
            updateChapter = updateChapter,
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
            chapterSettings = chapterSettings,
            filterChaptersForDownload = mockk(relaxed = true),
            updateErrors = mockk(relaxed = true),
            refreshTracks = mockk(relaxed = true),
            libraryPreferences = LibraryPreferences(store),
            uiPreferences = UiPreferences(store),
            chaptersByEntry = mockk<ChaptersByEntry> {
                coEvery { group(any(), any()) } answers { listOf(firstArg<Manga>() to secondArg<List<Chapter>>()) }
            },
            desktopPreferences = lazyOf(DesktopPreferences(store)),
            appScope = app,
        ).also { models += it }
        Await.until("manga loaded", 5_000) { model.state.value.manga != null }
        return model
    }

    @Test
    fun `selection actions mark read, reset progress on unread, bookmark, and mark previous read`() {
        val updates = Collections.synchronizedList(mutableListOf<ChapterUpdate>())
        val updateChapter = mockk<UpdateChapter> {
            coEvery { awaitAll(any()) } answers {
                updates += firstArg<List<ChapterUpdate>>()
                true
            }
        }
        val model = newModel(updateChapter = updateChapter)

        // selectAll selects all filtered chapters; clearSelection empties it
        model.selectAll()
        model.state.value.selected.size shouldBe 12
        model.clearSelection()
        model.state.value.selected shouldBe emptySet()

        // Mark selection [1, 2] as read -> clears selection
        model.toggleSelection(chapters[0])
        model.toggleSelection(chapters[1])
        model.markRead(listOf(chapters[0], chapters[1]), read = true)
        Await.until("selection cleared after markRead", 5_000) {
            model.state.value.selected.isEmpty() && updates.size == 2
        }
        updates.map { it.id to it.read } shouldBe listOf(1L to true, 2L to true)

        // Mark chapter 2 unread -> resets lastPageRead = 0L
        updates.clear()
        model.toggleSelection(chapters[1])
        model.markRead(listOf(chapters[1]), read = false)
        Await.until("unread reset lastPageRead", 5_000) {
            model.state.value.selected.isEmpty() && updates.size == 1
        }
        updates.single().let {
            it.id shouldBe 2L
            it.read shouldBe false
            it.lastPageRead shouldBe 0L
        }

        // Bookmark selection [3, 4], then remove bookmark on 4
        updates.clear()
        model.toggleSelection(chapters[2])
        model.toggleSelection(chapters[3])
        model.setBookmark(listOf(chapters[2], chapters[3]), bookmark = true)
        Await.until("bookmarks updated", 5_000) { updates.size == 2 && model.state.value.selected.isEmpty() }
        updates.map { it.id to it.bookmark } shouldBe listOf(3L to true, 4L to true)

        // markPreviousRead(chapter 4) marks only unread chapters older than chapter 4 (2, 3; 1 is already read)
        updates.clear()
        model.markPreviousRead(chapters[3])
        Await.until("previous read updated", 5_000) { updates.isNotEmpty() }
        updates.map { it.id } shouldBe listOf(2L, 3L)
    }

    @Test
    fun `download menu actions enqueue exact unread and bookmarked chapters in reading order`() {
        val enqueued = Collections.synchronizedList(mutableListOf<List<Long>>())
        val downloads = mockk<DownloadManager>(relaxed = true) {
            every { changes } returns MutableStateFlow(0)
            every { queue } returns MutableStateFlow(emptyList())
            coEvery { enqueueAll(any()) } answers {
                enqueued += firstArg<List<Pair<Manga, List<Chapter>>>>().flatMap { (_, list) -> list.map { it.id } }
            }
        }
        val model = newModel(downloads = downloads)

        model.downloadUnread(1)
        Await.until("next 1 queued", 5_000) { enqueued.size == 1 }
        enqueued.last() shouldBe listOf(2L)

        model.downloadUnread(5)
        Await.until("next 5 queued", 5_000) { enqueued.size == 2 }
        enqueued.last() shouldBe (2L..6L).toList()

        model.downloadUnread(10)
        Await.until("next 10 queued", 5_000) { enqueued.size == 3 }
        enqueued.last() shouldBe (2L..11L).toList()

        model.downloadUnread(null)
        Await.until("all unread queued", 5_000) { enqueued.size == 4 }
        enqueued.last() shouldBe (2L..12L).toList()

        model.downloadBookmarked()
        Await.until("bookmarked queued", 5_000) { enqueued.size == 5 }
        enqueued.last() shouldBe listOf(7L, 9L)
    }

    @Test
    fun `deleting downloads of selection asks with chapter count and deletes only after confirm`() =
        runDesktopComposeUiTest(width = 900, height = 700, effectContext = e2eEffectContext()) {
            val deleted = Collections.synchronizedList(mutableListOf<List<Long>>())
            val downloads = mockk<DownloadManager>(relaxed = true) {
                every { changes } returns MutableStateFlow(0)
                every { queue } returns MutableStateFlow(emptyList())
                coEvery { delete(any(), any()) } answers {
                    deleted += secondArg<List<Chapter>>().map { it.id }
                    Unit
                }
            }
            val model = newModel(downloads = downloads)
            model.selectAll()

            var dialog by mutableStateOf<MangaDialog?>(MangaDialog.ConfirmDeleteDownloads(chapters))
            setContent {
                MangaDialogHost(
                    dialog = dialog,
                    manga = manga,
                    model = model,
                    isLocal = false,
                    onShow = { dialog = it },
                )
            }

            val title = localizedPlural(DMR.plurals.delete_selected_downloads_title, chapters.size, chapters.size)
            waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText(title)).fetchSemanticsNodes().isNotEmpty() }
            deleted shouldBe emptyList()

            // Cancel preserves selection and avoids deletion
            onNodeWithText(localized(MR.strings.action_cancel)).performClick()
            waitForIdle()
            deleted shouldBe emptyList()
            model.state.value.selected.size shouldBe 12

            // Re-open and confirm deletes and clears selection
            runOnUiThread { dialog = MangaDialog.ConfirmDeleteDownloads(chapters) }
            waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText(title)).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(localized(DMR.strings.delete_downloads)).performClick()
            waitUntil(timeoutMillis = 5_000) { deleted.size == 1 && model.state.value.selected.isEmpty() }
            deleted.single().size shouldBe 12
        }

    @Test
    fun `ChapterSettingsDialog updates display mode, excluded scanlators, and set as default`() =
        runDesktopComposeUiTest(width = 900, height = 700, effectContext = e2eEffectContext()) {
            val chapterSettings = mockk<ChapterSettings>(relaxed = true) {
                every { excludedScanlators(any()) } returns flowOf(emptySet())
            }
            val model = newModel(chapterSettings = chapterSettings)

            setContent {
                ChapterSettingsDialog(state = model.state.value, model = model, onDismiss = {})
            }

            // Scanlators filter
            onAllNodesWithText(localized(DMR.strings.scanlators)).onFirst().performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Beta Scans")).fetchSemanticsNodes().isNotEmpty() }
            onAllNodesWithText("Beta Scans").onLast().performClick()
            onAllNodesWithText(localized(MR.strings.action_save)).onFirst().performClick()
            coVerify(timeout = 5_000) { chapterSettings.setExcludedScanlators(manga.id, setOf("Beta Scans")) }

            // Display tab
            onAllNodesWithText(localized(MR.strings.action_display)).onFirst().performClick()
            waitUntil(timeoutMillis = 5_000) {
                onAllNodes(hasText(localized(MR.strings.show_chapter_number))).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText(localized(MR.strings.show_chapter_number)).performClick()
            coVerify(timeout = 5_000) { chapterSettings.setDisplayMode(manga, Manga.CHAPTER_DISPLAY_NUMBER) }

            // Set as default (current entry vs all library entries)
            onAllNodesWithText(localized(MR.strings.set_chapter_settings_as_default)).onFirst().performClick()
            val allEntriesLabel = localized(DMR.strings.also_apply_them_to_every_entry)
            waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText(allEntriesLabel)).fetchSemanticsNodes().isNotEmpty() }
            onAllNodesWithText(localized(MR.strings.action_ok)).onLast().performClick()
            coVerify(timeout = 5_000) { chapterSettings.setAsDefault(manga, false) }

            onAllNodesWithText(localized(MR.strings.set_chapter_settings_as_default)).onFirst().performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText(allEntriesLabel)).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(allEntriesLabel).performClick()
            onAllNodesWithText(localized(MR.strings.action_ok)).onLast().performClick()
            coVerify(timeout = 5_000) { chapterSettings.setAsDefault(manga, true) }
        }
}
