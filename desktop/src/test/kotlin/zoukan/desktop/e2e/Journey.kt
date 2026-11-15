package zoukan.desktop.e2e

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mihon.domain.manga.model.toDomainManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.ZoukanRuntime
import zoukan.desktop.domain.RefreshManga
import zoukan.desktop.exh.BuiltInSources
import zoukan.desktop.secrets.SecretStore
import zoukan.desktop.ui.AppScope
import zoukan.desktop.ui.E2EDiagnostics
import zoukan.desktop.ui.common.Toasts
import zoukan.desktop.ui.startUi
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * What every offline journey shares: a fresh data folder, the app's runtime and UI services, the
 * [FixtureServer] and the [FixtureSource] over it, and the steps tests take on screen.
 *
 * The runtime starts once per JVM, so each journey is its own test class and the `journeyTest`
 * task forks a JVM for each (see desktop/build.gradle.kts).
 */
@OptIn(ExperimentalTestApi::class)
class Journey private constructor(val name: String) {

    val dirs = ZoukanDirs(Files.createTempDirectory("zoukan-journey-$name").toFile())
    val server = FixtureServer()
    val source = FixtureSource(server.baseUrl)
    private val shots = File("build/reports/journeys").apply { mkdirs() }

    /** The source's settings file, as the app writes it (AndroidCompat's settings folder). */
    val sourcePreferencesFile: File get() = File(dirs.android, "settings/source_${source.id}.xml")

    companion object {
        const val WIDTH = 1280
        const val HEIGHT = 820

        /** Starts the app's runtime on a fresh data folder with the fixture source installed. */
        fun start(name: String): Journey {
            // The OS keychain is not for tests: keep the secrets in the data folder
            System.setProperty(SecretStore.FILE_STORE_PROPERTY, "true")
            val journey = Journey(name)
            ZoukanRuntime.start(journey.dirs, interactiveBrowser = false)
            startUi(journey.dirs)
            // The source's grid, as before Android's "Replace latest button" (on by default) opened its feed
            Injekt.get<eu.kanade.domain.ui.UiPreferences>().useNewSourceNavigation().set(false)
            AppScope.launch { Toasts.flow.collect { println("Journey toast: $it") } }
            Injekt.get<BuiltInSources>().testSources.value = listOf(journey.source)
            runBlocking {
                val sources = Injekt.get<SourceManager>()
                sources.isInitialized.first { it }
                val deadline = System.currentTimeMillis() + 10_000
                while (sources.get(journey.source.id) == null) {
                    check(System.currentTimeMillis() < deadline) { "the SourceManager didn't list the fixture source within 10s" }
                    Thread.sleep(50)
                }
            }
            println("Journey $name: fixtures at ${journey.server.baseUrl}, data in ${journey.dirs.root}")
            return journey
        }
    }

    fun <T> step(title: String, block: () -> T): T {
        println("Journey $name: $title")
        return block()
    }

    fun ComposeUiTest.waitFor(matcher: SemanticsMatcher, timeout: Long = 30_000) =
        waitOrDump("waiting for ${matcher.description}", timeout) { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    /** Waits for [condition]; on a timeout saves a screenshot, prints the texts on screen and the app's state. */
    fun ComposeUiTest.waitOrDump(what: String, timeout: Long = 30_000, condition: () -> Boolean) {
        try {
            waitUntil(timeoutMillis = timeout, condition = condition)
        } catch (e: Throwable) {
            println("Journey $name: timed out $what")
            runCatching { shot("timeout") }
            val texts = onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes()
                .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
            println("Journey $name: texts on screen: $texts")
            println("Journey $name: fixture requests: ${server.requests}")
            E2EDiagnostics.dump()
            throw AssertionError("Timed out $what", e)
        }
    }

    fun ComposeUiTest.shot(title: String) {
        waitForIdle()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(shots, "$name-$title.png"))
    }

