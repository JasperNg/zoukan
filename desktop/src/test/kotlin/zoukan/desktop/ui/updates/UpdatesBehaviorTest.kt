package zoukan.desktop.ui.updates

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.data.AndroidDatabaseHandler
import tachiyomi.data.Database
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.data.updates.UpdatesRepositoryImpl
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetCustomMangaInfo
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.updates.interactor.GetUpdates
import tachiyomi.domain.updates.service.UpdatesPreferences
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.compat.localized
import zoukan.desktop.db.DatabaseThread
import zoukan.desktop.db.DesktopDatabase
import zoukan.desktop.db.DesktopSqliteDriver
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.library.LibraryUpdater
import zoukan.desktop.testing.FreshInjekt
import zoukan.desktop.testkit.TestPreferenceStore
import zoukan.desktop.ui.common.Images
import zoukan.i18n.desktop.DMR
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Isolated Compose UI test for [UpdatesTab] and [UpdatesScreenModel]:
 * folded groups expand and collapse, a row's read/unread button toggles its chapter alone,
 * selecting a folded leader selects every chapter in the group, and Bookmark bookmarks all
 * selected chapters and clears the selection.
 */
@OptIn(ExperimentalTestApi::class)
class UpdatesBehaviorTest {
    @JvmField
    @RegisterExtension
    val injekt = FreshInjekt()

    @TempDir
    lateinit var temp: File

    private val mainDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val dbThread = DatabaseThread()
    private lateinit var driver: app.cash.sqldelight.db.SqlDriver
    private lateinit var handler: AndroidDatabaseHandler
    private lateinit var getChapters: GetChaptersByMangaId

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        Images.init(File(temp, "covers"))
        val store = TestPreferenceStore()
        driver = DesktopSqliteDriver.withSchema(
            url = "jdbc:sqlite:${File(temp, "updates.db").absolutePath}",
            properties = DesktopDatabase.properties(ByteArray(32) { it.toByte() }),
            databaseThread = dbThread,
            schema = Database.Schema,
        )
        val dbDispatcher = dbThread.executor.asCoroutineDispatcher()
        handler = AndroidDatabaseHandler(DesktopDatabase.createDatabase(driver), driver, dbDispatcher, dbDispatcher)

        val mangaRepo = MangaRepositoryImpl(handler)
        val chapterRepo = ChapterRepositoryImpl(handler)
        getChapters = GetChaptersByMangaId(chapterRepo)

        val downloadManager = mockk<DownloadManager>(relaxed = true) {
            every { changes } returns MutableStateFlow(0)
            every { queue } returns MutableStateFlow(emptyList())
            every { downloadedIds(any(), any()) } returns emptySet()
        }
        val libraryUpdater = mockk<LibraryUpdater>(relaxed = true) {
            every { progress } returns MutableStateFlow(null)
        }

