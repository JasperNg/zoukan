package zoukan.desktop.ui

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetFavorites
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.ZoukanRuntime
import zoukan.desktop.download.CbzWriter
import zoukan.desktop.download.ComicInfoXml
import zoukan.desktop.library.LibraryUpdater
import zoukan.desktop.storage.ZoukanStorage
import zoukan.desktop.ui.common.DesktopPreferences
import zoukan.desktop.ui.common.Toasts
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.minutes

/**
 * The offline half of the Phase 5 checks, through the real UI and with no network: browses the
 * local source, adds an entry to the library, updates the library from the Library tab, picks up a
 * chapter added on disk, reads it, and opens the Downloads screen and the new settings.
 *
 *   ./gradlew :desktop:test -Pzoukan.e2e=true --tests "*EndToEndLocalTest"
 */
@OptIn(ExperimentalTestApi::class)
class EndToEndLocalTest {

    private val shots = File("build/reports/e2e").apply { mkdirs() }

    @Test
    fun localSourceLibraryUpdateAndRead() {
        assumeTrue(System.getProperty("zoukan.e2e") == "true", "End-to-end test; run with -Pzoukan.e2e=true")
        val dirs = ZoukanDirs(Files.createTempDirectory("zoukan-e2e-local").toFile())
        ZoukanRuntime.start(dirs, interactiveBrowser = false)
        startUi(dirs)
        val preferences = Injekt.get<DesktopPreferences>()
        val storage = Injekt.get<ZoukanStorage>()
        val updater = Injekt.get<LibraryUpdater>()
        var navigator: Navigator? = null
        AppScope.launch { Toasts.flow.collect { println("E2E toast: $it") } }

        val mangaDir = File(storage.local, "Local Test Manga").apply { mkdirs() }
        File(mangaDir, ComicInfoXml.FILE).writeText(
            ComicInfoXml(mapOf("Series" to "Local Test Manga", "Writer" to "Zoukan", "Summary" to "Made by the end-to-end test")).write(),
        )
        cbz(File(mangaDir, "Chapter 1.cbz"), 4)
        Injekt.get<LibraryPreferences>().autoUpdateMangaRestrictions().set(emptySet())

        runDesktopComposeUiTest(width = 1280, height = 820, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
            setContent { ZoukanContent(preferences) { navigator = it } }

            step("Open the local entry and add it to the library") {
                onAllNodesWithText("Browse").onFirst().performClick()
                onAllNodesWithText("Sources").onFirst().performClick()
                waitFor(hasText("Local source"))
                onAllNodesWithText("Local source").onFirst().performScrollTo().performClick()
                waitUntil(timeoutMillis = 30_000) { onAllNodesWithTag("manga-item").fetchSemanticsNodes().isNotEmpty() }
                shot("l1-local-source")
                onAllNodesWithTag("manga-item").onFirst().performClick()
                waitFor(hasText("Start") and isEnabled())
                waitFor(hasText("Made by the end-to-end test"))
                onAllNodesWithText("Add to library").onFirst().performClick()
                waitFor(hasText("In library"))
                shot("l2-manga")
                runOnUiThread { navigator?.popUntilRoot() }
            }

            step("Add a chapter on disk and update the library") {
                cbz(File(mangaDir, "Chapter 2.cbz"), 5)
                onAllNodesWithText("Library").onFirst().performClick()
                waitUntil(timeoutMillis = 30_000) { onAllNodesWithTag("manga-item").fetchSemanticsNodes().size == 1 }
                onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Update library")))
                    .onFirst().performClick()
                waitUntil(timeoutMillis = 60_000) { updater.lastResult.value != null && updater.progress.value == null }
                val result = updater.lastResult.value!!
                result.failures.shouldBeEmpty()
                result.updated shouldBe 1
                result.newChapters shouldBe 1
                val manga = runBlocking { Injekt.get<GetFavorites>().await().single() }
                runBlocking { Injekt.get<GetChaptersByMangaId>().await(manga.id).size } shouldBe 2
                shot("l3-library")
            }

            step("Read the new chapter") {
                onAllNodesWithText("Updates").onFirst().performClick()
                waitFor(hasText("Chapter 2"))
                shot("l4-updates")
                onAllNodesWithText("Chapter 2").onFirst().performClick()
                waitUntil(timeoutMillis = 30_000) { pageOnScreen() }
                onRoot().performKeyInput { pressKey(Key.M) }
                waitFor(hasText("Local file", substring = true))
                shot("l5-reader")
                runOnUiThread { navigator?.popUntilRoot() }
            }

            step("Downloads screen and settings") {
                onAllNodesWithText("Library").onFirst().performClick()
                onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Downloads")))
                    .onFirst().performClick()
                waitFor(hasText("No downloads queued", substring = true))
                shot("l6-downloads")
                runOnUiThread { navigator?.popUntilRoot() }
                onAllNodesWithText("Settings").onFirst().performClick()
                waitFor(hasText("App theme"))
                shot("l7-settings")
            }
        }
    }

    private fun cbz(file: File, pages: Int) {
        CbzWriter(file).use { writer ->
            (1..pages).forEach { i ->
                val image = BufferedImage(600, 900, BufferedImage.TYPE_INT_RGB)
                val g = image.createGraphics()
                g.color = java.awt.Color(30 * i, 90, 160)
                g.fillRect(0, 0, 600, 900)
                g.color = java.awt.Color.WHITE
                g.font = g.font.deriveFont(120f)
                g.drawString("$i", 260, 480)
                g.dispose()
                writer.write("%03d.png".format(i), ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray())
            }
        }
    }

    private fun step(name: String, block: () -> Unit) {
        println("E2E: $name")
        block()
    }

    private fun ComposeUiTest.waitFor(matcher: SemanticsMatcher, timeout: Long = 30_000) {
        waitUntil(timeoutMillis = timeout) { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ComposeUiTest.pageOnScreen(): Boolean =
        onAllNodesWithTag("page-image").fetchSemanticsNodes().any { node ->
            val bounds = node.boundsInRoot
            bounds.width > 0f && bounds.height > 0f && bounds.right > 0f && bounds.left < 1280f
        }

    private fun ComposeUiTest.shot(name: String) {
        waitForIdle()
        val image = onRoot().captureToImage().toAwtImage()
        ImageIO.write(image, "png", File(shots, "$name.png"))
    }
}
