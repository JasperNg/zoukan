package zoukan.desktop.ui

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
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
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
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
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.ZoukanRuntime
import zoukan.desktop.local.LocalSource
import zoukan.desktop.storage.ZoukanStorage
import zoukan.desktop.ui.browse.ExtensionDetailsScreen
import zoukan.desktop.ui.browse.GlobalSearchScreen
import zoukan.desktop.ui.browse.SourceScreen
import zoukan.desktop.ui.common.DesktopPreferences
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.minutes

/**
 * The Komikku browse features through the real UI, offline: Migrate by source, bulk add from a
 * source, the global search filters, the feed order, open from a URL and the extension details
 * screen. Screenshots go to `build/reports/e2e/b*.png`.
 *
 *   ./gradlew :desktop:test -Pzoukan.e2e=true --tests "*EndToEndBrowseTest"
 */
@OptIn(ExperimentalTestApi::class)
class EndToEndBrowseTest {

    private val shots = File("build/reports/e2e").apply { mkdirs() }

    @Test
    fun browse() {
        assumeTrue(System.getProperty("zoukan.e2e") == "true", "End-to-end test; run with -Pzoukan.e2e=true")
        val dirs = ZoukanDirs(Files.createTempDirectory("zoukan-e2e-browse").toFile())
        ZoukanRuntime.start(dirs, interactiveBrowser = false)
        startUi(dirs)
        val preferences = Injekt.get<DesktopPreferences>()
        val storage = Injekt.get<ZoukanStorage>()
        var navigator: Navigator? = null

        // Two local entries to add from the local source, not yet in the library
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

        runDesktopComposeUiTest(width = 1280, height = 820, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
            setContent { ZoukanContent(preferences) { navigator = it } }
            waitFor(hasText("Library"))

            step("Browse > Migrate: sources with library entries, then pick entries") {
                runOnUiThread { HomeScreen.selectTab(HomeTab.Browse) }
                onAllNodesWithText("Migrate").onFirst().performClick()
                waitFor(hasTestTag("migrate-sources"))
                waitFor(hasText("Not installed"))
                shot("b1-migrate-sources")
                onAllNodesWithText("Not installed").onFirst().performClick()
                waitFor(hasText("Oscar"))
                byDescription("Select all").performClick()
                waitFor(hasText("Migrate 2"))
                shot("b2-migrate-entries")
                onAllNodesWithText("Migrate 2").onFirst().performClick()
                waitFor(hasText("Select sources"))
                runOnUiThread { navigator?.popUntilRoot() }
            }

            step("Bulk add from a source") {
                runOnUiThread { navigator?.push(SourceScreen(LocalSource.ID)) }
                waitUntil(timeoutMillis = 30_000) { onAllNodesWithTag("manga-item").fetchSemanticsNodes().size == 2 }
                byDescription("Select entries").performClick()
                waitFor(hasTestTag("bulk-favorite-bar"))
                onAllNodesWithTag("manga-item").onFirst().performMouseInput { rightClick() }
                waitFor(hasText("1 selected"))
                byDescription("Select all").performClick()
                waitFor(hasText("2 selected"))
                shot("b3-bulk-selection")
                onAllNodesWithText("Add to library").onFirst().performClick()
                waitUntil(timeoutMillis = 10_000) {
                    runBlocking { Injekt.get<GetFavorites>().await() }.count { it.source == LocalSource.ID } == 2
                }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("bulk-favorite-bar").fetchSemanticsNodes().isEmpty() }
                runOnUiThread { navigator?.popUntilRoot() }
            }

            step("Source display modes: list, then the comfortable grid") {
                val displayMode = Injekt.get<SourcePreferences>().sourceDisplayMode()
                runOnUiThread { navigator?.push(SourceScreen(LocalSource.ID)) }
                waitUntil(timeoutMillis = 30_000) { onAllNodesWithTag("manga-item").fetchSemanticsNodes().size == 2 }
                byDescription("Display mode").performClick()
                onAllNodesWithText("List").onFirst().performClick()
                waitUntil(timeoutMillis = 10_000) { displayMode.get() == LibraryDisplayMode.List }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("manga-item").fetchSemanticsNodes().size == 2 }
                shot("b3a-source-list")
                byDescription("Display mode").performClick()
                onAllNodesWithText("Comfortable grid").onFirst().performClick()
                waitUntil(timeoutMillis = 10_000) { displayMode.get() == LibraryDisplayMode.ComfortableGrid }
                waitFor(hasText("Kilo"))
                shot("b3b-source-comfortable-grid")
                displayMode.set(LibraryDisplayMode.default)
                runOnUiThread { navigator?.popUntilRoot() }
            }

