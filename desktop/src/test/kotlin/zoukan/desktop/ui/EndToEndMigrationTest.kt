package zoukan.desktop.ui

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
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
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import mihon.domain.manga.model.toDomainManga
import okhttp3.Headers
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
import zoukan.desktop.download.CbzWriter
import zoukan.desktop.exh.BuiltInSources
import zoukan.desktop.library.LibraryUpdater
import zoukan.desktop.local.LocalSource
import zoukan.desktop.storage.ZoukanStorage
import zoukan.desktop.ui.common.DesktopPreferences
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.minutes

/**
 * Bulk migration and batch add through the real UI, offline: two library entries migrate from
 * the local source to an in-memory source that has one of them, and batch add reports an address
 * no source knows. Screenshots go to `build/reports/e2e/m*.png`.
 *
 *   ./gradlew :desktop:test -Pzoukan.e2e=true --tests "*EndToEndMigrationTest"
 */
@OptIn(ExperimentalTestApi::class)
class EndToEndMigrationTest {

    private val shots = File("build/reports/e2e").apply { mkdirs() }

    /** Has "Alpha" with five chapters, and nothing else. */
    private class MirrorSource : HttpSource() {
        override val name = "Mirror"
        override val lang = "en"
        override val supportsLatest = false
        override val baseUrl = "https://mirror.invalid"
        override fun headersBuilder() = Headers.Builder()

        private fun alpha() = SManga.create().apply {
            url = "/alpha"
            title = "Alpha"
            initialized = true
        }

        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
            MangasPage(if (query.contains("alpha", ignoreCase = true)) listOf(alpha()) else emptyList(), false)

        override suspend fun getMangaUpdate(
            manga: SManga,
            chapters: List<SChapter>,
            fetchDetails: Boolean,
            fetchChapters: Boolean,
        ): SMangaUpdate = SMangaUpdate(
            if (fetchDetails) alpha() else manga,
            if (fetchChapters) {
                (5 downTo 1).map { n ->
                    SChapter.create().apply {
                        url = "/alpha/$n"
                        name = "Chapter $n"
                        chapter_number = n.toFloat()
                    }
                }
            } else {
                chapters
            },
        )
    }

    @Test
    fun bulkMigrationAndBatchAdd() {
        assumeTrue(System.getProperty("zoukan.e2e") == "true", "End-to-end test; run with -Pzoukan.e2e=true")
        val dirs = ZoukanDirs(Files.createTempDirectory("zoukan-e2e-migration").toFile())
        ZoukanRuntime.start(dirs, interactiveBrowser = false)
        startUi(dirs)
        val preferences = Injekt.get<DesktopPreferences>()
        val library = Injekt.get<LibraryPreferences>()
        val storage = Injekt.get<ZoukanStorage>()
        val mirror = MirrorSource()
        Injekt.get<BuiltInSources>().testSources.value = listOf(mirror)

        listOf("Alpha" to 3, "Bravo" to 2).forEach { (title, chapters) ->
            val dir = File(storage.local, title).apply { mkdirs() }
            (1..chapters).forEach { cbz(File(dir, "Chapter $it.cbz"), title) }
        }
        library.autoUpdateMangaRestrictions().set(emptySet())
        runBlocking {
            val source = Injekt.get<SourceManager>().get(LocalSource.ID) as CatalogueSource
            val found = source.getPopularManga(1).mangas.map { it.toDomainManga(LocalSource.ID) }
            Injekt.get<NetworkToLocalManga>()(found).forEach { manga ->
                Injekt.get<MangaRepository>().update(MangaUpdate(id = manga.id, favorite = true, dateAdded = System.currentTimeMillis()))
            }
        }
        val updater = Injekt.get<LibraryUpdater>()
        updater.start()
        runBlocking { while (updater.progress.value != null || updater.lastResult.value == null) Thread.sleep(200) }
        updater.lastResult.value!!.failures.shouldBeEmpty()
        runBlocking { Injekt.get<GetFavorites>().await() }.size shouldBe 2

        runDesktopComposeUiTest(width = 1280, height = 820, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
            setContent { ZoukanContent(preferences) }

            step("Select both entries and open the migration") {
                waitUntil(timeoutMillis = 30_000) { onAllNodesWithTag("manga-item").fetchSemanticsNodes().size == 2 }
                // A right click opens the entry's menu; Select there starts the selection
                onAllNodesWithText("Alpha").onFirst().performMouseInput { rightClick() }
                waitFor(hasText("Select"))
                onAllNodesWithText("Select").onFirst().performClick()
                waitFor(hasText("1 selected"))
                onAllNodesWithText("Bravo").onFirst().performClick()
                waitFor(hasText("2 selected"))
                byDescription("Migrate").performClick()
                waitFor(hasText("Select sources"))
                waitFor(hasText("Mirror"))
                shot("m1-migration-sources")
            }

            step("Search the chosen sources") {
                onAllNodesWithText("Start").onFirst().performClick()
                waitFor(hasText("Migration (2/2)"), 60_000)
                waitFor(hasText("No alternatives found"))
                waitUntil(timeoutMillis = 30_000) { onAllNodesWithText("5 chapters", substring = true).fetchSemanticsNodes().isNotEmpty() }
                shot("m2-migration-list")
            }

            step("Migrate the match") {
                byDescription("Migrate").performClick()
                waitFor(hasText("Migrate entries?"))
                shot("m3-migrate-dialog")
                onAllNodesWithText("Migrate").onLast().performClick()
                waitUntil(timeoutMillis = 60_000) {
                    runBlocking { Injekt.get<GetFavorites>().await() }.any { it.source == mirror.id }
                }
                val favorites = runBlocking { Injekt.get<GetFavorites>().await() }
                // Alpha moved to Mirror; Bravo, without a match, stays on the local source
                favorites.map { it.title to (it.source == mirror.id) }.toSet() shouldBe setOf("Alpha" to true, "Bravo" to false)
                val migrated = favorites.first { it.source == mirror.id }
                runBlocking { Injekt.get<GetChaptersByMangaId>().await(migrated.id) }.size shouldBe 5
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("manga-item").fetchSemanticsNodes().size == 2 }
                shot("m4-library-after-migration")
            }

            step("Batch add an address no source knows") {
                HomeScreen.selectTab(HomeTab.Browse)
                waitFor(hasText("Browse"))
                byDescription("Batch add").performClick()
                waitFor(hasText("Add galleries"))
                onNodeWithTag("batch-add-input").performTextInput("https://unknown.invalid/g/1/abc")
                shot("b1-batch-add-input")
                onAllNodesWithText("Add galleries").onFirst().performClick()
                waitFor(hasText("Finish"), 60_000)
                waitFor(hasText("[ERROR]", substring = true))
                shot("b2-batch-add-result")
            }
        }
    }

    private fun ComposeUiTest.byDescription(text: String) =
        onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(text))).onFirst()

    private fun cbz(file: File, label: String) {
        CbzWriter(file).use { writer ->
            (1..2).forEach { i ->
                val image = BufferedImage(300, 450, BufferedImage.TYPE_INT_RGB)
                val g = image.createGraphics()
                g.color = java.awt.Color(40 * i, 90, 160)
                g.fillRect(0, 0, 300, 450)
                g.color = java.awt.Color.WHITE
                g.drawString("$label $i", 100, 220)
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
