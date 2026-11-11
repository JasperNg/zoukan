package zoukan.desktop.ui

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.manga.interactor.GetFavorites
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.ZoukanRuntime
import zoukan.desktop.ui.common.DesktopPreferences
import zoukan.desktop.ui.common.Toasts
import zoukan.extension.DesktopExtensionManager
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.minutes

/**
 * Finds, adds and reads a manga from an online source through the real UI: adds the Keiyoushi
 * store, installs an extension, browses its source, opens an entry, adds it to the library and
 * reads it in the paged and webtoon modes. It needs the network, so it runs only on request:
 *
 *   ./gradlew :desktop:test -Pzoukan.e2e=true --tests "*EndToEndReadTest"
 *
 * -Pzoukan.e2e.package=<pkg> picks another extension (default eu.kanade.tachiyomi.extension.en.mangapill, the full package name). Screenshots go to
 * desktop/build/reports/e2e/.
 */
@OptIn(ExperimentalTestApi::class)
class EndToEndReadTest {

    private val shots = File("build/reports/e2e").apply { mkdirs() }

    @Test
    fun findAddAndRead() {
        assumeTrue(System.getProperty("zoukan.e2e") == "true", "End-to-end test; run with -Pzoukan.e2e=true")
        val pkg = System.getProperty("zoukan.e2e.package") ?: "eu.kanade.tachiyomi.extension.en.mangapill"
        val dirs = ZoukanDirs(Files.createTempDirectory("zoukan-e2e").toFile())
        ZoukanRuntime.start(dirs, interactiveBrowser = false)
        startUi(dirs)
        val preferences = Injekt.get<DesktopPreferences>()
        // The source's grid, as before Android's "Replace latest button" (on by default) opened its feed
        Injekt.get<eu.kanade.domain.ui.UiPreferences>().useNewSourceNavigation().set(false)
        val extensions = Injekt.get<DesktopExtensionManager>()
        var navigator: Navigator? = null

        AppScope.launch { Toasts.flow.collect { println("E2E toast: $it") } }

        runDesktopComposeUiTest(width = 1280, height = 820, effectContext = e2eEffectContext(), testTimeout = 20.minutes) {
            setContent { ZoukanContent(preferences) { navigator = it } }

            step("Add the Keiyoushi store") {
                onAllNodesWithText("Browse").onFirst().performClick()
                onAllNodesWithText("Stores").onFirst().performClick()
                waitFor(hasText("Add Keiyoushi"))
                onAllNodesWithText("Add Keiyoushi").onFirst().performClick()
                waitFor(hasText("Keiyoushi"), timeout = 60_000) { onAllNodesWithText("Signing key", substring = true) }
            }

            step("Install $pkg") {
                onAllNodesWithText("Extensions", substring = true).onFirst().performClick()
                waitUntil(timeoutMillis = 120_000) { extensions.availableExtensionsFlow.value.containsKey(pkg) }
                onNodeWithTag("extension-search").performTextInput(pkg)
                waitFor(hasText("Install"))
                shot("1-extensions")
                onAllNodesWithText("Install").onFirst().performClick()
                waitUntil(timeoutMillis = 180_000) { extensions.installedExtensionsFlow.value.containsKey(pkg) }
            }

            val source = extensions.installedExtensionsFlow.value.getValue(pkg).sources.first()
            step("Browse ${source.name}") {
                onAllNodesWithText("Sources").onFirst().performClick()
                waitFor(hasText(source.name))
                onAllNodesWithText(source.name).onFirst().performClick()
                waitUntil(timeoutMillis = 120_000) { onAllNodesWithTag("manga-item").fetchSemanticsNodes().size >= 2 }
                waitForIdle()
                Thread.sleep(3000) // let a few covers load for the screenshot
                shot("2-source")
                onAllNodesWithTag("manga-item").onFirst().performClick()
            }

            step("Open the entry and add it to the library") {
                // The button turns on once the chapter list has loaded
                waitFor(hasText("Start") and isEnabled(), timeout = 120_000)
                onAllNodesWithText("Add to library").onFirst().performClick()
                waitFor(hasText("In library"))
                Thread.sleep(2000)
                shot("3-manga")
            }

            step("Read in the paged mode") {
                onAllNodesWithText("Start").onFirst().performClick()
                // The pager also composes the next page off screen, so wait for one on screen
                runCatching { waitUntil(timeoutMillis = 120_000) { pageOnScreen() } }.onFailure {
                    E2EDiagnostics.dump()
                    throw it
                }
                shot("4-reader-paged")
                // Right to left is the default: Left turns to the next page
                onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
                // A wide window shows double pages by default, as Android in landscape: the cover, then 2-3
                waitFor(hasText("2 / ", substring = true) or hasText("2-3 / ", substring = true), timeout = 60_000)
                waitUntil(timeoutMillis = 60_000) { pageOnScreen() }
                shot("5-reader-page-2")
            }

            step("Read in the webtoon mode") {
                onRoot().performKeyInput { pressKey(Key.M) }
                waitFor(hasText("Paged (right to left)"))
                onAllNodesWithText("Paged (right to left)").onFirst().performClick()
                waitFor(hasText("Webtoon"))
                onAllNodesWithText("Webtoon").onFirst().performClick()
                onRoot().performKeyInput { pressKey(Key.M) }
                waitUntil(timeoutMillis = 60_000) { onAllNodesWithTag("page-image").fetchSemanticsNodes().isNotEmpty() }
                onRoot().performKeyInput { pressKey(Key.DirectionDown) }
                Thread.sleep(1500)
                shot("6-reader-webtoon")
            }

            step("Back to the library and history") {
                runOnUiThread { navigator?.popUntilRoot() }
                onAllNodesWithText("Library").onFirst().performClick()
                waitUntil(timeoutMillis = 30_000) { onAllNodesWithTag("manga-item").fetchSemanticsNodes().size == 1 }
                shot("7-library")
                onAllNodesWithText("History").onFirst().performClick()
                waitFor(hasText("Chapter", substring = true))
                shot("8-history")
            }
        }

        runBlocking {
            val favorites = Injekt.get<GetFavorites>().await()
            favorites shouldHaveSize 1
            val chapters = Injekt.get<GetChaptersByMangaId>().await(favorites.single().id)
            chapters.any { it.lastPageRead > 0 } shouldBe true
            Injekt.get<GetHistory>().subscribe("", null, null, null).first().isNotEmpty() shouldBe true
        }
    }

    private fun step(name: String, block: () -> Unit) {
        println("E2E: $name")
        block()
    }

    private fun androidx.compose.ui.test.ComposeUiTest.waitFor(
        matcher: androidx.compose.ui.test.SemanticsMatcher,
        timeout: Long = 30_000,
        nodes: SemanticsNodeInteractionsProvider.() -> androidx.compose.ui.test.SemanticsNodeInteractionCollection = {
            onAllNodes(matcher)
        },
    ) {
        waitUntil(timeoutMillis = timeout) { nodes().fetchSemanticsNodes().isNotEmpty() }
    }

    /** A decoded page is drawn on screen: off-screen pages have no visible bounds. */
    private fun androidx.compose.ui.test.ComposeUiTest.pageOnScreen(): Boolean =
        onAllNodesWithTag("page-image").fetchSemanticsNodes().any { node ->
            val bounds = node.boundsInRoot
            bounds.width > 0f && bounds.height > 0f && bounds.right > 0f && bounds.left < 1280f
        }

    private fun androidx.compose.ui.test.ComposeUiTest.shot(name: String) {
        waitForIdle()
        val image = onRoot().captureToImage().toAwtImage()
        ImageIO.write(image, "png", File(shots, "$name.png"))
    }
}
