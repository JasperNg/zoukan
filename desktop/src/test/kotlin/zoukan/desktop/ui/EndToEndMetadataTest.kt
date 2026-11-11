package zoukan.desktop.ui

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.source.service.SourcePreferences
import exh.metadata.metadata.EHentaiSearchMetadata
import exh.metadata.metadata.base.RaisedTag
import exh.source.EH_SOURCE_ID
import exh.source.ExhPreferences
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.interactor.InsertFlatMetadata
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.ZoukanRuntime
import zoukan.desktop.ui.common.DesktopPreferences
import zoukan.desktop.ui.manga.MangaScreen
import zoukan.desktop.ui.settings.SettingsCategory
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.minutes

/**
 * Chunk 7 offline: an E-Hentai entry with stored metadata shows the details block and its tags by
 * namespace, "More info" opens the metadata viewer, and Settings has the data saver. MangaDex
 * settings need the MangaDex extension, so they are not part of this offline test. Screenshots
 * go to `build/reports/e2e/m*.png`.
 *
 *   ./gradlew :desktop:test -Pzoukan.e2e=true --tests "*EndToEndMetadataTest"
 */
@OptIn(ExperimentalTestApi::class)
class EndToEndMetadataTest {

    private val shots = File("build/reports/e2e").apply { mkdirs() }

    @Test
    fun metadataViewerAndDataSaver() {
        assumeTrue(System.getProperty("zoukan.e2e") == "true", "End-to-end test; run with -Pzoukan.e2e=true")
        val dirs = ZoukanDirs(Files.createTempDirectory("zoukan-e2e-metadata").toFile())
        ZoukanRuntime.start(dirs, interactiveBrowser = false)
        startUi(dirs)
        val preferences = Injekt.get<DesktopPreferences>()
        var navigator: Navigator? = null

        // The built-in E-Hentai source, and an entry from it with its gallery metadata
        Injekt.get<ExhPreferences>().isHentaiEnabled().set(true)
        val sources = Injekt.get<SourceManager>()
        val deadline = System.currentTimeMillis() + 30_000
        while (sources.get(EH_SOURCE_ID) == null && System.currentTimeMillis() < deadline) Thread.sleep(100)
        val manga = runBlocking {
            val manga = Injekt.get<NetworkToLocalManga>()(
                Manga.create().copy(
                    url = "/g/123456/abcdef1234/",
                    ogTitle = "Metadata gallery",
                    source = EH_SOURCE_ID,
                    favorite = true,
                    initialized = true,
                    dateAdded = System.currentTimeMillis(),
                ),
            )
            Injekt.get<ChapterRepository>().addAll(
                listOf(Chapter.create().copy(mangaId = manga.id, url = manga.url, name = "Chapter", sourceOrder = 0)),
            )
            val meta = EHentaiSearchMetadata().apply {
                mangaId = manga.id
                gId = "123456"
                gToken = "abcdef1234"
                title = "Metadata gallery"
                genre = "doujinshi"
                uploader = "uploader-name"
                language = "english"
                translated = true
                size = 52_428_800
                length = 24
                favorites = 321
                averageRating = 4.5
                ratingCount = 100
                visible = "Yes"
                tags += RaisedTag("artist", "some artist", EHentaiSearchMetadata.TAG_TYPE_NORMAL)
                tags += RaisedTag("female", "glasses", EHentaiSearchMetadata.TAG_TYPE_LIGHT)
            }
            Injekt.get<InsertFlatMetadata>().await(meta)
            manga
        }

        runDesktopComposeUiTest(width = 1280, height = 820, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
            setContent { ZoukanContent(preferences) { navigator = it } }
            waitForIdle()

            step("Details block and namespace tags") {
                runOnUiThread { navigator?.push(MangaScreen(manga.id)) }
                waitFor(hasText("More info"))
                waitFor(hasText("uploader-name"))
                waitFor(hasText("artist"))
                waitFor(hasText("glasses"))
                onAllNodesWithText("glasses").onFirst().performScrollTo()
                shot("m1-manga-metadata")
            }

            step("Metadata viewer") {
                onAllNodesWithText("More info").onFirst().performScrollTo().performClick()
                waitFor(hasText("Gallery size"))
                waitFor(hasText("123456"))
                shot("m2-metadata-viewer")
                runOnUiThread { navigator?.popUntilRoot() }
            }

            step("Data saver settings") {
                runOnUiThread { navigator?.let { HomeScreen.openSettings(it) } }
                waitFor(hasText("Settings"))
                settingsPage(SettingsCategory.Data).performScrollToNode(hasText("Excluded sources"))
                shot("m3-data-saver")
                onAllNodesWithText("Compress images before downloading or loading in reader").onFirst().performClick()
                waitFor(hasText("wsrv.nl"))
                onAllNodesWithText("wsrv.nl").onFirst().performClick()
                waitForIdle()
                Injekt.get<SourcePreferences>().dataSaver().get() shouldBe SourcePreferences.DataSaver.WSRV_NL
                val url = exh.util.DataSaver(sources.get(EH_SOURCE_ID)!!, Injekt.get()).compress("https://example.org/a.png")
                url shouldBe "https://wsrv.nl/?url=https://example.org/a.png&output=webp&q=80"
                Injekt.get<SourcePreferences>().dataSaver().set(SourcePreferences.DataSaver.NONE)
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

    private fun ComposeUiTest.shot(name: String) {
        waitForIdle()
        Thread.sleep(300)
        waitForIdle()
        // A dialog is its own root: draw each root over the window at its place
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
