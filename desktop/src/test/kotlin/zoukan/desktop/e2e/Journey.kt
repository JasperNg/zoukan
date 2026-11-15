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
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import eu.kanade.tachiyomi.source.model.FilterList
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
import zoukan.desktop.update.Updates
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.util.Collections
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

    /** Every toast the app showed, in order (the app's own and the extensions'). */
    val toasts: MutableList<String> = Collections.synchronizedList(mutableListOf())

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
            try {
                startRuntime(journey)
            } catch (e: Throwable) {
                journey.server.close()
                throw e
            }
            println("Journey $name: fixtures at ${journey.server.baseUrl}, data in ${journey.dirs.root}")
            return journey
        }

        private fun startRuntime(journey: Journey) {
            ZoukanRuntime.start(journey.dirs, interactiveBrowser = false)
            // Journeys are offline: no daily update check against GitHub (Updates.start, which
            // startUi calls, checks after 30 s on Windows and macOS)
            Updates.autoCheck().set(false)
            startUi(journey.dirs)
            // The source's grid, as before Android's "Replace latest button" (on by default) opened its feed
            Injekt.get<eu.kanade.domain.ui.UiPreferences>().useNewSourceNavigation().set(false)
            AppScope.launch {
                Toasts.flow.collect {
                    println("Journey toast: $it")
                    journey.toasts += it
                }
            }
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

    /**
     * The window's root. A toast or tooltip is a popup with a root of its own, where onRoot()
     * fails with "found 2 nodes"; the first root is the window's.
     */
    fun ComposeUiTest.root() = onAllNodes(isRoot()).onFirst()

    fun ComposeUiTest.shot(title: String) {
        waitForIdle()
        ImageIO.write(root().captureToImage().toAwtImage(), "png", File(shots, "$name-$title.png"))
    }

    /** The reader's page images that are on screen: off-screen pages have no visible bounds. */
    private fun ComposeUiTest.pagesOnScreen(): List<SemanticsNode> =
        onAllNodesWithTag("page-image").fetchSemanticsNodes().filter { node ->
            val bounds = node.boundsInRoot
            bounds.width > 0f && bounds.height > 0f && bounds.right > 0f && bounds.left < WIDTH && bounds.top < HEIGHT
        }

    /**
     * Whether [page] drew: [minShare] or more of an on-screen page image is its colour (a quarter
     * by default). A page that failed to decode shows the reader's error or a blank, and a page
     * still loading its spinner, so none of them pass.
     */
    fun ComposeUiTest.pageDrew(page: FixturePage, minShare: Double = 0.25): Boolean = pageShare(page) >= minShare

    /** The largest share of an on-screen page image that is [page]'s colour; 0 with none on screen. */
    fun ComposeUiTest.pageShare(page: FixturePage): Double {
        val window = runCatching { root().captureToImage().toAwtImage() }.getOrNull() ?: return 0.0
        return pagesOnScreen().maxOfOrNull { node -> share(window, node, page) } ?: 0.0
    }

    /**
     * Reads [pages] in order in the paged reader: waits for each to draw, turning the page (Left,
     * the next page right to left) when it is not on screen yet. A wide window shows two pages
     * side by side, so one turn can bring two of them.
     */
    fun ComposeUiTest.readThrough(pages: List<FixturePage>, label: String, minShare: Double = 0.25) {
        pages.forEachIndexed { index, page ->
            var turns = 0
            while (true) {
                // A page after the first is often one turn away: look briefly before turning; the
                // first page, and a page after a turn, get time to load and decode
                val timeout = if (index > 0 && turns == 0) 2_000L else 15_000L
                val drew = runCatching { waitUntil(timeoutMillis = timeout) { pageDrew(page, minShare) } }.isSuccess
                if (drew) break
                println("Journey $name: page ${index + 1} (${page.file}) is ${pageShare(page)} its colour, turn $turns")
                if (turns++ >= 2) {
                    shot("$label-page-${index + 1}-failed")
                    waitOrDump("for page ${index + 1} (${page.file}) to draw", timeout = 1) { pageDrew(page, minShare) }
                }
                root().performKeyInput { pressKey(Key.DirectionLeft) }
            }
            shot("$label-page-${index + 1}")
        }
    }

    /** The share of [node]'s area in [window] that is [page]'s colour. */
    private fun share(window: BufferedImage, node: SemanticsNode, page: FixturePage): Double {
        val bounds = node.boundsInRoot
        return share(
            window,
            page,
            bounds.left.toInt().coerceIn(0, window.width),
            bounds.top.toInt().coerceIn(0, window.height),
            bounds.right.toInt().coerceIn(0, window.width),
            bounds.bottom.toInt().coerceIn(0, window.height),
        )
    }

    /** The share of a whole decoded [image] that is [page]'s colour, for images straight from the server. */
    fun share(image: BufferedImage, page: FixturePage): Double = share(image, page, 0, 0, image.width, image.height)

    private fun share(window: BufferedImage, page: FixturePage, left: Int, top: Int, right: Int, bottom: Int): Double {
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
     * does, for journeys about what comes after. Found by searching, so search-only titles too.
     */
    fun addToLibrary(title: String): Manga = runBlocking {
        val listed = source.getSearchManga(1, title, FilterList()).mangas.single { it.title == title }
        val manga = Injekt.get<NetworkToLocalManga>()(listOf(listed.toDomainManga(source.id))).single()
        Injekt.get<MangaRepository>().update(MangaUpdate(id = manga.id, favorite = true, dateAdded = System.currentTimeMillis()))
        Injekt.get<RefreshManga>()(Injekt.get<MangaRepository>().getMangaById(manga.id), source = source).manga
    }

    fun close() {
        Injekt.get<BuiltInSources>().testSources.value = emptyList()
        server.close()
        // The database stays open until the JVM exits, so this removes what it can; the JVM is
        // the journey's own (forkEvery = 1)
        dirs.root.deleteRecursively()
    }
}
