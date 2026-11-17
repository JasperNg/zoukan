package zoukan.desktop.e2e

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.data.backup.BackupCodec
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import eu.kanade.tachiyomi.data.backup.models.BackupTracking
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.floats.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import okio.sink
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.category.interactor.CreateCategoryWithName
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.e2eEffectContext
import zoukan.desktop.ui.settings.CreateBackupScreen
import zoukan.desktop.ui.settings.RestoreBackupScreen
import zoukan.desktop.ui.settings.backupStatusFlow
import zoukan.desktop.ui.settings.createBackup
import java.io.File
import kotlin.time.Duration.Companion.minutes

/**
 * Backs up a library and restores it: a title in a category, a chapter read to its middle, another
 * read through, and the source's settings. Then it takes all of that away and restores the backup
 * from the Restore backup screen, and checks the library shows the title again with its category,
 * progress and settings. Last, the backup screens' choices: Create backup's options (and that
 * nothing chosen means nothing to create), Restore backup for a backup that needs a source that
 * is not installed and a tracker that is not signed in, and a file that is not a backup.
 *
 * The backup is made with what Create backup runs once its file is chosen (the file chooser is a
 * native dialog a test cannot drive).
 *
 *   ./gradlew :desktop:journeyTest --tests "*BackupRestoreJourneyTest"
 */
@OptIn(ExperimentalTestApi::class)
class BackupRestoreJourneyTest {

    private lateinit var journey: Journey

    @AfterEach
    fun close() {
        if (::journey.isInitialized) journey.close()
    }