        Injekt.addSingleton<PreferenceStore>(store)
        Injekt.addSingleton(UiPreferences(store))
        Injekt.addSingleton(DesktopPreferences(store))
        Injekt.addSingleton(LibraryPreferences(store))
        Injekt.addSingleton(UpdatesPreferences(store))
        Injekt.addSingleton(downloadManager)
        Injekt.addSingleton(libraryUpdater)
        Injekt.addSingleton(GetUpdates(UpdatesRepositoryImpl(handler)))
        Injekt.addSingleton(UpdateChapter(chapterRepo))
        Injekt.addSingleton(GetFavorites(mangaRepo))
        Injekt.addSingleton(GetManga(mangaRepo))
        Injekt.addSingleton(mockk<GetCustomMangaInfo> { every { this@mockk.get(any()) } returns null })
        Injekt.addSingleton(GetChapter(chapterRepo))
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        mainDispatcher.close()
        driver.close()
        dbThread.executor.shutdownNow()
        dbThread.executor.awaitTermination(5, TimeUnit.SECONDS)
    }

    private fun seedUpdates(): List<Long> = runBlocking {
        val now = System.currentTimeMillis()
        val alphaId = handler.awaitOneExecutable(true) {
            mangasQueries.insert(
                source = 1L, url = "/alpha", artist = null, author = null, description = null, genre = null,
                title = "Alpha", status = 0L, thumbnailUrl = null, favorite = true, lastUpdate = 0L, nextUpdate = 0L,
                calculateInterval = 0L, initialized = true, viewerFlags = 0L, chapterFlags = 0L, coverLastModified = 0L,
                dateAdded = 0L, updateStrategy = UpdateStrategy.ALWAYS_UPDATE, version = 0L, notes = "", memo = JsonObject(emptyMap()),
            )
            mangasQueries.selectLastInsertedRowId()
        }
        val bravoId = handler.awaitOneExecutable(true) {
            mangasQueries.insert(
                source = 1L, url = "/bravo", artist = null, author = null, description = null, genre = null,
                title = "Bravo", status = 0L, thumbnailUrl = null, favorite = true, lastUpdate = 0L, nextUpdate = 0L,
                calculateInterval = 0L, initialized = true, viewerFlags = 0L, chapterFlags = 0L, coverLastModified = 0L,
                dateAdded = 0L, updateStrategy = UpdateStrategy.ALWAYS_UPDATE, version = 0L, notes = "", memo = JsonObject(emptyMap()),
            )
            mangasQueries.selectLastInsertedRowId()
        }
        handler.await(inTransaction = true) {
            // Alpha has 3 chapters on the same day, Bravo has 2
            listOf(
                Triple(alphaId, "/a/1", 1.0),
                Triple(alphaId, "/a/2", 2.0),
                Triple(alphaId, "/a/3", 3.0),
                Triple(bravoId, "/b/1", 1.0),
                Triple(bravoId, "/b/2", 2.0),
            ).forEachIndexed { idx, (mid, url, num) ->
                chaptersQueries.insert(
                    mangaId = mid,
                    url = url,
                    name = "Chapter ${num.toInt()}",
                    scanlator = null,
                    read = false,
                    bookmark = false,
                    lastPageRead = 0L,
                    chapterNumber = num,
                    sourceOrder = idx.toLong(),
                    dateFetch = now - idx * 1_000L,
                    dateUpload = now - idx * 1_000L,
                    version = 0L,
                    memo = JsonObject(emptyMap()),
                )
            }
        }
        listOf(alphaId, bravoId)
    }

    @Test
    fun `folded groups expand, toggle read, select as group, and bookmark all`() {
        val mangaIds = seedUpdates()
        val model = UpdatesScreenModel()
        try {
            runDesktopComposeUiTest {
                setContent { cafe.adriel.voyager.navigator.Navigator(BlankScreen(model)) }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("updates-leader").fetchSemanticsNodes().size == 2 }
                onAllNodesWithTag("updates-item").fetchSemanticsNodes().size shouldBe 0

                // Expand first group -> reveals Alpha's 2 extra chapters
                onAllNodes(hasContentDescription(localized(DMR.strings.expand))).onFirst().performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("updates-item").fetchSemanticsNodes().size == 2 }

                // Row Mark as read marks only that chapter, then Mark as unread reverts it
                onAllNodes(hasContentDescription(localized(MR.strings.action_mark_as_read))).onFirst().performClick()
                waitUntil(timeoutMillis = 10_000) {
                    runBlocking { mangaIds.flatMap { getChapters.await(it) } }.count { it.read } == 1 &&
                        onAllNodes(hasContentDescription(localized(MR.strings.action_mark_as_unread))).fetchSemanticsNodes().isNotEmpty()
                }
                onAllNodes(hasContentDescription(localized(MR.strings.action_mark_as_unread))).onFirst().performClick()
                waitUntil(timeoutMillis = 10_000) {
                    runBlocking { mangaIds.flatMap { getChapters.await(it) } }.none { it.read }
                }

                // Collapse hides Alpha's extra chapters again
                onAllNodes(hasContentDescription(localized(DMR.strings.collapse))).onFirst().performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("updates-item").fetchSemanticsNodes().isEmpty() }

                // Right-click folded Alpha leader -> Select selects all 3 chapters of Alpha
                onAllNodesWithTag("updates-leader").onFirst().performMouseInput { rightClick() }
                onNodeWithText(localized(DMR.strings.select)).performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(localized(DMR.strings.selected, 3)).fetchSemanticsNodes().isNotEmpty() }

                // Select all selects all 5 chapters; Bookmark bookmarks them all and ends selection
                onNodeWithContentDescription(localized(MR.strings.action_select_all)).performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(localized(DMR.strings.selected, 5)).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithContentDescription(localized(DMR.strings.bookmark)).performClick()
                waitUntil(timeoutMillis = 10_000) {
                    runBlocking { mangaIds.flatMap { getChapters.await(it) } }.all { it.bookmark } &&
                        !model.state.value.selecting
                }
                waitForIdle()
                model.state.value.selecting shouldBe false
            }
        } finally {
            model.onDispose()
            runBlocking {
                withTimeout(5_000) {
                    val job = model.screenModelScope.coroutineContext[Job]!!
                    job.cancelChildren()
                    job.children.toList().joinAll()
                }
            }
        }
    }

    private class BlankScreen(private val model: UpdatesScreenModel) : cafe.adriel.voyager.core.screen.Screen {
        @androidx.compose.runtime.Composable
        override fun Content() = UpdatesTab(model)
    }
}
