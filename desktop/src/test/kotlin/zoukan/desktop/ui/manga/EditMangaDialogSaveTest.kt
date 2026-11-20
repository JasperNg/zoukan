package zoukan.desktop.ui.manga

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.manga.interactor.GetCustomMangaInfo
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.i18n.action_notes
import tachiyomi.i18n.action_save
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.get
import zoukan.desktop.compat.localized
import zoukan.desktop.manga.EditManga
import zoukan.desktop.testing.FreshInjekt
import zoukan.desktop.ui.common.Images
import java.io.File

/** Save in the edit dialog writes the edited details and notes of a library entry, and Fill from tracker selection. */
@OptIn(ExperimentalTestApi::class)
class EditMangaDialogSaveTest {

    @JvmField
    @RegisterExtension
    val injekt = FreshInjekt()

    @TempDir
    lateinit var tempDir: File

    private lateinit var edit: EditManga

    @BeforeEach
    fun setUp() {
        Images.init(File(tempDir, "edit-manga-covers").apply { mkdirs() })
        // A library entry reads its custom info through Injekt
        injekted<GetCustomMangaInfo> { mockk { every { this@mockk.get(any()) } returns null } }
        // A spy: the dialog reads canEditCover as it is, the saves are recorded (and reach only mocks)
        edit = injekted { spyk(EditManga(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))) }
        clearMocks(edit, answers = false, recordedCalls = true, childMocks = false, verificationMarks = true, exclusionRules = false)
    }

    @Test
    fun `save writes the edited details and notes`() = runDesktopComposeUiTest {
        val manga = Manga.create().copy(id = 42, source = 1L, favorite = true, ogTitle = "Alpha", ogAuthor = "Someone", notes = "")
        setContent { EditMangaDialog(manga, onDismiss = {}) }

        onNode(hasSetTextAction() and hasText("Alpha")).performTextReplacement("Alpha Renamed")
        onNode(hasSetTextAction() and hasText(localized(MR.strings.action_notes))).performTextReplacement("Read on weekends")
        onNodeWithText(localized(MR.strings.action_save)).performClick()
        waitForIdle()

        coVerify(timeout = 5_000) {
            edit.saveInfo(manga, match { it.title == "Alpha Renamed" && it.author == "Someone" })
        }
        coVerify(timeout = 5_000) { edit.saveNotes(manga, "Read on weekends") }
    }

    /** Edit details > Fill from tracker: what it does with no, one and several trackers. */
    @Nested
    inner class TrackerFillSelection {

        @Test
        fun `an entry no tracker tracks says so`() {
            trackerFill(emptyList<String>()) shouldBe TrackerFill.NotTracked
        }

        @Test
        fun `one tracker fills the details at once`() {
            trackerFill(listOf("AniList")) shouldBe TrackerFill.Fill("AniList")
        }

        @Test
        fun `several trackers open the tracker select dialog with every one of them`() {
            trackerFill(listOf("AniList", "MyAnimeList")) shouldBe TrackerFill.Choose(listOf("AniList", "MyAnimeList"))
            trackerFill(listOf("AniList", "MyAnimeList", "Kitsu")) shouldBe TrackerFill.Choose(listOf("AniList", "MyAnimeList", "Kitsu"))
        }
    }

    private companion object {
        /** The registered [T], registering [create]'s when there is none yet (Injekt keeps the first). */
        inline fun <reified T : Any> injekted(create: () -> T): T =
            runCatching { Injekt.get<T>() }.getOrNull() ?: create().also { Injekt.addSingleton(it) }
    }
}
