package zoukan.desktop.ui.manga

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import eu.kanade.domain.manga.interactor.UpdateManga
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
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
import tachiyomi.i18n.kmk.KMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.get
import xyz.nulldev.androidcompat.io.sharedprefs.ForTestsOnly
import xyz.nulldev.androidcompat.io.sharedprefs.JavaSharedPreferences
import zoukan.desktop.compat.localized
import zoukan.desktop.manga.EditManga
import zoukan.desktop.ui.common.Images
import zoukan.i18n.desktop.DMR
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory

/**
 * The entry actions' dialogs that look their services up in Injekt: each gets a fake here, so the
 * test sees what the dialog's buttons do. Injekt is global and keeps the first registration, so
 * each fake is registered once for the JVM and its recordings are cleared before every test.
 */
@OptIn(ExperimentalTestApi::class, ForTestsOnly::class)
class EntryActionsDialogsTest {

    @BeforeEach
    fun setUp() {
        // The dialogs load covers through the app's image loader
        Images.init(createTempDirectory("entry-actions-covers").toFile())
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

    private companion object {
        /** The updates the fake repository behind the registered [UpdateManga] received. */
        val updates = CopyOnWriteArrayList<MangaUpdate>()

        val updateRecorder = mockk<MangaRepository> {
            coEvery { update(any()) } answers {
                updates += firstArg<MangaUpdate>()
                true
            }
        }

        /** The registered [T], registering [create]'s when there is none yet. */
        inline fun <reified T : Any> injekted(create: () -> T): T =
            runCatching { Injekt.get<T>() }.getOrNull() ?: create().also { Injekt.addSingleton(it) }
    }
}
