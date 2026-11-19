package zoukan.desktop.ui.history

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.TriState
import tachiyomi.data.AndroidDatabaseHandler
import tachiyomi.data.Database
import tachiyomi.data.category.CategoryRepositoryImpl
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.history.HistoryRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.GetMergedChaptersByMangaId
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.history.interactor.RemoveHistory
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.history.service.HistoryPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import zoukan.desktop.compat.localized
import zoukan.desktop.db.DatabaseThread
import zoukan.desktop.db.DesktopDatabase
import zoukan.desktop.db.DesktopSqliteDriver
import zoukan.desktop.library.AddToLibrary
import zoukan.desktop.testing.Await
import zoukan.desktop.testing.FreshInjekt
import zoukan.desktop.ui.common.Images
import zoukan.i18n.desktop.DMR
import java.io.File
import java.util.Date
import java.util.concurrent.Executors

/**
 * Isolated Compose UI test for [HistoryTab] and [HistoryScreenModel] against SQLite:
 * search narrowing and empty message, removing one chapter vs removing the whole entry,
 * tri-state filters (`nonLibrary`, `unfinishedChapter`, `unfinishedManga`, `usePanoramaCover`),
 * select all + remove, clear all history, and adding a non-library title from its row.
 */
@OptIn(ExperimentalTestApi::class)
class HistoryBehaviorTest {
    @JvmField
    @RegisterExtension
    val injekt = FreshInjekt()

    @TempDir
    lateinit var temp: File

    private val mainDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val dbThread = DatabaseThread()
    private lateinit var driver: app.cash.sqldelight.db.SqlDriver
    private lateinit var handler: AndroidDatabaseHandler
    private lateinit var getHistory: GetHistory
    private lateinit var upsertHistory: UpsertHistory
    private lateinit var getManga: GetManga
    private lateinit var historyPrefs: HistoryPreferences

    private class HostScreen(private val model: HistoryScreenModel) : Screen {
        @Composable
        override fun Content() = HistoryTab(model)
    }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        Images.init(File(temp, "covers"))
        val store = InMemoryPreferenceStore()
        historyPrefs = HistoryPreferences(store)
        val libraryPrefs = LibraryPreferences(store)

        driver = DesktopSqliteDriver.withSchema(
            url = "jdbc:sqlite:${File(temp, "history.db").absolutePath}",
            properties = DesktopDatabase.properties(ByteArray(32) { it.toByte() }),
            databaseThread = dbThread,
            schema = Database.Schema,
        )
        val dbDispatcher = dbThread.executor.asCoroutineDispatcher()
        handler = AndroidDatabaseHandler(DesktopDatabase.createDatabase(driver), driver, dbDispatcher, dbDispatcher)

        val mangaRepo = MangaRepositoryImpl(handler)
        val chapterRepo = ChapterRepositoryImpl(handler)
        val historyRepo = HistoryRepositoryImpl(handler)
        val categoryRepo = CategoryRepositoryImpl(handler)
        val sourceManager = mockk<SourceManager>(relaxed = true) { every { get(any<Long>()) } returns null }

        getHistory = GetHistory(historyRepo)
        upsertHistory = UpsertHistory(historyRepo)
        getManga = GetManga(mangaRepo)
        val getCategories = GetCategories(categoryRepo)
        val setCategories = SetMangaCategories(mangaRepo)

