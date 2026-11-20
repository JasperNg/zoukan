package zoukan.desktop.ui.manga

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import eu.kanade.domain.manga.interactor.UpdateManga
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import mihon.domain.migration.models.MigrationFlag
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.interactor.GetCustomMangaInfo
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.i18n.action_add
import tachiyomi.i18n.action_add_anyway
import tachiyomi.i18n.action_download
import tachiyomi.i18n.action_migrate
import tachiyomi.i18n.action_ok
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.kmk.chapters_from_database
import tachiyomi.i18n.kmk.downloaded_data
import tachiyomi.i18n.kmk.`open`
import tachiyomi.i18n.label_default
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.get
import xyz.nulldev.androidcompat.io.sharedprefs.ForTestsOnly
import xyz.nulldev.androidcompat.io.sharedprefs.JavaSharedPreferences
import zoukan.desktop.compat.localized
import zoukan.desktop.manga.EditManga
import zoukan.desktop.testing.FreshInjekt
import zoukan.desktop.ui.common.Images
import zoukan.i18n.desktop.DMR
import zoukan.i18n.desktop.add_the_entry_to_the_library
import zoukan.i18n.desktop.change
import zoukan.i18n.desktop.clear_local_files
import zoukan.i18n.desktop.days
import zoukan.i18n.desktop.every
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tests for entry action dialogs (Injekt-backed dialogs, confirmation dialogs, merge/migrate flags,
 * and merge/migration outcome reporting).
 */
@OptIn(ExperimentalTestApi::class, ForTestsOnly::class)
class EntryActionsDialogsTest {

    /**
     * The entry actions' dialogs that look their services up in Injekt: each gets a fake here, so the
     * test sees what the dialog's buttons do. Each test runs in a fresh Injekt scope via [FreshInjekt].
     */
    @Nested
    inner class InjektDialogs {

        @JvmField
        @RegisterExtension
        val injekt = FreshInjekt()

        @TempDir
        lateinit var tempDir: File

        private val updates = CopyOnWriteArrayList<MangaUpdate>()
        private val updateRecorder = mockk<MangaRepository> {
            coEvery { update(any()) } answers {
                updates += firstArg<MangaUpdate>()
                true
            }
        }

        @BeforeEach
        fun setUp() {
            // The dialogs load covers through the app's image loader
            Images.init(File(tempDir, "entry-actions-covers").apply { mkdirs() })
            // A library entry reads its custom info through Injekt
            injekted<GetCustomMangaInfo> { mockk { every { this@mockk.get(any()) } returns null } }
            injekted { spyk(EditManga(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))) }
            injekted { UpdateManga(updateRecorder, FetchInterval(mockk<GetChaptersByMangaId>(relaxed = true))) }
            injekted { LibraryPreferences(DesktopPreferenceStore(JavaSharedPreferences("entry_actions_test"))) }
            injekted<SourceManager> { mockk(relaxed = true) }
            updates.clear()
        }

        @AfterEach
        fun waitForWriters() {
            JavaSharedPreferences.awaitPendingSaves()
        }

        private fun libraryPreferences(customIntervals: Boolean) {
            Injekt.get<LibraryPreferences>().autoUpdateMangaRestrictions().set(
                if (customIntervals) setOf(LibraryPreferences.MANGA_OUTSIDE_RELEASE_PERIOD) else emptySet(),
            )
        }

        private fun entry(favorite: Boolean) = Manga.create().copy(id = 42, source = 1L, ogTitle = "Alpha", favorite = favorite)

        @Test
        fun `update frequency saves the chosen interval`() = runDesktopComposeUiTest {
            libraryPreferences(customIntervals = true)
            val manga = entry(favorite = true)
            setContent { FetchIntervalDialog(manga, onDismiss = {}) }

            onNodeWithText("${localized(DMR.strings.every)}: ${localized(MR.strings.label_default)}").performClick()
            onNodeWithText(localized(DMR.strings.days, 3)).performClick()
            onNodeWithText(localized(MR.strings.action_ok)).performClick()
            waitUntil(timeoutMillis = 5_000) { updates.isNotEmpty() }

            // Stored negative: a custom interval, as Android stores it
            updates.single().id shouldBe 42L
            updates.single().fetchInterval shouldBe -3
        }