    @Test
    fun backUpAndRestoreTheLibrary() {
        journey = Journey.start("backup")
        val preferences = Injekt.get<DesktopPreferences>()
        val mangaRepository = Injekt.get<MangaRepository>()
        val chapterRepository = Injekt.get<ChapterRepository>()
        var navigator: Navigator? = null
        val backup = File(journey.dirs.backups, "journey.tachibk")

        val manga = journey.addToLibrary(FixtureCatalog.FORMATS_TITLE)
        val chapters = runBlocking { Injekt.get<GetChaptersByMangaId>().await(manga.id) }.sortedBy { it.chapterNumber }
        val shelf = journey.step("Put the title in a category, read a chapter and a half, change the source's settings") {
            runBlocking {
                Injekt.get<CreateCategoryWithName>().await(SHELF)
                val shelf = Injekt.get<GetCategories>().await().single { it.name == SHELF }
                Injekt.get<SetMangaCategories>().await(manga.id, listOf(shelf.id))
                chapterRepository.update(ChapterUpdate(id = chapters[0].id, read = true, lastPageRead = 2))
                chapterRepository.update(ChapterUpdate(id = chapters[1].id, lastPageRead = 1))
                shelf
            }.also {
                journey.source.getSourcePreferences().edit()
                    .putBoolean(FixtureSource.SHOW_BONUS, true)
                    .putString(FixtureSource.ORDER, "za")
                    .commit()
            }
        }

        with(journey) {
            step("Back up") {
                createBackup(backup, BackupOptions())
                // The status clears when the backup ends, written or not (a failure is a toast)
                val deadline = System.currentTimeMillis() + 60_000
                while (backupStatusFlow.value != null) {
                    check(System.currentTimeMillis() < deadline) { "the backup did not finish within 60s" }
                    Thread.sleep(100)
                }
                check(backup.isFile) { "the backup failed; see the toast above" }
            }

            step("Take it all away") {
                runBlocking {
                    mangaRepository.update(MangaUpdate(id = manga.id, favorite = false))
                    Injekt.get<SetMangaCategories>().await(manga.id, emptyList())
                    chapters.forEach { chapterRepository.update(ChapterUpdate(id = it.id, read = false, lastPageRead = 0)) }
                    Injekt.get<GetFavorites>().await().size shouldBe 0
                }
                source.getSourcePreferences().edit().clear().commit()
            }

            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
                setContent { ZoukanContent(preferences) { navigator = it } }
                waitForIdle()

                step("The library is empty") {
                    onAllNodesWithText("Library").onFirst().performClick()
                    waitForIdle()
                    onAllNodesWithTag("manga-item").fetchSemanticsNodes().size shouldBe 0
                }

                step("Restore the backup from the Restore backup screen") {
                    runOnUiThread { navigator?.push(RestoreBackupScreen(backup.absolutePath)) }
                    waitFor(hasTestTag("restore-backup-action") and isEnabled())
                    // The screen names the file the user picked
                    onAllNodes(hasText(backup.name, substring = true)).fetchSemanticsNodes().isNotEmpty() shouldBe true
                    // Not stretched across the window: a normal button, and options within the settings width
                    val button = onNodeWithTag("restore-backup-action").fetchSemanticsNode().boundsInRoot
                    withClue("the action button spans the window") { button.width shouldBeLessThan Journey.WIDTH / 2f }
                    val cap = with(density) { 900.dp.toPx() }
                    val content = onNodeWithTag("restore-backup-content").fetchSemanticsNode().boundsInRoot
                    withClue("the options are wider than the settings page") { content.width shouldBeLessThanOrEqual cap + 1f }
                    shot("1-restore")
                    onNodeWithTag("restore-backup-action").performClick()
                    // The action turns into Close once the restore ends
                    waitFor(hasTestTag("restore-backup-action") and hasText("Close"), timeout = 120_000)
                    shot("2-restored")
                    // The "Restored 1 entry" message must not cover the only way out of the screen
                    waitFor(hasText("Restored", substring = true) and hasAnyAncestor(hasTestTag("app-snackbar")))
                    val close = onNodeWithTag("restore-backup-action").fetchSemanticsNode().boundsInRoot
                    onAllNodes(hasAnyAncestor(hasTestTag("app-snackbar")) or hasTestTag("app-snackbar")).fetchSemanticsNodes().forEach { bar ->
                        withClue("the message covers Close") { bar.boundsInRoot.overlaps(close) shouldBe false }
                    }
                    onNodeWithTag("restore-backup-action").performClick()
                    waitOrDump("for the restore screen to close") {
                        onAllNodes(hasTestTag("restore-backup-action")).fetchSemanticsNodes().isEmpty()
                    }
                }

                step("Progress, category and settings are back") {
                    runBlocking {
                        Injekt.get<GetFavorites>().await().map { it.title } shouldContainExactly listOf(FixtureCatalog.FORMATS_TITLE)
                        Injekt.get<GetCategories>().await(manga.id).map { it.id } shouldContainExactly listOf(shelf.id)
                        val restored = Injekt.get<GetChaptersByMangaId>().await(manga.id).sortedBy { it.chapterNumber }
                        restored.map { it.read } shouldContainExactly listOf(true, false, false, false)
                        restored[1].lastPageRead shouldBe 1L
                    }
                    val prefs = source.getSourcePreferences()
                    prefs.getBoolean(FixtureSource.SHOW_BONUS, false) shouldBe true
                    prefs.getString(FixtureSource.ORDER, null) shouldBe "za"
                }

                step("The library shows the title again") {
                    waitFor(hasText("Library"))
                    onAllNodesWithText("Library").onFirst().performClick()
                    // With every entry in one category the library shows no category tabs
                    waitOrDump("for the restored title") {
                        onAllNodes(hasTestTag("manga-item")).fetchSemanticsNodes().size == 1
                    }
                    waitFor(hasText(FixtureCatalog.FORMATS_TITLE))
                    // A count of one reads "1 entry", never "1 entries" or "1 chapters"
                    val plainOne = SemanticsMatcher("shows \"1 entries\" or \"1 chapters\"") { node ->
                        node.config.getOrNull(SemanticsProperties.Text)
                            ?.any { Regex("""\b1 (entries|chapters)\b""").containsMatchIn(it.text) } == true
                    }
                    onAllNodes(plainOne).fetchSemanticsNodes().size shouldBe 0
                    onAllNodes(hasText("1 entry", substring = true)).fetchSemanticsNodes().isNotEmpty() shouldBe true
                    shot("3-library")
                }

                step("Backup and restore choices") {
                    // Create backup: what goes in the backup, as Android's CreateBackupScreen
                    runOnUiThread { navigator?.push(CreateBackupScreen) }
                    waitFor(hasTestTag("create-backup-action"))
                    // Let the screen finish coming in, so the clicks land where the options rest
                    waitForIdle()
                    shot("4-create-backup")
                    onNodeWithTag("create-backup-action").assertIsEnabled()
                    // The options' own list: the library behind the screen has a scrolling grid too
                    val backupOption = SemanticsMatcher("is a backup option") { node ->
                        node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("backup-option:") == true
                    }
                    listOf("Library entries", "Categories", "Saved Searches & Feeds", "App settings", "Extension stores", "Source settings")
                        .forEach { label ->
                            val list = onNode(hasScrollAction() and hasAnyDescendant(backupOption))
                            list.performScrollToNode(hasTestTag("backup-option:$label"))
                            onNodeWithTag("backup-option:$label").performClick()
                        }
                    // DEBUG
                    onAllNodes(hasAnyAncestor(backupOption) and SemanticsMatcher("toggle") { it.config.getOrNull(SemanticsProperties.ToggleableState) != null }, useUnmergedTree = true).fetchSemanticsNodes().forEach { n -> println("DEBUG ${n.config.getOrNull(SemanticsProperties.ToggleableState)} ${n.boundsInRoot}") }
                    onAllNodes(hasScrollAction()).fetchSemanticsNodes().forEach { n -> println("DEBUG scroll ${n.boundsInRoot}") }
                    // Nothing chosen: nothing to create
                    onNodeWithTag("create-backup-action").assertIsNotEnabled()
                    runOnUiThread { navigator?.pop() }

                    // Restore backup: a backup that needs a source that is not installed and a tracker
                    // that is not signed in shows both, as Android's RestoreBackupScreen
                    val file = File(dirs.root, "needs-more.tachibk")
                    val needsMore = Backup(
                        backupManga = listOf(
                            BackupManga(
                                source = 4242L,
                                url = "/ghost",
                                title = "Ghost",
                                tracking = listOf(BackupTracking(syncId = 2, libraryId = 0L)),
                            ),
                        ),
                        backupSources = listOf(BackupSource("Ghost Source", 4242L)),
                    )
                    file.sink().use { BackupCodec.write(needsMore, it) }
                    runOnUiThread { navigator?.push(RestoreBackupScreen(file.path)) }
                    waitFor(hasText("Missing sources:", substring = true))
                    waitFor(hasText("Ghost Source", substring = true))
                    waitFor(hasText("Trackers not logged into:", substring = true))
                    waitFor(hasText("AniList", substring = true))
                    shot("5-restore-needs-more")
                    onNodeWithTag("restore-backup-action").assertIsEnabled()
                    listOf("Library", "Categories", "App settings", "Extension stores", "Source settings", "Saved Searches & Feeds")
                        .forEach { label -> onNodeWithTag("backup-option:$label").performClick() }
                    onNodeWithTag("restore-backup-action").assertIsNotEnabled()
                    runOnUiThread { navigator?.pop() }

                    // A file that is not a backup cannot be restored
                    val broken = File(dirs.root, "broken.tachibk").apply { writeText("not a backup") }
                    runOnUiThread { navigator?.push(RestoreBackupScreen(broken.path)) }
                    waitFor(hasText("Invalid backup file:", substring = true))
                    onNodeWithTag("restore-backup-action").assertIsNotEnabled()
                    runOnUiThread { navigator?.pop() }
                }
            }
        }
    }

    private companion object {
        const val SHELF = "Journey Shelf"
    }
}
