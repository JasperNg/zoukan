package zoukan.desktop.ui.library

import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.data.AndroidDatabaseHandler
import tachiyomi.data.Database
import tachiyomi.data.category.CategoryRepositoryImpl
import tachiyomi.data.libraryUpdateError.LibraryUpdateErrorRepositoryImpl
import tachiyomi.data.libraryUpdateError.LibraryUpdateErrorWithRelationsRepositoryImpl
import tachiyomi.data.libraryUpdateErrorMessage.LibraryUpdateErrorMessageRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.category.interactor.CreateCategoryWithName
import tachiyomi.domain.category.interactor.DeleteCategory
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.HideCategory
import tachiyomi.domain.category.interactor.RenameCategory
import tachiyomi.domain.category.interactor.ReorderCategory
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.libraryUpdateError.interactor.DeleteLibraryUpdateErrors
import tachiyomi.domain.libraryUpdateError.interactor.GetLibraryUpdateErrorWithRelations
import tachiyomi.domain.libraryUpdateError.interactor.InsertLibraryUpdateErrors
import tachiyomi.domain.libraryUpdateErrorMessage.interactor.GetLibraryUpdateErrorMessages
import tachiyomi.domain.libraryUpdateErrorMessage.interactor.InsertLibraryUpdateErrorMessages
import tachiyomi.domain.manga.interactor.GetCustomMangaInfo
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.i18n.action_delete
import tachiyomi.i18n.action_rename_category
import tachiyomi.i18n.action_retry
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.kmk.action_hide
import tachiyomi.i18n.kmk.info_empty_library_update_errors
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import zoukan.desktop.compat.localized
import zoukan.desktop.db.DatabaseThread
import zoukan.desktop.db.DesktopDatabase
import zoukan.desktop.db.DesktopSqliteDriver
import zoukan.desktop.library.LibraryUpdateErrors
import zoukan.desktop.library.LibraryUpdater
import zoukan.desktop.testing.FreshInjekt
import zoukan.desktop.testkit.TestPreferenceStore
import zoukan.desktop.ui.category.CategoryScreen
import zoukan.desktop.ui.common.Images
import zoukan.desktop.ui.e2eEffectContext
import zoukan.i18n.desktop.DMR
import zoukan.i18n.desktop.clear_selection
import zoukan.i18n.desktop.selected
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Compose UI behavior tests for library, categories, delete-downloads confirmation, and update
 * errors screen/recomposition.
 */
@OptIn(ExperimentalTestApi::class)
class LibraryAndCategoriesBehaviorTest {

