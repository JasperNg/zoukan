package zoukan.desktop.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.storage.ZoukanStorage
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.common.DesktopPreferences
import zoukan.desktop.ui.e2eEffectContext
import zoukan.desktop.ui.manga.MangaScreen
import zoukan.desktop.ui.settings.StorageLocation
import java.io.File
import java.security.MessageDigest
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

                step("Deleting downloads in the library asks first, and Cancel keeps every file") {
                    val before = formats.indices.associate { i ->
                        val file = downloads.findChapter(manga, chapters()[i]).shouldNotBeNull()
                        file.path to digest(file)
                    }
                    fun untouched() {
                        before.forEach { (path, d) -> digest(File(path)) shouldBe d }
                        downloads.downloadedIds(manga, chapters()).size shouldBe formats.size
                    }
                    runOnUiThread { navigator?.popUntilRoot() }
                    waitFor(hasText(FixtureCatalog.FORMATS_TITLE))

                    onAllNodesWithText(FixtureCatalog.FORMATS_TITLE).onFirst().performMouseInput { rightClick() }
                    waitFor(hasText("Delete downloads"))
                    onNodeWithText("Delete downloads").performClick()
                    waitFor(hasText("Delete the downloads of 1 entry?"))
                    waitFor(hasText("Cancel"))
                    Thread.sleep(1_000)
                    untouched()
                    onNodeWithText("Cancel").performClick()
                    waitForIdle()
                    untouched()

                    onAllNodesWithText(FixtureCatalog.FORMATS_TITLE).onFirst().performMouseInput { rightClick() }
                    waitFor(hasText("Select"))
                    onNodeWithText("Select").performClick()
                    waitFor(hasContentDescription("Delete downloads"))
                    onAllNodes(hasContentDescription("Delete downloads")).onFirst().performClick()
                    waitFor(hasText("Delete the downloads of 1 entry?"))
                    Thread.sleep(1_000)
                    untouched()
                    onNodeWithText("Cancel").performClick()
                    waitForIdle()
                    untouched()

                    root().performKeyInput { pressKey(Key.Escape) }
                    waitForIdle()
                    runOnUiThread { navigator?.push(MangaScreen(manga.id)) }
                    waitFor(hasText("Chapter 2 (PNG)"))
                }

                step("Deleting downloads from a right-click in Updates asks first, and Cancel keeps every file") {
                    val before = formats.indices.associate { i ->
                        val file = downloads.findChapter(manga, chapters()[i]).shouldNotBeNull()
                        file.path to digest(file)
                    }
                    fun untouched() {
                        before.forEach { (path, d) -> digest(File(path)) shouldBe d }
                        downloads.downloadedIds(manga, chapters()).size shouldBe formats.size
                    }
                    // The fixture chapters are dated January 2026; Updates lists the last 90 days
                    runBlocking {
                        val now = System.currentTimeMillis()
                        Injekt.get<UpdateChapter>().awaitAll(chapters().map { ChapterUpdate(id = it.id, dateUpload = now) })
                    }
                    runOnUiThread { navigator?.popUntilRoot() }
                    waitFor(hasText(FixtureCatalog.FORMATS_TITLE))
                    onAllNodesWithText("Updates").onFirst().performClick()
                    waitFor(hasTestTag("updates-leader"))

                    // Every chapter selected, then a right-click on the group's leader acts on the whole selection
                    // (Ctrl + A does the same selection)
                    onAllNodesWithTag("updates-leader").onFirst().performMouseInput { rightClick() }
                    waitFor(hasText("Select"))
                    onNodeWithText("Select").performClick()
                    waitFor(hasContentDescription("Select all"))
                    onAllNodes(hasContentDescription("Select all")).onFirst().performClick()
                    waitForIdle()
                    onAllNodesWithTag("updates-leader").onFirst().performMouseInput { rightClick() }
                    waitFor(hasText("Delete downloads"))
                    onNodeWithText("Delete downloads").performClick()
                    waitFor(hasText("Cancel"))
                    Thread.sleep(1_000)
                    untouched()
                    onNodeWithText("Cancel").performClick()
                    waitForIdle()
                    untouched()

                    // One chapter, not selected
                    root().performKeyInput { pressKey(Key.Escape) }
                    waitForIdle()
                    onAllNodes(hasContentDescription("Expand")).onFirst().performClick()
                    waitFor(hasText("Chapter 1 (JPEG)"))
                    onAllNodesWithText("Chapter 1 (JPEG)").onFirst().performMouseInput { rightClick() }
                    waitFor(hasText("Delete downloads"))
                    onNodeWithText("Delete downloads").performClick()
                    waitFor(hasText("Cancel"))
                    Thread.sleep(1_000)
                    untouched()
                    onNodeWithText("Cancel").performClick()
                    waitForIdle()
                    untouched()

                    runOnUiThread { navigator?.push(MangaScreen(manga.id)) }
                    waitFor(hasText("Chapter 2 (PNG)"))
                }

                step("Changing the storage folder leaves the downloads where they are") {
                    fun hashes(): Map<String, String> = formats.indices.flatMap { i ->
                        val file = downloads.findChapter(manga, chapters()[i]).shouldNotBeNull()
                        (if (file.isDirectory) file.walkTopDown().filter { it.isFile }.toList() else listOf(file)).map {
                            it.path to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { b -> "%02x".format(b) }
                        }
                    }.toMap()
                    val before = hashes()
                    val storage = Injekt.get<ZoukanStorage>()
                    val old = storage.folderPreference.get()

                    StorageLocation.apply(storage, File(dirs.root, "moved").path) { downloads.invalidateCache() }
                    downloads.downloadedIds(manga, chapters()).isEmpty() shouldBe true
                    before.forEach { (path, hash) ->
                        File(path).exists() shouldBe true
                        MessageDigest.getInstance("SHA-256").digest(File(path).readBytes()).joinToString("") { b -> "%02x".format(b) } shouldBe hash
                    }

                    StorageLocation.apply(storage, old) { downloads.invalidateCache() }
                    downloads.downloadedIds(manga, chapters()) shouldHaveSize formats.size
                    hashes() shouldBe before
                }

                step("Read a download with the fixture server stopped") {
                    server.close()
                    onAllNodesWithText("Chapter 2 (PNG)").onFirst().performClick()
                    readThrough(FixtureCatalog.pages("png"), "2-offline")
                }
            }
        }
    }

    /** What must be the same after a dialog was cancelled: the SHA-256 and size of the file, or of every file in a folder download. */
    private fun digest(file: File): List<Pair<String, Long>> {
        val files = if (file.isDirectory) file.walkTopDown().filter { it.isFile }.sortedBy { it.path }.toList() else listOf(file)
        return files.map { f ->
            val sha = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
            "${f.name}:$sha" to f.length()
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