        @Test
        fun `the cover dialog of a library entry offers to change its cover`() = runDesktopComposeUiTest {
            setContent { CoverDialog(entry(favorite = true), onDismiss = {}) }
            onNodeWithText(localized(DMR.strings.change)).assertExists()
            onNodeWithText(localized(DMR.strings.add_the_entry_to_the_library)).assertDoesNotExist()
        }

        @Test
        fun `the cover dialog of an entry outside the library asks to add it first`() = runDesktopComposeUiTest {
            setContent { CoverDialog(entry(favorite = false), onDismiss = {}) }
            onNodeWithText(localized(DMR.strings.change)).assertDoesNotExist()
            onNodeWithText(localized(DMR.strings.add_the_entry_to_the_library)).assertExists()
        }

        @Test
        fun `each duplicate button calls its own action`() = runDesktopComposeUiTest {
            val duplicate = Manga.create().copy(id = 7, source = 1L, ogTitle = "Alpha (other source)")
            val opened = mutableListOf<Long>()
            val migrated = mutableListOf<Long>()
            var addedAnyway = 0
            setContent {
                DuplicateDialog(
                    target = entry(favorite = false),
                    duplicates = listOf(MangaWithChapterCount(duplicate, 2)),
                    onAddAnyway = { addedAnyway++ },
                    onOpen = { opened += it.id },
                    onMigrate = { migrated += it.id },
                    onDismiss = {},
                )
            }

            onNodeWithText(localized(MR.strings.action_migrate)).performClick()
            waitForIdle()
            migrated shouldBe listOf(7L)
            opened shouldBe emptyList()

            onNodeWithText(localized(KMR.strings.open)).performClick()
            waitForIdle()
            opened shouldBe listOf(7L)
            migrated shouldBe listOf(7L)

            onNodeWithText(localized(MR.strings.action_add_anyway)).performClick()
            waitForIdle()
            addedAnyway shouldBe 1
        }
    }

    /**
     * The entry page's dialogs that only report a choice: each confirm button passes on exactly what
     * the user checked or typed. Clear entry matters most, as its flags delete downloads and local files.
     */
    @Nested
    inner class ConfirmDialogs {

        private inner class Clear(val deleteDownload: Boolean, val removeChapters: Boolean, val deleteLocalFiles: Boolean) {
            override fun equals(other: Any?): Boolean =
                other is Clear &&
                    deleteDownload == other.deleteDownload &&
                    removeChapters == other.removeChapters &&
                    deleteLocalFiles == other.deleteLocalFiles

            override fun hashCode(): Int =
                (deleteDownload.hashCode() * 31 + removeChapters.hashCode()) * 31 + deleteLocalFiles.hashCode()
        }

        private fun ComposeUiTest.row(label: String): SemanticsNodeInteraction = onNode(hasText(label) and isToggleable())

        private fun clearWith(vararg labels: (String) -> String): List<Clear> {
            val confirmed = mutableListOf<Clear>()
            runDesktopComposeUiTest {
                setContent {
                    ClearMangaDialog(
                        onDismissRequest = {},
                        showDownloadedData = true,
                        localFolders = listOf("Alpha"),
                        onConfirm = { download, chapters, local -> confirmed += Clear(download, chapters, local) },
                    )
                }
                labels.forEach { row(it("Alpha")).performClick() }
                onNodeWithText(localized(MR.strings.action_ok)).performClick()
                waitForIdle()
            }
            return confirmed
        }

        private val downloadedData: (String) -> String = { localized(KMR.strings.downloaded_data) }
        private val chaptersFromDatabase: (String) -> String = { localized(KMR.strings.chapters_from_database) }
        private val localFiles: (String) -> String = { localized(DMR.strings.clear_local_files, it) }

        @Test
        fun `clear entry deletes only the downloads when only downloaded data is checked`() {
            clearWith(downloadedData) shouldBe listOf(Clear(deleteDownload = true, removeChapters = false, deleteLocalFiles = false))
        }

        @Test
        fun `clear entry removes only the chapters when only chapters from database is checked`() {
            clearWith(chaptersFromDatabase) shouldBe listOf(Clear(deleteDownload = false, removeChapters = true, deleteLocalFiles = false))
        }

        @Test
        fun `clear entry deletes only the local files when only the local folder is checked`() {
            clearWith(localFiles) shouldBe listOf(Clear(deleteDownload = false, removeChapters = false, deleteLocalFiles = true))
        }

        @Test
        fun `custom download passes on the typed count`() = runDesktopComposeUiTest {
            val confirmed = mutableListOf<Int>()
            setContent { CustomDownloadDialog(onConfirm = { confirmed += it }, onDismiss = {}) }
            onNode(hasSetTextAction()).performTextInput("3")
            onNodeWithText(localized(MR.strings.action_download)).performClick()
            waitForIdle()
            confirmed shouldBe listOf(3)
        }

        private val categories = listOf(
            Category(id = 1, name = "Reading", order = 0, flags = 0, hidden = false),
            Category(id = 2, name = "Later", order = 1, flags = 0, hidden = false),
            Category(id = 3, name = "Done", order = 2, flags = 0, hidden = false),
        )

        @Test
        fun `add to categories passes on the checked categories`() = runDesktopComposeUiTest {
            val confirmed = mutableListOf<List<Long>>()
            setContent { CategoryDialog(categories, onConfirm = { confirmed += it }, onDismiss = {}) }
            row("Later").performClick()
            row("Done").performClick()
            onNodeWithText(localized(MR.strings.action_add)).performClick()
            waitForIdle()
            confirmed.single().sorted() shouldBe listOf(2L, 3L)
        }

        @Test
        fun `change categories starts with the entry's own categories checked and keeps them on OK`() = runDesktopComposeUiTest {
            val confirmed = mutableListOf<Set<Long>>()
            setContent { ChangeCategoryDialog(categories, chosen = setOf(1L, 3L), onConfirm = { confirmed += it }, onDismiss = {}) }
            row("Reading").assertIsOn()
            row("Later").assertIsOff()
            row("Done").assertIsOn()
            onNodeWithText(localized(MR.strings.action_ok)).performClick()
            waitForIdle()
            confirmed shouldBe listOf(setOf(1L, 3L))
        }

        @Test
        fun `change categories applies a checked and an unchecked category`() = runDesktopComposeUiTest {
            val confirmed = mutableListOf<Set<Long>>()
            setContent { ChangeCategoryDialog(categories, chosen = setOf(1L), onConfirm = { confirmed += it }, onDismiss = {}) }
            row("Reading").performClick()
            row("Later").performClick()
            onNodeWithText(localized(MR.strings.action_ok)).performClick()
            waitForIdle()
            confirmed shouldBe listOf(setOf(2L))
        }
    }

    @Nested
    inner class MergeAndMigrateFlags {

        @Test
        fun `the Custom cover checkbox only shows when the entry has a custom cover, as on Android`() {
            migrationDialogFlags(hasCustomCover = false) shouldBe MigrationFlag.entries - MigrationFlag.CUSTOM_COVER
            migrationDialogFlags(hasCustomCover = true) shouldBe MigrationFlag.entries
        }

        @Test
        fun `flags round-trip through the migration_flags preference`() {
            val all = MigrationFlag.entries.toSet()
            MigrationFlag.toBit(emptySet()) shouldBe 0
            MigrationFlag.fromBit(MigrationFlag.toBit(all)) shouldBe all
            // The custom cover bit survives, so a ticked checkbox carries the cover along
            MigrationFlag.fromBit(MigrationFlag.toBit(setOf(MigrationFlag.CUSTOM_COVER))) shouldBe setOf(MigrationFlag.CUSTOM_COVER)
        }
    }

    /** Merge and migration report real failures, never a cancellation. */
    @Nested
    inner class ReportOutcomeHandling {

        @Test
        fun `a failure is reported and rethrown`() {
            val reported = mutableListOf<Throwable>()
            val failure = IllegalStateException("boom")

            shouldThrow<IllegalStateException> {
                runBlocking { reportOutcome(onFailure = { reported += it }) { throw failure } }
            }

            reported shouldBe listOf(failure)
        }

        @Test
        fun `a cancellation is rethrown without a failure report`() {
            val reported = mutableListOf<Throwable>()

            shouldThrow<CancellationException> {
                runBlocking { reportOutcome(onFailure = { reported += it }) { throw CancellationException("left the screen") } }
            }

            reported shouldBe emptyList()
        }

        @Test
        fun `a success returns the result without a report`() {
            val reported = mutableListOf<Throwable>()

            runBlocking { reportOutcome(onFailure = { reported += it }) { 42 } } shouldBe 42
            reported shouldBe emptyList()
        }
    }

    private companion object {
        /** The registered [T], registering [create]'s when there is none yet. */
        inline fun <reified T : Any> injekted(create: () -> T): T =
            runCatching { Injekt.get<T>() }.getOrNull() ?: create().also { Injekt.addSingleton(it) }
    }
}
