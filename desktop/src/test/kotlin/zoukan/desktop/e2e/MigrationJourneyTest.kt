package zoukan.desktop.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.source.service.SourcePreferences
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import mihon.domain.migration.models.MigrationFlag
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.local.LocalSource
import zoukan.desktop.migration.MigrateManga
import zoukan.desktop.storage.ZoukanStorage
import zoukan.desktop.ui.HomeScreen
import zoukan.desktop.ui.HomeTab
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.e2eEffectContext
import zoukan.desktop.ui.manga.MangaScreen
import kotlin.time.Duration.Companion.minutes

/**
 * Bulk migration and batch add through the real UI, offline: two library entries migrate from
 * their sources to a second fixture source that has one of them, and batch add reports an address
 * no source knows. The entry the target has moves, with the chapters it lists over HTTP; the local
 * entry it cannot find stays where it is. Then the moved entry migrates back from its own page: the
 * pick search, the Migrate dialog with Notes unticked, and what came along.
 *
 *   ./gradlew :desktop:journeyTest --tests "*MigrationJourneyTest"
 */
@OptIn(ExperimentalTestApi::class)
class MigrationJourneyTest {

    private lateinit var journey: Journey

    @AfterEach
    fun close() {
        if (::journey.isInitialized) journey.close()
    }

