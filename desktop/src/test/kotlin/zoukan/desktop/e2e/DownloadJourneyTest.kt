package zoukan.desktop.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
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
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
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
import tachiyomi.domain.manga.model.Manga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.download.Download
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.download.DownloadPaths
import zoukan.desktop.download.SafeDelete
import zoukan.desktop.storage.ZoukanStorage
import zoukan.desktop.ui.HomeScreen
import zoukan.desktop.ui.HomeTab
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.downloads.DownloadsScreen
import zoukan.desktop.ui.e2eEffectContext
import zoukan.desktop.ui.manga.MangaScreen
import zoukan.desktop.ui.settings.SettingsCategory
import zoukan.desktop.ui.settings.StorageLocation
import zoukan.desktop.ui.settingsPage
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.minutes

/**
 * Downloads chapters from a title's screen, checks the files hold every page, decodable and the
 * right picture, drives the download queue (opened from the More tab: pause, reorder, cancel one,
 * resume, cancel all) and the clean-up of downloads from Settings > Advanced, then stops the
 * fixture server and reads a download offline.
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
            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 15.minutes) {
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
                    awaitAppIdle()
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
                    awaitAppIdle()
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
                    awaitAppIdle()
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
                    awaitAppIdle()
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

                step("More > Download queue: pause, reorder from the menu, cancel one, resume, cancel all") {
                    val paths = Injekt.get<DownloadPaths>()
                    val second = addToLibrary("Fixture Second")
                    val third = addToLibrary("Fixture Third")
                    fun chaptersOf(m: Manga) = runBlocking { Injekt.get<GetChaptersByMangaId>().await(m.id) }
                    fun order() = downloads.queue.value.map { it.manga.title }
                    val avif = chapters()[3]
                    val avifTmp = File(paths.mangaDir(manga), paths.chapterDirName(avif) + DownloadPaths.TMP_SUFFIX)
                    fun partialPages() = avifTmp.listFiles().orEmpty().count { it.isFile && !it.name.endsWith(".tmp") }
                    // The AVIF chapter's last page never answers, so its download stays in the queue with
                    // the first two pages in its _tmp folder
                    server.fail("/images/${FixtureCatalog.pages("avif")[2].file}", FixtureFailure.Hang)
                    downloads.enqueue(manga, listOf(avif))
                    downloads.enqueue(second, chaptersOf(second))
                    downloads.enqueue(third, chaptersOf(third))
                    order() shouldBe listOf(FixtureCatalog.FORMATS_TITLE, "Fixture Second", "Fixture Third")

                    runOnUiThread {
                        navigator?.popUntilRoot()
                        HomeScreen.selectTab(HomeTab.More)
                    }
                    waitFor(hasText("Download queue"))
                    onAllNodesWithText("Download queue").onFirst().performClick()
                    waitFor(hasText("Fixture Third"))
                    runOnUiThread { navigator?.lastItem shouldBe DownloadsScreen }
                    waitOrDump("for the AVIF chapter's partial download") {
                        downloads.queue.value.first().state.value == Download.State.DOWNLOADING && partialPages() == 2
                    }
                    shot("3-queue-downloading")

                    byDescription("Pause").performClick()
                    waitOrDump("for the queue to pause", 10_000) {
                        !downloads.running.value && downloads.queue.value.all { it.state.value == Download.State.QUEUED }
                    }
                    waitFor(hasContentDescription("Start"))
                    // Pausing keeps the pages already downloaded
                    partialPages() shouldBe 2

                    rowButton("Fixture Third", "More")
                    onAllNodesWithText("Move to top").onFirst().performClick()
                    waitOrDump("for Fixture Third to move to the top", 10_000) {
                        order() == listOf("Fixture Third", FixtureCatalog.FORMATS_TITLE, "Fixture Second")
                    }
                    rowButton(FixtureCatalog.FORMATS_TITLE, "More")
                    onAllNodesWithText("Move to bottom").onFirst().performClick()
                    waitOrDump("for ${FixtureCatalog.FORMATS_TITLE} to move to the bottom", 10_000) {
                        order() == listOf("Fixture Third", "Fixture Second", FixtureCatalog.FORMATS_TITLE)
                    }
                    // On screen in the same order
                    waitOrDump("for the rows in the queue's order", 10_000) {
                        val tops = order().map { title -> onAllNodesWithText(title).fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.top }
                        tops.none { it == null } && tops.filterNotNull().zipWithNext().all { (a, b) -> a < b }
                    }
                    shot("4-queue-reordered")

                    rowButton("Fixture Second", "Cancel")
                    waitOrDump("for Fixture Second to leave the queue", 10_000) {
                        order() == listOf("Fixture Third", FixtureCatalog.FORMATS_TITLE)
                    }

                    byDescription("Start").performClick()
                    waitOrDump("for Fixture Third to download and the AVIF chapter to resume", 60_000) {
                        downloads.downloadedIds(third, chaptersOf(third)).size == 1 &&
                            order() == listOf(FixtureCatalog.FORMATS_TITLE) &&
                            downloads.queue.value.first().state.value == Download.State.DOWNLOADING && partialPages() == 2
                    }
                    downloads.downloadedIds(second, chaptersOf(second)).isEmpty() shouldBe true

                    byDescription("Cancel all").performClick()
                    waitOrDump("for the queue to empty", 10_000) { downloads.queue.value.isEmpty() }
                    // Cancelling removes the unfinished download's files
                    waitOrDump("for the partial download to be removed", 10_000) {
                        !avifTmp.exists() && !File(avifTmp.parentFile, paths.chapterDirName(avif) + ".cbz" + DownloadPaths.TMP_SUFFIX).exists()
                    }
                    downloads.findChapter(manga, avif) shouldBe null
                    // The other downloads are untouched
                    downloads.downloadedIds(manga, chapters()) shouldHaveSize formats.size
                    waitFor(hasText("No downloads queued", substring = true))
                    shot("5-queue-cleared")
                    server.clearFailures()
                }

                step("Settings > Advanced > Clean up downloaded chapters: only what the preview lists goes, an orphan only once ticked") {
                    val paths = Injekt.get<DownloadPaths>()
                    val trashed: MutableList<File> = Collections.synchronizedList(mutableListOf())
                    val originalTrash = SafeDelete.trash
                    // The journey must not use the machine's trash: the hook records what would go there
                    SafeDelete.trash = { file ->
                        trashed += file
                        file.deleteRecursively()
                    }
                    try {
                        val mangaDir = paths.mangaDir(manga)
                        val sourceDir = mangaDir.parentFile
                        val read = downloads.findChapter(manga, chapters()[0]).shouldNotBeNull()
                        val unread = listOf(1, 2).map { downloads.findChapter(manga, chapters()[it]).shouldNotBeNull() }
                        runBlocking { Injekt.get<UpdateChapter>().awaitAll(listOf(ChapterUpdate(id = chapters()[0].id, read = true))) }
                        // An unfinished download, a file that is no chapter, a chapter only the Android app
                        // knows, and a folder of no entry
                        val stray = File(mangaDir, "Chapter 9${DownloadPaths.TMP_SUFFIX}").apply { mkdirs() }
                        File(stray, "001.png").writeBytes(ByteArray(10))
                        val notes = File(mangaDir, "notes.txt").apply { writeText("not a chapter") }
                        val androidOnly = File(mangaDir, "Chapter 7.cbz").apply { writeBytes(ByteArray(5)) }
                        val orphan = File(sourceDir, "Nobody").apply { mkdirs() }
                        // No chapter name of any entry: a folder holding one of their chapters counts as a renamed entry's, not an orphan
                        File(orphan, "Chapter 99.cbz").writeBytes(ByteArray(3))
                        fun survivors() = (unread + listOf(notes, androidOnly)).all { it.exists() }

                        runOnUiThread {
                            navigator?.popUntilRoot()
                            navigator?.let { HomeScreen.openSettings(it) }
                        }
                        fun openPreview() {
                            val list = settingsPage(SettingsCategory.Advanced)
                            list.performScrollToNode(hasText("Clean up downloaded chapters"))
                            onAllNodesWithText("Clean up downloaded chapters").onFirst().performClick()
                            waitFor(hasText("Clean read"))
                            lastWithText("OK").performClick()
                            waitFor(hasText("Delete these downloads?"))
                        }

                        openPreview()
                        waitFor(hasText("Unfinished downloads: 1", substring = true))
                        waitFor(hasText("Read chapters: 1", substring = true))
                        waitFor(hasText("${sourceDir.name}/Nobody", substring = true))
                        onAllNodesWithText("Chapters of entries not in the library", substring = true).fetchSemanticsNodes() shouldHaveSize 0
                        shot("6-cleanup-preview")
                        // Nothing is deleted before the confirm
                        (read.exists() && stray.exists() && orphan.exists() && survivors()) shouldBe true
                        lastWithText("Delete").performClick()
                        waitOrDump("for the clean-up to finish", 30_000) { toasts.contains("Cleanup done. Removed 2 folders") }
                        trashed.map { it.name } shouldContainExactlyInAnyOrder listOf(read.name, stray.name)
                        read.exists() shouldBe false
                        stray.exists() shouldBe false
                        survivors() shouldBe true
                        // Not ticked: kept
                        orphan.isDirectory shouldBe true
                        downloads.downloadedIds(manga, chapters()) shouldBe chapters().drop(1).take(2).map { it.id }.toSet()

                        // Again: now only the orphan is listed, and it goes once ticked
                        openPreview()
                        waitFor(hasText("${sourceDir.name}/Nobody", substring = true))
                        onAllNodesWithText("Unfinished downloads", substring = true).fetchSemanticsNodes() shouldHaveSize 0
                        onAllNodesWithText("${sourceDir.name}/Nobody", substring = true).onFirst().performClick()
                        shot("7-cleanup-orphan-ticked")
                        lastWithText("Delete").performClick()
                        waitOrDump("for the orphan to be deleted", 30_000) { toasts.contains("Cleanup done. Removed 1 folder") }
                        orphan.exists() shouldBe false
                        trashed.map { it.name } shouldContainExactlyInAnyOrder listOf(read.name, stray.name, orphan.name)
                        survivors() shouldBe true
                    } finally {
                        SafeDelete.trash = originalTrash
                    }
                    runOnUiThread { navigator?.popUntilRoot() }
                    runOnUiThread { navigator?.push(MangaScreen(manga.id)) }
                    waitFor(hasText("Chapter 2 (PNG)"))
                }

                step("Read a download with the fixture server stopped") {
                    server.close()
                    onAllNodesWithText("Chapter 2 (PNG)").onFirst().performClick()
                    readThrough(FixtureCatalog.pages("png"), "2-offline")
                }
            }
        }
    }

    /** The last node with [text]: in the dialog on top, when the page under it has the same text. */
    private fun ComposeUiTest.lastWithText(text: String) =
        onAllNodesWithText(text).let { it[it.fetchSemanticsNodes().size - 1] }

    /** Clicks the button described [description] in the queue row of [title]: the one level with the title. */
    private fun ComposeUiTest.rowButton(title: String, description: String) {
        val label = onAllNodesWithText(title).fetchSemanticsNodes().single().boundsInRoot
        val buttons = onAllNodes(hasContentDescription(description))
        // Right of the title, so never a button of the navigation rail
        val index = buttons.fetchSemanticsNodes().withIndex()
            .filter { (_, node) -> node.boundsInRoot.left > label.left }
            .minBy { (_, node) -> kotlin.math.abs(node.boundsInRoot.center.y - label.center.y) }.index
        buttons[index].performClick()
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