    /**
     * Isolated behavior tests for `CategoriesJourneyTest` and `LibraryUpdateErrorsJourneyTest`:
     * - Category CRUD (add, rename, reorder, hide, default category reset on delete) against SQLite.
     * - Category assignment via [SetMangaCategories].
     * - Library update errors screen listing failed entries, right-click selection, and Retry clearing errors.
     */
    @Nested
    inner class CategoriesAndUpdateErrors {

        @JvmField
        @RegisterExtension
        val injekt = FreshInjekt()

        @TempDir
        lateinit var temp: File

        private val mainDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        private val dbThread = DatabaseThread()
        private lateinit var driver: app.cash.sqldelight.db.SqlDriver
        private lateinit var handler: AndroidDatabaseHandler
        private lateinit var store: TestPreferenceStore
        private lateinit var libraryPrefs: LibraryPreferences

        @BeforeEach
        fun setUp() {
            Dispatchers.setMain(mainDispatcher)
            Images.init(File(temp, "covers"))
            store = TestPreferenceStore()
            libraryPrefs = LibraryPreferences(store)
            driver = DesktopSqliteDriver.withSchema(
                url = "jdbc:sqlite:${File(temp, "library_behavior.db").absolutePath}",
                properties = DesktopDatabase.properties(ByteArray(32) { it.toByte() }),
                databaseThread = dbThread,
                schema = Database.Schema,
            ).also {
                it.execute(null, "DROP TRIGGER IF EXISTS system_category_delete_trigger", 0)
                it.execute(
                    null,
                    """
                    CREATE TRIGGER system_category_delete_trigger BEFORE DELETE
                    ON categories
                    BEGIN SELECT CASE
                        WHEN old._id <= 0 THEN
                            RAISE(ABORT, 'System category can''t be deleted')
                        END;
                    END
                    """.trimIndent(),
                    0,
                )
            }
            val dbDispatcher = dbThread.executor.asCoroutineDispatcher()
            handler = AndroidDatabaseHandler(DesktopDatabase.createDatabase(driver), driver, dbDispatcher, dbDispatcher)

            Injekt.addSingleton<PreferenceStore>(store)
            Injekt.addSingleton(libraryPrefs)
            Injekt.addSingleton(mockk<GetCustomMangaInfo> { every { this@mockk.get(any()) } returns null })
            Injekt.addSingleton(mockk<SourceManager>(relaxed = true))
        }

        @AfterEach
        fun tearDown() {
            Dispatchers.resetMain()
            mainDispatcher.close()
            driver.close()
            dbThread.executor.shutdownNow()
            dbThread.executor.awaitTermination(5, TimeUnit.SECONDS)
        }

        @Test
        fun `category screen adds, renames, hides, sets default, deletes and resets default on delete`() {
            val categoryRepo = CategoryRepositoryImpl(handler)
            val mangaRepo = MangaRepositoryImpl(handler)
            val getCategories = GetCategories(categoryRepo)
            val createCategory = CreateCategoryWithName(categoryRepo, libraryPrefs)
            val setMangaCategories = SetMangaCategories(mangaRepo)
            val deleteCategory = DeleteCategory(categoryRepo, libraryPrefs, DownloadPreferences(store))

            Injekt.addSingleton(getCategories)
            Injekt.addSingleton(createCategory)
            Injekt.addSingleton(deleteCategory)
            Injekt.addSingleton(ReorderCategory(categoryRepo))
            Injekt.addSingleton(RenameCategory(categoryRepo))
            Injekt.addSingleton(HideCategory(categoryRepo))

            fun userCategories() = runBlocking { getCategories.await() }.filterNot { it.isSystemCategory }

            runDesktopComposeUiTest(effectContext = e2eEffectContext()) {
                setContent { Navigator(CategoryScreen) }
                waitForIdle()

                // Add Reading and Later
                for (name in listOf("Reading", "Later")) {
                    onNodeWithTag("category-add").performClick()
                    onNodeWithTag("category-name").performTextInput(name)
                    onNodeWithTag("category-confirm").performClick()
                    waitUntil(timeoutMillis = 10_000) { userCategories().any { it.name == name } }
                }

                // Rename Later -> Plan to read
                onAllNodes(androidx.compose.ui.test.hasContentDescription(localized(MR.strings.action_rename_category)))[1].performClick()
                onNodeWithTag("category-name").performTextClearance()
                onNodeWithTag("category-name").performTextInput("Plan to read")
                onNodeWithTag("category-confirm").performClick()
                waitUntil(timeoutMillis = 10_000) { userCategories().map { it.name } == listOf("Reading", "Plan to read") }

                // Reorder via interactor / Hide Reading
                runBlocking { ReorderCategory(categoryRepo).await(userCategories().last(), 0) }
                waitUntil(timeoutMillis = 10_000) { userCategories().map { it.name } == listOf("Plan to read", "Reading") }
                onAllNodes(androidx.compose.ui.test.hasContentDescription(localized(KMR.strings.action_hide)))[1].performClick()
                waitUntil(timeoutMillis = 10_000) { userCategories().first { it.name == "Reading" }.hidden }

                // Set default category to Plan to read, then delete it and verify default resets to -1
                waitForIdle()
                val plan = userCategories().first { it.name == "Plan to read" }
                libraryPrefs.defaultCategory().set(plan.id.toInt())
                onAllNodes(androidx.compose.ui.test.hasContentDescription(localized(MR.strings.action_delete)))[0].performClick()
                waitForIdle()
                onNodeWithTag("category-confirm").performClick()
                waitForIdle()
                waitUntil(timeoutMillis = 10_000) { userCategories().map { it.name } == listOf("Reading") }
                waitUntil(timeoutMillis = 10_000) { libraryPrefs.defaultCategory().get() == -1 }
                runBlocking { setMangaCategories.await(0L, emptyList()) }
            }
        }

        @Test
        fun `update errors screen selects error row and retry clears fixed errors`() {
            val mangaRepo = MangaRepositoryImpl(handler)
            val errRepo = LibraryUpdateErrorRepositoryImpl(handler)
            val errRelRepo = LibraryUpdateErrorWithRelationsRepositoryImpl(handler)
            val msgRepo = LibraryUpdateErrorMessageRepositoryImpl(handler)
            val getErrors = GetLibraryUpdateErrorWithRelations(errRelRepo)
            val getMessages = GetLibraryUpdateErrorMessages(msgRepo)
            val errors = LibraryUpdateErrors(
                deleteErrors = DeleteLibraryUpdateErrors(errRepo),
                insertErrors = InsertLibraryUpdateErrors(errRepo),
                insertMessages = InsertLibraryUpdateErrorMessages(msgRepo),
            )
            Injekt.addSingleton(mockk<SourceManager>(relaxed = true))
            val charlieId = runBlocking {
                handler.awaitOneExecutable(true) {
                    mangasQueries.insert(
                        source = 1L,
                        url = "/charlie",
                        artist = null,
                        author = null,
                        description = null,
                        genre = null,
                        title = "Charlie",
                        status = 0L,
                        thumbnailUrl = null,
                        favorite = true,
                        lastUpdate = 0L,
                        nextUpdate = 0L,
                        calculateInterval = 0L,
                        initialized = true,
                        viewerFlags = 0L,
                        chapterFlags = 0L,
                        coverLastModified = 0L,
                        dateAdded = 0L,
                        updateStrategy = UpdateStrategy.ALWAYS_UPDATE,
                        version = 0L,
                        notes = "",
                        memo = JsonObject(emptyMap()),
                    )
                    mangasQueries.selectLastInsertedRowId()
                }
            }
            runBlocking { errors.record(charlieId, "Folder missing") }

            val updater = mockk<LibraryUpdater>(relaxed = true) {
                every { progress } returns MutableStateFlow(null)
                every {
                    start(
                        categoryId = any(),
                        mangaIds = eq(setOf(charlieId)),
                        groupIds = any(),
                        manual = any(),
                        onDone = any(),
                    )
                } answers {
                    val callback = arg<(LibraryUpdater.Result) -> Unit>(4)
                    runBlocking { errors.clear(charlieId) }
                    val res = LibraryUpdater.Result(
                        updated = 1,
                        newChapters = 0,
                        entriesWithNewChapters = emptyList(),
                        queuedDownloads = 0,
                        skipped = emptyList(),
                        failures = emptyList(),
                        errorFile = null,
                    )
                    callback.invoke(res)
                    true
                }
            }

            Injekt.addSingleton(getErrors)
            Injekt.addSingleton(getMessages)
            Injekt.addSingleton(errors)
            Injekt.addSingleton(updater)
            Injekt.addSingleton(GetManga(mangaRepo))

            runDesktopComposeUiTest(effectContext = e2eEffectContext()) {
                setContent { Navigator(UpdateErrorsScreen) }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("update-error").fetchSemanticsNodes().size == 1 }
                onNodeWithText("Charlie").assertExists()

                // Right-click selects error row, clear selection unselects
                onAllNodesWithTag("update-error").onFirst().performMouseInput { rightClick() }
                waitForIdle()
                onNodeWithText(localized(DMR.strings.selected, 1)).assertExists()
                onNodeWithContentDescription(localized(DMR.strings.clear_selection)).performClick()
                waitForIdle()

                // Retry updates Charlie and clears error list
                onNodeWithContentDescription(localized(MR.strings.action_retry)).performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("update-error").fetchSemanticsNodes().isEmpty() }
                onNodeWithText(localized(KMR.strings.info_empty_library_update_errors)).assertExists()
                runBlocking { getErrors.subscribeAll().first() } shouldContainExactly emptyList()
            }
        }
    }

    @Nested
    inner class DeleteDownloadsDialogConfirmation {

        @Test
        fun `deleting the downloads of two entries asks first and deletes only on confirm`() = runDesktopComposeUiTest {
            var confirmed = 0
            var dismissed = 0
            val shown = mutableStateOf(true)
            setContent {
                if (shown.value) {
                    LibraryDeleteDownloadsDialog(
                        entryCount = 2,
                        onConfirm = { confirmed++ },
                        onDismiss = {
                            dismissed++
                            shown.value = false
                        },
                    )
                }
            }
            onNodeWithText("2 entries", substring = true).assertExists()

            onNodeWithText("Cancel").performClick()
            confirmed shouldBe 0
            dismissed shouldBe 1

            shown.value = true
            waitForIdle()
            onNodeWithText("Delete downloads").performClick()
            confirmed shouldBe 1
        }
    }

    @Nested
    inner class UpdateRunningRecomposition {

        @Test
        fun `progress ticks do not recompose a screen that only shows whether an update runs`() = runDesktopComposeUiTest {
            val progress = MutableStateFlow<LibraryUpdater.Progress?>(LibraryUpdater.Progress(0, 10, emptyList()))
            var compositions = 0
            setContent {
                val running by rememberLibraryUpdateRunning(progress)
                SideEffect { compositions++ }
                Text(if (running) "running" else "idle")
            }
            waitForIdle()
            repeat(10) { i ->
                progress.value = LibraryUpdater.Progress(i + 1, 10, listOf("Entry $i"))
                waitForIdle()
            }
            compositions shouldBe 1
            progress.value = null
            waitForIdle()
            onNodeWithText("idle").assertExists()
            compositions shouldBe 2
        }
    }
}
