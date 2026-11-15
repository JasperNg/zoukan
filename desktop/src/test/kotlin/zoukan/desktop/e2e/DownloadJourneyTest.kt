package zoukan.desktop.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.common.DesktopPreferences
import zoukan.desktop.ui.e2eEffectContext
import zoukan.desktop.ui.manga.MangaScreen
import java.io.File
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.minutes

/**
 * Downloads chapters from a title's screen, checks the files hold every page, decodable and the
 * right picture, then stops the fixture server and reads a download offline.
 *
 *   ./gradlew :desktop:journeyTest --tests "*DownloadJourneyTest"
 */
@OptIn(ExperimentalTestApi::class)
class DownloadJourneyTest {

    private lateinit var journey: Journey

    @AfterEach
    fun close() {
        if (::journey.isInitialized) journey.close()
    }

    @Test
    fun downloadChaptersAndReadThemOffline() {
        journey = Journey.start("download")
        val preferences = Injekt.get<DesktopPreferences>()
        val downloads = Injekt.get<DownloadManager>()
        var navigator: Navigator? = null
        val manga = journey.addToLibrary(FixtureCatalog.FORMATS_TITLE)
        // JPEG, PNG and WebP: the first three chapters, the order "Next chapter" downloads them in
        val formats = FixtureCatalog.formats.take(3)
        fun chapters(): List<Chapter> = runBlocking { Injekt.get<GetChaptersByMangaId>().await(manga.id) }.sortedBy { it.chapterNumber }

        with(journey) {
            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
                setContent { ZoukanContent(preferences) { navigator = it } }
                waitForIdle()
                runOnUiThread { navigator?.push(MangaScreen(manga.id)) }
                waitFor(hasText("Start") and isEnabled())

                formats.forEachIndexed { i, format ->
                    step("Download the next chapter: chapter ${i + 1} (${format.uppercase()})") {
                        onAllNodes(hasContentDescription("Download")).onFirst().performClick()
                        waitFor(hasText("Next chapter"))
                        onAllNodesWithText("Next chapter").onFirst().performClick()
                        waitOrDump("for the download to finish", timeout = 120_000) {
                            downloads.queue.value.isEmpty() && !downloads.running.value &&
                                downloads.downloadedIds(manga, chapters()).size == i + 1
                        }
                    }
                }
                shot("1-downloaded")

                step("The downloads hold every page, each the right picture") {
                    formats.forEachIndexed { i, format ->
                        val file = downloads.findChapter(manga, chapters()[i]).shouldNotBeNull()
                        val images = pageImages(file)
                        val pages = FixtureCatalog.pages(format)
                        images shouldHaveSize pages.size
                        images.zip(pages).forEach { (bytes, page) ->
                            val image = ImageIO.read(bytes.inputStream()).shouldNotBeNull()
                            imageMatches(image, page) shouldBe true
                        }
                    }
                }

                step("Read a download with the fixture server stopped") {
                    server.close()
                    onAllNodesWithText("Chapter 2 (PNG)").onFirst().performClick()
                    readThrough(FixtureCatalog.pages("png"), "2-offline")
                }
            }
        }
    }

    /** A downloaded chapter's page files, in page order: a CBZ (the default) or a folder. */
    private fun pageImages(file: File): List<ByteArray> {
        val isPage = { name: String -> name.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "webp", "avif", "gif") }
        return if (file.isDirectory) {
            file.listFiles().orEmpty().filter { it.isFile && isPage(it.name) }.sortedBy { it.name }.map { it.readBytes() }
        } else {
            ZipFile(file).use { zip ->
                zip.entries().toList().filter { !it.isDirectory && isPage(it.name) }.sortedBy { it.name }
                    .map { entry -> zip.getInputStream(entry).use { it.readBytes() } }
            }
        }
    }
}