    /** The reader's page images that are on screen: off-screen pages have no visible bounds. */
    private fun ComposeUiTest.pagesOnScreen(): List<SemanticsNode> =
        onAllNodesWithTag("page-image").fetchSemanticsNodes().filter { node ->
            val bounds = node.boundsInRoot
            bounds.width > 0f && bounds.height > 0f && bounds.right > 0f && bounds.left < WIDTH && bounds.top < HEIGHT
        }

    /**
     * Whether [page] drew: a quarter or more of an on-screen page image is its colour. A page that
     * failed to decode shows the reader's error or a blank, and a page still loading its spinner,
     * so none of them pass.
     */
    fun ComposeUiTest.pageDrew(page: FixturePage): Boolean {
        val window = runCatching { onRoot().captureToImage().toAwtImage() }.getOrNull() ?: return false
        return pagesOnScreen().any { node -> share(window, node, page) >= 0.25 }
    }

    /**
     * Reads [pages] in order in the paged reader: waits for each to draw, turning the page (Left,
     * the next page right to left) when it is not on screen yet. A wide window shows two pages
     * side by side, so one turn can bring two of them.
     */
    fun ComposeUiTest.readThrough(pages: List<FixturePage>, label: String) {
        pages.forEachIndexed { index, page ->
            var turns = 0
            while (true) {
                val drew = runCatching { waitUntil(timeoutMillis = 15_000) { pageDrew(page) } }.isSuccess
                if (drew) break
                if (turns++ >= 2) {
                    shot("$label-page-${index + 1}-failed")
                    waitOrDump("for page ${index + 1} (${page.file}) to draw", timeout = 1) { pageDrew(page) }
                }
                onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
            }
            shot("$label-page-${index + 1}")
        }
    }

    /** The share of [node]'s area in [window] that is [page]'s colour. */
    private fun share(window: BufferedImage, node: SemanticsNode, page: FixturePage): Double {
        val bounds = node.boundsInRoot
        val left = bounds.left.toInt().coerceIn(0, window.width)
        val top = bounds.top.toInt().coerceIn(0, window.height)
        val right = bounds.right.toInt().coerceIn(0, window.width)
        val bottom = bounds.bottom.toInt().coerceIn(0, window.height)
        if (right <= left || bottom <= top) return 0.0
        var matching = 0
        var total = 0
        // Every fourth pixel is plenty for a solid colour
        for (y in top until bottom step 4) {
            for (x in left until right step 4) {
                total++
                val rgb = window.getRGB(x, y)
                if (close(rgb shr 16 and 0xFF, page.red) && close(rgb shr 8 and 0xFF, page.green) && close(rgb and 0xFF, page.blue)) {
                    matching++
                }
            }
        }
        return if (total == 0) 0.0 else matching.toDouble() / total
    }

    /** Lossy formats shift a solid colour a little. */
    private fun close(a: Int, b: Int) = abs(a - b) <= 24

    /** Whether a decoded image file is mostly [page]'s colour, for files the app wrote. */
    fun imageMatches(image: BufferedImage, page: FixturePage): Boolean {
        val rgb = image.getRGB(image.width / 4, image.height / 4)
        return close(rgb shr 16 and 0xFF, page.red) && close(rgb shr 8 and 0xFF, page.green) && close(rgb and 0xFF, page.blue)
    }

    /**
     * Adds a fixture title to the library with its chapters, as "Add to library" on its screen
     * does, for journeys about what comes after.
     */
    fun addToLibrary(title: String): Manga = runBlocking {
        val listed = source.getPopularManga(1).mangas.single { it.title == title }
        val manga = Injekt.get<NetworkToLocalManga>()(listOf(listed.toDomainManga(source.id))).single()
        Injekt.get<MangaRepository>().update(MangaUpdate(id = manga.id, favorite = true, dateAdded = System.currentTimeMillis()))
        Injekt.get<RefreshManga>()(Injekt.get<MangaRepository>().getMangaById(manga.id), source = source).manga
    }

    fun close() {
        Injekt.get<BuiltInSources>().testSources.value = emptyList()
        server.close()
    }
}
