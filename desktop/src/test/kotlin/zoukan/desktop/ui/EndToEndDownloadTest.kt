package zoukan.desktop.ui

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
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
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.source.CatalogueSource
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mihon.domain.extension.interactor.AddExtensionStore
import mihon.domain.manga.model.toDomainManga
import net.lingala.zip4j.ZipFile
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.ZoukanRuntime
import zoukan.desktop.backup.AutoBackup
import zoukan.desktop.download.CbzPassword
import zoukan.desktop.download.CbzWriter
import zoukan.desktop.download.Download
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.library.LibraryUpdater
import zoukan.desktop.storage.ZoukanStorage
import zoukan.desktop.ui.browse.BrowseScreenModel
import zoukan.desktop.ui.common.DesktopPreferences
import zoukan.desktop.ui.common.Toasts
import zoukan.desktop.ui.manga.MangaScreen
import zoukan.extension.DesktopExtensionManager
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.time.Instant
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.minutes

/**
 * The Phase 5 gate, end to end: a full library update and a download run finish with no errors.
 * It installs an extension, adds three entries to the library, updates the library from the Updates
 * tab, downloads chapters from an entry's screen (as a CBZ, then an encrypted CBZ), reads a
 * download, runs a download of every entry's newest chapter, reads from the local source and makes
 * an automatic backup. It needs the network, so it runs only on request:
 *
 *   ./gradlew :desktop:test -Pzoukan.e2e=true --tests "*EndToEndDownloadTest"
 *
 * -Pzoukan.e2e.package=<pkg> picks another extension. Screenshots go to desktop/build/reports/e2e/.
 */
@OptIn(ExperimentalTestApi::class)
class EndToEndDownloadTest {

    private val shots = File("build/reports/e2e").apply { mkdirs() }

