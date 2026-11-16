package zoukan.desktop.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.source.service.SourcePreferences
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.interactor.GetFeedSavedSearchGlobal
import tachiyomi.domain.source.interactor.InsertFeedSavedSearch
import tachiyomi.domain.source.model.FeedSavedSearch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.local.LocalSource
import zoukan.desktop.storage.ZoukanStorage
import zoukan.desktop.ui.HomeScreen
import zoukan.desktop.ui.HomeTab
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.browse.ExtensionDetailsScreen
import zoukan.desktop.ui.browse.GlobalSearchScreen
import zoukan.desktop.ui.browse.SourceScreen
import zoukan.desktop.ui.common.DesktopPreferences
import zoukan.desktop.ui.e2eEffectContext
import java.io.File
import kotlin.time.Duration.Companion.minutes

/**
 * The Komikku browse features through the real UI, offline: Migrate by source, bulk add from a
 * source (covers over HTTP), the source display modes, the global search filters, the feed order,
 * open from a URL, the sources search box and the extension details screen.
 *
 *   ./gradlew :desktop:journeyTest --tests "*BrowseFeaturesJourneyTest"
 */
@OptIn(ExperimentalTestApi::class)
class BrowseFeaturesJourneyTest {

    private lateinit var journey: Journey

    @AfterEach
    fun close() {
        if (::journey.isInitialized) journey.close()
    }