    @Test
    fun bulkMigrationAndBatchAdd() {
        journey = Journey.start("migration")
        val preferences = Injekt.get<DesktopPreferences>()
        val storage = Injekt.get<ZoukanStorage>()
        // The target is appended to the installed sources: the journey's own source stays
        val target = journey.addSource()
        val title = FixtureCatalog.FORMATS_TITLE
        val targetChapters = FixtureCatalog.manga.first { it.title == title }.chapters.size

        Injekt.get<LibraryPreferences>().autoUpdateMangaRestrictions().set(emptySet())
        // The fixture title is on the journey's source and found on the target; Bravo is local only
        journey.addToLibrary(title)
        LocalFixtures.localEntry(storage, "Bravo", chapters = 2)
        LocalFixtures.addLocalToLibrary()
        val before = runBlocking { Injekt.get<GetFavorites>().await() }
        before.map { it.title to it.source }.toSet() shouldBe setOf(title to journey.source.id, "Bravo" to LocalSource.ID)
        runBlocking { Injekt.get<GetChaptersByMangaId>().await(before.first { it.title == "Bravo" }.id) }.size shouldBe 2
        val fixtureBefore = before.first { it.title == title }
        runBlocking { Injekt.get<GetChaptersByMangaId>().await(fixtureBefore.id) }.size shouldBe targetChapters

        with(journey) {
            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 15.minutes) {
                var navigator: Navigator? = null
                setContent { ZoukanContent(preferences) { navigator = it } }

                step("Select both entries and open the migration") {
                    waitOrDump("for both library entries") { onAllNodesWithTag("manga-item").fetchSemanticsNodes().size == 2 }
                    // A right click opens the entry's menu; Select there starts the selection
                    onAllNodesWithText(title).onFirst().performMouseInput { rightClick() }
                    waitFor(hasText("Select"))
                    onAllNodesWithText("Select").onFirst().performClick()
                    waitFor(hasText("1 selected"))
                    onAllNodesWithText("Bravo").onFirst().performClick()
                    waitFor(hasText("2 selected"))
                    byDescription("Migrate").performClick()
                    waitFor(hasText("Select sources"))
                    waitFor(hasText(target.name))
                    // Every source is selected: take the journey's own off, so the target is the only one searched
                    onAllNodesWithText(source.name).onFirst().performClick()
                    waitOrDump("for the target to be the only source selected") {
                        Injekt.get<SourcePreferences>().migrationSources().get() == listOf(target.id)
                    }
                    shot("1-migration-sources")
                }

                step("Search the chosen sources") {
                    onAllNodesWithText("Start").onFirst().performClick()
                    waitFor(hasText("Migration (2/2)"), 60_000)
                    waitFor(hasText("No alternatives found"))
                    waitOrDump("for the match's chapter count", 30_000) {
                        onAllNodesWithText("$targetChapters chapters", substring = true).fetchSemanticsNodes().isNotEmpty()
                    }
                    shot("2-migration-list")
                }

                step("Migrate the match") {
                    val detailRequests = server.requests.count { it.startsWith("/manga/") }
                    byDescription("Migrate").performClick()
                    waitFor(hasText("Migrate entries?"))
                    shot("3-migrate-dialog")
                    onAllNodesWithText("Migrate").onLast().performClick()
                    waitOrDump("for the match to become a favourite of the target", 60_000) {
                        runBlocking { Injekt.get<GetFavorites>().await() }.any { it.source == target.id }
                    }
                    val favorites = runBlocking { Injekt.get<GetFavorites>().await() }
                    // The match moved to the target; Bravo, without a match, stays on the local source
                    favorites.map { it.title to it.source }.toSet() shouldBe setOf(title to target.id, "Bravo" to LocalSource.ID)
                    // The old entry on the journey's source is no longer a favourite
                    favorites.none { it.source == this@with.source.id } shouldBe true
                    val migrated = favorites.first { it.source == target.id }
                    runBlocking { Injekt.get<GetChaptersByMangaId>().await(migrated.id) }.size shouldBe targetChapters
                    // The target's details and chapter list came over HTTP
                    server.requests.count { it.startsWith("/manga/") } shouldBeGreaterThan detailRequests
                    server.requests shouldContain "/manga/formats"
                    // One safety backup for the batch, in the data folder, before anything moved
                    zoukan.desktop.backup.SafetySnapshot.folder(dirs).listFiles().orEmpty()
                        .count { it.name.startsWith("safety_migration_") } shouldBe 1
                    waitOrDump("for the library to list both entries", 10_000) {
                        onAllNodesWithTag("manga-item").fetchSemanticsNodes().size == 2
                    }
                    shot("4-library-after-migration")
                }

                step("Migrate one entry from its page: pick it, untick Notes, migrate") {
                    val onTarget = runBlocking { Injekt.get<GetFavorites>().await() }.single { it.source == target.id }
                    val chapterRepository = Injekt.get<ChapterRepository>()
                    val firstOnTarget = runBlocking { chapterRepository.getChapterByMangaId(onTarget.id) }.minBy { it.chapterNumber }
                    runBlocking {
                        chapterRepository.update(ChapterUpdate(id = firstOnTarget.id, read = true))
                        Injekt.get<MangaRepository>().update(MangaUpdate(id = onTarget.id, notes = "Stays behind"))
                    }
                    runOnUiThread { navigator?.push(MangaScreen(onTarget.id)) }
                    waitFor(hasText("Stays behind"))
                    byDescription("More").performClick()
                    onAllNodesWithText("Migrate to another source").onFirst().performClick()
                    // The search leaves out the entry's own source: the journey's source is the only row
                    waitOrDump("for the title on the journey's source") { onAllNodesWithTag("manga-item").fetchSemanticsNodes().size == 1 }
                    shot("5-migrate-pick")
                    onAllNodesWithTag("manga-item").onFirst().performClick()
                    waitFor(hasText("Migrate to $title?"))
                    onAllNodesWithText("Notes").onLast().performClick()
                    shot("6-migrate-pick-dialog")
                    onAllNodesWithText("Migrate").onLast().performClick()
                    waitOrDump("for the entry to move back to the journey's source", 60_000) {
                        runBlocking { Injekt.get<GetFavorites>().await() }.any { it.source == this@with.source.id && it.title == title }
                    }
                    val favorites = runBlocking { Injekt.get<GetFavorites>().await() }
                    favorites.map { it.title to it.source }.toSet() shouldBe setOf(title to this@with.source.id, "Bravo" to LocalSource.ID)
                    val moved = favorites.single { it.source == this@with.source.id }
                    // Notes stayed behind, the read chapter came along, and the choice is remembered
                    moved.notes shouldBe ""
                    runBlocking { chapterRepository.getChapterByMangaId(moved.id) }.minBy { it.chapterNumber }.read shouldBe true
                    (MigrationFlag.NOTES in Injekt.get<MigrateManga>().flags().get()) shouldBe false
                    // The page of the entry it moved to replaces the old one's
                    waitOrDump("for the new entry's page", 10_000) { (navigator?.lastItem as? MangaScreen)?.mangaId == moved.id }
                    runOnUiThread { navigator?.popUntilRoot() }
                }

                step("Batch add an address no source knows") {
                    runOnUiThread { HomeScreen.selectTab(HomeTab.Browse) }
                    waitFor(hasText("Browse"))
                    byDescription("Batch add").performClick()
                    waitFor(hasText("Add galleries"))
                    onNodeWithTag("batch-add-input").performTextInput("https://unknown.invalid/g/1/abc")
                    shot("7-batch-add-input")
                    onAllNodesWithText("Add galleries").onFirst().performClick()
                    waitFor(hasText("Finish"), 60_000)
                    waitFor(hasText("[ERROR]", substring = true))
                    shot("8-batch-add-result")
                }
            }
        }
    }
}