    @Test
    fun updateDownloadAndReadOffline() {
        assumeTrue(System.getProperty("zoukan.e2e") == "true", "End-to-end test; run with -Pzoukan.e2e=true")
        val pkg = System.getProperty("zoukan.e2e.package") ?: "eu.kanade.tachiyomi.extension.en.mangapill"
        val dirs = ZoukanDirs(Files.createTempDirectory("zoukan-e2e-download").toFile())
        ZoukanRuntime.start(dirs, interactiveBrowser = false)
        startUi(dirs)
        val preferences = Injekt.get<DesktopPreferences>()
        val extensions = Injekt.get<DesktopExtensionManager>()
        val downloads = Injekt.get<DownloadManager>()
        val updater = Injekt.get<LibraryUpdater>()
        val storage = Injekt.get<ZoukanStorage>()
        var navigator: Navigator? = null
        AppScope.launch { Toasts.flow.collect { println("E2E toast: $it") } }

        val source = step("Install $pkg and add three entries to the library") {
            runBlocking {
                Injekt.get<AddExtensionStore>()(BrowseScreenModel.KEIYOUSHI).getOrThrow()
                extensions.findAvailableExtensions()
                val available = extensions.availableExtensionsFlow.value[pkg] ?: error("$pkg is not in the store")
                extensions.installExtension(available)
                extensions.installedExtensionsFlow.first { it.containsKey(pkg) }
                val source = extensions.installedExtensionsFlow.value.getValue(pkg).sources.first() as CatalogueSource
                val popular = source.getPopularManga(1).mangas.take(3)
                val entries = Injekt.get<NetworkToLocalManga>()(popular.map { it.toDomainManga(source.id) })
                entries.forEach {
                    Injekt.get<MangaRepository>().update(MangaUpdate(id = it.id, favorite = true, dateAdded = Instant.now().toEpochMilli()))
                }
                source
            }
        }
        // Update every entry, whatever the skip rules say: new entries have no chapters read
        Injekt.get<LibraryPreferences>().autoUpdateMangaRestrictions().set(emptySet())

        runDesktopComposeUiTest(width = 1280, height = 820, effectContext = e2eEffectContext(), testTimeout = 30.minutes) {
            setContent { ZoukanContent(preferences) { navigator = it } }

            step("Update the library from the Updates tab") {
                onAllNodesWithText("Updates").onFirst().performClick()
                waitFor(hasText("No recent updates", substring = true) or hasText("Unread only"))
                onAllNodes(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription, listOf("Update library")))
                    .onFirst().performClick()
                waitUntil(timeoutMillis = 300_000) { updater.lastResult.value != null && updater.progress.value == null }
                val result = updater.lastResult.value!!
                println("E2E: library update: ${result.updated} entries, ${result.newChapters} new chapters, failures ${result.failures}")
                result.failures.shouldBeEmpty()
                result.updated shouldBe 3
                // Newest fetches come first, so any of the entries can be at the top of the list
                val titles = result.entries.map { it.title }
                waitUntil(timeoutMillis = 30_000) {
                    titles.any { onAllNodes(hasText(it, substring = true)).fetchSemanticsNodes().isNotEmpty() }
                }
                shot("d1-updates")
            }

            val manga = runBlocking { Injekt.get<GetFavorites>().await().first() }
            step("Download the next two chapters of ${manga.title}") {
                runOnUiThread { navigator?.push(MangaScreen(manga.id)) }
                waitFor(hasText("Start") and isEnabled(), timeout = 120_000)
                onAllNodes(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription, listOf("Download")))
                    .onFirst().performClick()
                waitFor(hasText("Next chapter"))
                onAllNodesWithText("Next chapter").onFirst().performClick()
                waitUntil(timeoutMillis = 300_000) { downloads.queue.value.isEmpty() && !downloads.running.value }
                // The first chapters sit at the bottom of a long list, off screen, so ask the manager
                waitUntil(timeoutMillis = 30_000) { downloadedCount(manga) == 1 }
                shot("d2-downloaded")

                // The next one encrypted
                Injekt.get<CbzPassword>().set("e2e-password")
                Injekt.get<SecurityPreferences>().passwordProtectDownloads().set(true)
                onAllNodes(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription, listOf("Download")))
                    .onFirst().performClick()
                waitFor(hasText("Next chapter"))
                onAllNodesWithText("Next chapter").onFirst().performClick()
                waitUntil(timeoutMillis = 300_000) { downloads.queue.value.isEmpty() && !downloads.running.value }
                // The first chapters sit at the bottom of a long list, off screen, so ask the manager
                waitUntil(timeoutMillis = 30_000) { downloadedCount(manga) == 2 }
                val files = storage.downloads.walkTopDown().filter { it.isFile && it.extension == "cbz" }.toList()
                println("E2E: downloaded $files")
                files.size shouldBe 2
                files.count { ZipFile(it).isEncrypted } shouldBe 1
            }

            step("Read the encrypted download") {
                val chapters = runBlocking { Injekt.get<GetChaptersByMangaId>().await(manga.id) }
                val second = chapters.sortedByDescending { it.sourceOrder }[1]
                downloads.findChapter(manga, second) shouldNotBe null
                runOnUiThread { navigator?.push(zoukan.desktop.ui.reader.ReaderScreen(manga.id, second.id)) }
                waitUntil(timeoutMillis = 60_000) { pageOnScreen() }
                onRoot().performKeyInput { pressKey(Key.M) }
                waitFor(hasText("Downloaded", substring = true))
                shot("d3-reader-downloaded")
                onRoot().performKeyInput { pressKey(Key.M) }
                runOnUiThread { navigator?.popUntilRoot() }
            }

            step("Download every entry's newest chapter") {
                Injekt.get<SecurityPreferences>().passwordProtectDownloads().set(false)
                runBlocking {
                    val getChapters = Injekt.get<GetChaptersByMangaId>()
                    Injekt.get<GetFavorites>().await().forEach { entry ->
                        downloads.enqueue(entry, getChapters.await(entry.id).sortedBy { it.sourceOrder }.take(1), start = false)
                    }
                }
                val queued = downloads.queue.value.toList()
                println("E2E: downloading ${queued.size} chapters")
                downloads.start()
                runOnUiThread { navigator?.push(zoukan.desktop.ui.downloads.DownloadsScreen) }
                Thread.sleep(1500)
                shot("d4-download-queue")
                waitUntil(timeoutMillis = 600_000) { !downloads.running.value }
                val failed = queued.filter { it.state.value != Download.State.DOWNLOADED }
                failed.map { "${it.manga.title} / ${it.chapter.name}: ${it.error}" }.shouldBeEmpty()
                runOnUiThread { navigator?.popUntilRoot() }
            }

            step("Read from the local source") {
                val dir = File(storage.local, "E2E Local Manga").apply { mkdirs() }
                CbzWriter(File(dir, "Chapter 1.cbz")).use { writer ->
                    (1..3).forEach { writer.write("%03d.png".format(it), png(it)) }
                }
                onAllNodesWithText("Browse").onFirst().performClick()
                onAllNodesWithText("Sources").onFirst().performClick()
                waitFor(hasText("Local source"))
                onAllNodesWithText("Local source").onFirst().performScrollTo().performClick()
                // Background work after the downloads can keep the database busy for a while; this once took over 30 s on Windows
                waitOrDump("d5-local-source-failed", timeout = 120_000) { onAllNodesWithTag("manga-item").fetchSemanticsNodes().isNotEmpty() }
                onAllNodesWithTag("manga-item").onFirst().performClick()
                waitFor(hasText("Start") and isEnabled(), timeout = 30_000)
                onAllNodesWithText("Start").onFirst().performClick()
                waitUntil(timeoutMillis = 30_000) { pageOnScreen() }
                shot("d5-local-reader")
                runOnUiThread { navigator?.popUntilRoot() }
            }
        }

        step("Automatic backup") {
            val file = runBlocking { Injekt.get<AutoBackup>().run() }
            file shouldNotBe null
            file!!.parentFile shouldBe storage.autoBackups
        }
        runBlocking { Injekt.get<SourceManager>().get(source.id) shouldNotBe null }
    }

    private fun png(shade: Int): ByteArray {
        val image = BufferedImage(600, 900, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = java.awt.Color(40 * shade, 120, 200)
        g.fillRect(0, 0, 600, 900)
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun downloadedCount(manga: tachiyomi.domain.manga.model.Manga): Int = runBlocking {
        val chapters = Injekt.get<GetChaptersByMangaId>().await(manga.id)
        Injekt.get<DownloadManager>().downloadedIds(manga, chapters).size
    }

    private fun <T> step(name: String, block: () -> T): T {
        println("E2E: $name")
        return block()
    }

    private fun ComposeUiTest.waitFor(matcher: SemanticsMatcher, timeout: Long = 30_000) {
        waitUntil(timeoutMillis = timeout) { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Waits for [condition]; on a timeout saves a screenshot and prints the texts on screen. */
    private fun ComposeUiTest.waitOrDump(name: String, timeout: Long = 30_000, condition: () -> Boolean) {
        try {
            waitUntil(timeoutMillis = timeout, condition = condition)
        } catch (e: Throwable) {
            runCatching { shot(name) }
            val texts = onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes()
                .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
            println("E2E: texts on screen: $texts")
            E2EDiagnostics.dump()
            // Which nudge brings the screen up to date tells where it was stuck
            val nudges = listOf<Pair<String, () -> Unit>>(
                "Snapshot.sendApplyNotifications" to { runOnUiThread { androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() } },
                "waitForIdle" to { waitForIdle() },
                "advance the clock by one frame" to { mainClock.advanceTimeByFrame() },
                "advance the clock by 1 s" to { mainClock.advanceTimeBy(1_000) },
                "a mouse move" to { onRoot().performMouseInput { moveTo(center) } },
            )
            for ((label, nudge) in nudges) {
                val ok = runCatching {
                    nudge()
                    condition()
                }
                println("E2E: after $label the condition is ${ok.getOrElse { "an error: $it" }}")
                if (ok.getOrNull() == true) break
            }
            throw e
        }
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