    @Test
    fun browseFeatures() {
        journey = Journey.start("browse-features")
        val preferences = Injekt.get<DesktopPreferences>()
        val storage = Injekt.get<ZoukanStorage>()
        var navigator: Navigator? = null

        // Two local entries for the display modes and the global search, not in the library
        listOf("Kilo", "Lima").forEach { File(storage.local, it).mkdirs() }
        // Two library entries of a source that is not installed, for Migrate
        val missingSource = 4242L
        runBlocking {
            Injekt.get<NetworkToLocalManga>()(
                listOf("Oscar", "Papa").map { Manga.create().copy(url = "/$it", ogTitle = it, source = missingSource) },
            ).forEach { manga ->
                Injekt.get<MangaRepository>().update(MangaUpdate(id = manga.id, favorite = true, dateAdded = System.currentTimeMillis()))
            }
            // Two global feeds, to reorder
            listOf(missingSource, missingSource + 1).forEach {
                Injekt.get<InsertFeedSavedSearch>().await(FeedSavedSearch(id = -1, source = it, savedSearch = null, global = true, feedOrder = 0))
            }
        }
        val catalogue = FixtureCatalog.manga.filter { !it.searchOnly && !it.bonus }

        with(journey) {
            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
                setContent { ZoukanContent(preferences) { navigator = it } }
                waitFor(hasText("Library"))

                step("Browse > Migrate: sources with library entries, then pick entries") {
                    runOnUiThread { HomeScreen.selectTab(HomeTab.Browse) }
                    onAllNodesWithText("Migrate").onFirst().performClick()
                    waitFor(hasTestTag("migrate-sources"))
                    waitFor(hasText("Not installed"))
                    shot("1-migrate-sources")
                    onAllNodesWithText("Not installed").onFirst().performClick()
                    waitFor(hasText("Oscar"))
                    byDescription("Select all").performClick()
                    waitFor(hasText("Migrate 2"))
                    shot("2-migrate-entries")
                    onAllNodesWithText("Migrate 2").onFirst().performClick()
                    waitFor(hasText("Select sources"))
                    runOnUiThread { navigator?.popUntilRoot() }
                }

                step("Bulk add from a source") {
                    runOnUiThread { navigator?.push(SourceScreen(source.id)) }
                    waitOrDump("for the catalogue titles") { onAllNodesWithTag("manga-item").fetchSemanticsNodes().size == catalogue.size }
                    byDescription("Select entries").performClick()
                    waitFor(hasTestTag("bulk-favorite-bar"))
                    onAllNodesWithTag("manga-item").onFirst().performMouseInput { rightClick() }
                    waitFor(hasText("1 selected"))
                    byDescription("Select all").performClick()
                    waitFor(hasText("${catalogue.size} selected"))
                    shot("3-bulk-selection")
                    onAllNodesWithText("Add to library").onFirst().performClick()
                    waitOrDump("for the titles to become favourites", 10_000) {
                        runBlocking { Injekt.get<GetFavorites>().await() }.count { it.source == source.id } == catalogue.size
                    }
                    waitOrDump("for the selection bar to close", 10_000) { onAllNodesWithTag("bulk-favorite-bar").fetchSemanticsNodes().isEmpty() }
                    runBlocking { Injekt.get<GetFavorites>().await() }.filter { it.source == source.id }.map { it.title }.toSet() shouldBe
                        catalogue.map { it.title }.toSet()
                    // The grid drew the covers, which came over HTTP
                    catalogue.forEach { server.imageRequests(it.cover) shouldBeGreaterThan 0 }
                    runOnUiThread { navigator?.popUntilRoot() }
                }

                step("Source display modes: list, then the comfortable grid") {
                    val displayMode = Injekt.get<SourcePreferences>().sourceDisplayMode()
                    runOnUiThread { navigator?.push(SourceScreen(LocalSource.ID)) }
                    waitOrDump("for the two local entries") { onAllNodesWithTag("manga-item").fetchSemanticsNodes().size == 2 }
                    byDescription("Display mode").performClick()
                    shot("4-display-mode-menu")
                    onAllNodesWithText("List").onFirst().performClick()
                    waitOrDump("for the list mode", 10_000) { displayMode.get() == LibraryDisplayMode.List }
                    waitOrDump("for the entries in the list", 10_000) { onAllNodesWithTag("manga-item").fetchSemanticsNodes().size == 2 }
                    shot("5-source-list")
                    byDescription("Display mode").performClick()
                    onAllNodesWithText("Comfortable grid").onFirst().performClick()
                    waitOrDump("for the comfortable grid mode", 10_000) { displayMode.get() == LibraryDisplayMode.ComfortableGrid }
                    waitFor(hasText("Kilo"))
                    shot("6-source-comfortable-grid")
                    displayMode.set(LibraryDisplayMode.default)
                    runOnUiThread { navigator?.popUntilRoot() }
                }

                step("Global search filters") {
                    val filter = Injekt.get<SourcePreferences>().globalSearchFilterState()
                    filter.set(false)
                    runOnUiThread { navigator?.push(GlobalSearchScreen("Kilo")) }
                    waitFor(hasTestTag("global-search-has-results"))
                    waitFor(hasText("Local source"))
                    // The fixture source answers too, with nothing for "Kilo": its row shows until the filter hides it
                    waitFor(hasText(source.name))
                    onNodeWithTag("global-search-has-results").performClick()
                    waitOrDump("for the filter to be on", 10_000) { filter.get() }
                    waitOrDump("for the filter to hide the source without results", 10_000) {
                        onAllNodesWithText(source.name).fetchSemanticsNodes().isEmpty()
                    }
                    waitFor(hasText("Local source"))
                    shot("7-global-search-filters")
                    onNodeWithTag("global-search-has-results").performClick()
                    waitOrDump("for the filter to be off", 10_000) { !filter.get() }
                    runOnUiThread { navigator?.popUntilRoot() }
                }

                step("Feed order") {
                    runOnUiThread { HomeScreen.selectTab(HomeTab.Browse) }
                    onAllNodesWithText("Feed").onFirst().performClick()
                    byDescription("Reorder the feed").performClick()
                    waitFor(hasTestTag("feed-order"))
                    shot("8-feed-order")
                    byDescription("Move down").performClick()
                    waitOrDump("for the stored feed order", 10_000) {
                        runBlocking { Injekt.get<GetFeedSavedSearchGlobal>().await() }.first().source == missingSource + 1
                    }
                    runBlocking { Injekt.get<GetFeedSavedSearchGlobal>().await() }.map { it.source } shouldBe
                        listOf(missingSource + 1, missingSource)
                    // On screen the moved feed is now below the other
                    val first = onAllNodesWithText("${missingSource + 1}", substring = true).onFirst().fetchSemanticsNode().boundsInRoot.top
                    val second = onAllNodesWithText("$missingSource", substring = true).onFirst().fetchSemanticsNode().boundsInRoot.top
                    first shouldBeLessThan second
                    shot("9-feed-order-moved")
                    onAllNodesWithText("Done").onFirst().performClick()
                }

                step("Open from a URL: no source knows it, so it becomes a global search") {
                    onAllNodesWithText("Sources").onFirst().performClick()
                    byDescription("Open from a URL").performClick()
                    onNodeWithTag("open-url-field").performTextInput("https://example.org/title/123")
                    shot("10-open-url")
                    onAllNodesWithText("Open").onFirst().performClick()
                    waitFor(hasText("Search: https://example.org/title/123"))
                    runOnUiThread { navigator?.popUntilRoot() }
                }

                step("Sources search box (KMK): by name, comma-separated terms") {
                    runOnUiThread { HomeScreen.selectTab(HomeTab.Browse) }
                    onAllNodesWithText("Sources").onFirst().performClick()
                    waitFor(hasTestTag("source-search"))
                    waitFor(hasText("Local source"))
                    onNodeWithTag("source-search").performTextInput("no such source")
                    waitOrDump("for the search to hide the local source", 10_000) { onAllNodesWithText("Local source").fetchSemanticsNodes().isEmpty() }
                    shot("11-source-search-none")
                    onNodeWithTag("source-search").performTextClearance()
                    onNodeWithTag("source-search").performTextInput("nothing, LOCAL")
                    waitFor(hasText("Local source"))
                    shot("12-source-search-match")
                    onNodeWithTag("source-search").performTextClearance()
                }

                step("Extension details of a missing extension") {
                    runOnUiThread { navigator?.push(ExtensionDetailsScreen("eu.kanade.tachiyomi.extension.en.missing")) }
                    waitFor(hasText("This extension is not installed."))
                    shot("13-extension-missing")
                    runOnUiThread { navigator?.popUntilRoot() }
                }
            }
        }
        Injekt.get<SourcePreferences>().globalSearchFilterState().get() shouldBe false
    }
}