            step("Global search filters") {
                Injekt.get<SourcePreferences>().globalSearchFilterState().set(false)
                runOnUiThread { navigator?.push(GlobalSearchScreen("Kilo")) }
                waitFor(hasTestTag("global-search-has-results"))
                waitFor(hasText("Local source"))
                onNodeWithTag("global-search-has-results").performClick()
                waitUntil(timeoutMillis = 10_000) { Injekt.get<SourcePreferences>().globalSearchFilterState().get() }
                shot("b4-global-search-filters")
                onNodeWithTag("global-search-has-results").performClick()
                runOnUiThread { navigator?.popUntilRoot() }
            }

            step("Feed order") {
                runOnUiThread { HomeScreen.selectTab(HomeTab.Browse) }
                onAllNodesWithText("Feed").onFirst().performClick()
                byDescription("Reorder the feed").performClick()
                waitFor(hasTestTag("feed-order"))
                shot("b5-feed-order")
                byDescription("Move down").performClick()
                waitUntil(timeoutMillis = 10_000) {
                    runBlocking { Injekt.get<GetFeedSavedSearchGlobal>().await() }.first().source == missingSource + 1
                }
                onAllNodesWithText("Done").onFirst().performClick()
            }

            step("Open from a URL: no source knows it, so it becomes a global search") {
                onAllNodesWithText("Sources").onFirst().performClick()
                byDescription("Open from a URL").performClick()
                onNodeWithTag("open-url-field").performTextInput("https://example.org/title/123")
                shot("b6-open-url")
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
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Local source").fetchSemanticsNodes().isEmpty() }
                shot("b7-source-search-none")
                onNodeWithTag("source-search").performTextClearance()
                onNodeWithTag("source-search").performTextInput("nothing, LOCAL")
                waitFor(hasText("Local source"))
                shot("b8-source-search-match")
                onNodeWithTag("source-search").performTextClearance()
            }

            step("Extension details of a missing extension") {
                runOnUiThread { navigator?.push(ExtensionDetailsScreen("eu.kanade.tachiyomi.extension.en.missing")) }
                waitFor(hasText("This extension is not installed."))
                runOnUiThread { navigator?.popUntilRoot() }
            }
        }
        Injekt.get<SourcePreferences>().globalSearchFilterState().get() shouldBe false
    }

    private fun ComposeUiTest.byDescription(text: String) =
        onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(text))).onFirst()

    private fun step(name: String, block: () -> Unit) {
        println("E2E: $name")
        block()
    }

    private fun ComposeUiTest.waitFor(matcher: SemanticsMatcher, timeout: Long = 30_000) {
        waitUntil(timeoutMillis = timeout) { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ComposeUiTest.shot(name: String) {
        waitForIdle()
        Thread.sleep(300)
        waitForIdle()
        val roots = onAllNodes(isRoot())
        val count = roots.fetchSemanticsNodes().size
        val image = roots[0].captureToImage().toAwtImage()
        val g = image.createGraphics()
        (1 until count).forEach { i ->
            val node = roots[i]
            val bounds = node.fetchSemanticsNode().boundsInWindow
            g.drawImage(node.captureToImage().toAwtImage(), bounds.left.toInt(), bounds.top.toInt(), null)
        }
        g.dispose()
        ImageIO.write(image, "png", File(shots, "$name.png"))
    }
}