        Injekt.addSingleton<PreferenceStore>(store)
        Injekt.addSingleton(historyPrefs)
        Injekt.addSingleton(libraryPrefs)
        Injekt.addSingleton(getHistory)
        Injekt.addSingleton(RemoveHistory(historyRepo))
        Injekt.addSingleton(
            GetNextChapters(
                getChaptersByMangaId = GetChaptersByMangaId(chapterRepo),
                getMergedChaptersByMangaId = mockk<GetMergedChaptersByMangaId>(relaxed = true),
                getManga = getManga,
                historyRepository = historyRepo,
            ),
        )
        Injekt.addSingleton(GetChapter(chapterRepo))
        Injekt.addSingleton(getManga)
        Injekt.addSingleton<MangaRepository>(mangaRepo)
        Injekt.addSingleton(getCategories)
        Injekt.addSingleton(
            AddToLibrary(
                getCategories = getCategories,
                setMangaCategories = setCategories,
                mangaRepository = mangaRepo,
                getDuplicateLibraryManga = GetDuplicateLibraryManga(mangaRepo),
                libraryPreferences = libraryPrefs,
                addTracks = mockk<AddTracks>(relaxed = true),
                refreshManga = mockk(relaxed = true),
                sourceManager = sourceManager,
            ),
        )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        mainDispatcher.close()
        driver.close()
        dbThread.executor.shutdownNow()
    }

    private fun seedData(): Triple<Long, Long, List<Long>> = runBlocking {
        val now = System.currentTimeMillis()
        suspend fun insertManga(title: String, favorite: Boolean): Long = handler.awaitOneExecutable(true) {
            mangasQueries.insert(
                source = 1L, url = "/$title", artist = null, author = null, description = null, genre = null,
                title = title, status = 0L, thumbnailUrl = null, favorite = favorite, lastUpdate = 0L, nextUpdate = 0L,
                calculateInterval = 0L, initialized = true, viewerFlags = 0L, chapterFlags = 0L, coverLastModified = 0L,
                dateAdded = 0L, updateStrategy = UpdateStrategy.ALWAYS_UPDATE, version = 0L, notes = "", memo = JsonObject(emptyMap()),
            )
            mangasQueries.selectLastInsertedRowId()
        }
        suspend fun insertChapter(mangaId: Long, url: String, name: String, num: Double, read: Boolean): Long =
            handler.awaitOneExecutable(true) {
                chaptersQueries.insert(
                    mangaId = mangaId, url = url, name = name, scanlator = null, read = read, bookmark = false,
                    lastPageRead = 0L, chapterNumber = num, sourceOrder = num.toLong(), dateFetch = now,
                    dateUpload = now, version = 0L, memo = JsonObject(emptyMap()),
                )
                chaptersQueries.selectLastInsertedRowId()
            }

        val formatsId = insertManga("Formats Title", favorite = true)
        val secondId = insertManga("Second Title", favorite = true)
        val f1 = insertChapter(formatsId, "/f/1", "Chapter 1", 1.0, read = true)
        val f2 = insertChapter(formatsId, "/f/2", "Chapter 2", 2.0, read = false)
        val s1 = insertChapter(secondId, "/s/1", "Chapter 1", 1.0, read = false)
        // Formats read most recently (chapter 2, and chapter 1 earlier); Second read 1h ago
        upsertHistory.await(HistoryUpdate(f1, Date(now - 30_000L), 60_000L))
        upsertHistory.await(HistoryUpdate(f2, Date(now), 60_000L))
        upsertHistory.await(HistoryUpdate(s1, Date(now - 3_600_000L), 60_000L))
        Triple(formatsId, secondId, listOf(f1, f2, s1))
    }

    private fun rows() = runBlocking { getHistory.subscribe("", null, null, null).first() }

    @Test
    fun `search, remove chapter vs series, tri-state filters, select-all, clear, and add to library`() {
        val (formatsId, secondId, chapters) = seedData()
        val (f1, f2, s1) = chapters
        val model = HistoryScreenModel()
        val removeDesc = localized(DMR.strings.remove_from_history)
        val removeText = localized(MR.strings.action_remove)

        try {
            runDesktopComposeUiTest {
                setContent { Navigator(HostScreen(model)) }
                Await.until("two history rows") { onAllNodesWithTag("history-item").fetchSemanticsNodes().size == 2 }

                // 1. Search narrows list and shows empty message
                onAllNodes(hasSetTextAction()).onFirst().performTextReplacement("Second")
                Await.until("narrowed to 1 row") { onAllNodesWithTag("history-item").fetchSemanticsNodes().size == 1 }
                onNodeWithText("Second Title").assertExists()
                onNodeWithText("Formats Title").assertDoesNotExist()

                onAllNodes(hasSetTextAction()).onFirst().performTextReplacement("zzz-nothing")
                Await.until("empty search message") {
                    onAllNodesWithText(localized(DMR.strings.nothing_matches, "zzz-nothing")).fetchSemanticsNodes().isNotEmpty()
                }
                onAllNodesWithTag("history-item").fetchSemanticsNodes() shouldHaveSize 0
                onAllNodes(hasSetTextAction()).onFirst().performTextReplacement("")
                Await.until("both rows back") { onAllNodesWithTag("history-item").fetchSemanticsNodes().size == 2 }

                // 2. Remove single chapter (f2) leaves Formats Title with f1
                onAllNodes(hasContentDescription(removeDesc)).onFirst().performClick()
                onNodeWithText(removeText).performClick()
                Await.until("f2 removed, f1 remains") { rows().firstOrNull { it.mangaId == formatsId }?.chapterId == f1 }
                onAllNodesWithTag("history-item").fetchSemanticsNodes() shouldHaveSize 2

                // 3. Remove whole entry removes Formats Title completely
                onAllNodes(hasContentDescription(removeDesc)).onFirst().performClick()
                onNodeWithText(localized(DMR.strings.remove_every_chapter_of_this_entry)).performClick()
                onNodeWithText(removeText).performClick()
                Await.until("Formats Title removed") { rows().none { it.mangaId == formatsId } }
                onAllNodesWithTag("history-item").fetchSemanticsNodes() shouldHaveSize 1

                // 4. Filters dialog: non-library leaves none, inverted keeps Second, unfinished chapters + panorama
                runBlocking { upsertHistory.await(HistoryUpdate(f1, Date(), 60_000L)) }
                Await.until("two rows again") { onAllNodesWithTag("history-item").fetchSemanticsNodes().size == 2 }
                onNodeWithContentDescription(localized(MR.strings.action_filter)).performClick()
                onNodeWithText(localized(DMR.strings.not_in_library)).performClick()
                onNodeWithText(localized(MR.strings.action_close)).performClick()
                Await.until("no history matches filter") {
                    onAllNodesWithText(localized(DMR.strings.no_history_matches_the_filters)).fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithContentDescription(localized(DMR.strings.filter_filtered)).performClick()
                onNodeWithText(localized(DMR.strings.not_in_library)).performClick()
                onNodeWithText(localized(MR.strings.action_close)).performClick()
                Await.until("both rows back with in-library") { onAllNodesWithTag("history-item").fetchSemanticsNodes().size == 2 }

                onNodeWithContentDescription(localized(DMR.strings.filter_filtered)).performClick()
                onNodeWithText(localized(MR.strings.in_library)).performClick()
                onNodeWithText(localized(DMR.strings.unfinished_chapters)).performClick()
                val beforePanorama = historyPrefs.usePanoramaCover().get()
                onNodeWithText(localized(KMR.strings.action_panorama_cover)).performClick()
                historyPrefs.usePanoramaCover().get() shouldBe !beforePanorama
                historyPrefs.filterUnfinishedChapter().get() shouldBe TriState.ENABLED_IS
                onNodeWithText(localized(MR.strings.action_close)).performClick()
                Await.until("only unfinished chapter row shown") {
                    onAllNodesWithTag("history-item").fetchSemanticsNodes().size == 1 &&
                        onAllNodesWithText("Second Title").fetchSemanticsNodes().isNotEmpty()
                }
                // Reset filters for selection test
                historyPrefs.filterUnfinishedChapter().set(TriState.DISABLED)
                Await.until("both rows back") { onAllNodesWithTag("history-item").fetchSemanticsNodes().size == 2 }

                // 5. Select all and remove
                onNodeWithContentDescription(localized(DMR.strings.select)).performClick()
                onNodeWithContentDescription(localized(MR.strings.action_select_all)).performClick()
                onNodeWithText(localized(DMR.strings.selected, 2)).assertExists()
                onNodeWithContentDescription(removeDesc).performClick()
                onNodeWithText(removeText).performClick()
                Await.until("all removed") { rows().isEmpty() }
                onNodeWithText(localized(DMR.strings.nothing_read_yet)).assertExists()

                // 6. Clear all history
                runBlocking { upsertHistory.await(HistoryUpdate(s1, Date(), 60_000L)) }
                Await.until("one row seeded") { onAllNodesWithTag("history-item").fetchSemanticsNodes().size == 1 }
                onNodeWithContentDescription(localized(MR.strings.pref_clear_history)).performClick()
                onNodeWithText(localized(DMR.strings.clear)).performClick()
                Await.until("history cleared") { rows().isEmpty() }

                // 7. Non-library entry: Add to library button adds it
                runBlocking {
                    MangaRepositoryImpl(handler).update(MangaUpdate(id = secondId, favorite = false))
                    upsertHistory.await(HistoryUpdate(s1, Date(), 60_000L))
                }
                Await.until("non-library row shown") {
                    onAllNodes(hasContentDescription(localized(MR.strings.add_to_library))).fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithContentDescription(localized(MR.strings.add_to_library)).performClick()
                Await.until("entry added to library") { runBlocking { getManga.await(secondId) }?.favorite == true }
            }
        } finally {
            model.onDispose()
        }
    }
}
