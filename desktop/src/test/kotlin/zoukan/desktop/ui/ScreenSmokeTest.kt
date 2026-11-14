package zoukan.desktop.ui

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.all.MangaDex
import exh.md.utils.MdUtil
import exh.source.EnhancedHttpSource
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mihon.domain.manga.model.toDomainManga
import org.junit.jupiter.api.Test
import tachiyomi.domain.category.interactor.CreateCategoryWithName
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.sy.SYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.ZoukanRuntime
import zoukan.desktop.backup.DesktopBackups
import zoukan.desktop.compat.localized
import zoukan.desktop.download.CbzWriter
import zoukan.desktop.exh.BuiltInSources
import zoukan.desktop.library.LibraryUpdater
import zoukan.desktop.local.LocalSource
import zoukan.desktop.secrets.SecretStore
import zoukan.desktop.security.AppLock
import zoukan.desktop.storage.ZoukanStorage
import zoukan.desktop.track.TrackingModel
import zoukan.desktop.ui.browse.ExtensionDetailsScreen
import zoukan.desktop.ui.browse.FeedOrderDialog
import zoukan.desktop.ui.browse.FeedScreenModel
import zoukan.desktop.ui.browse.GlobalSearchScreen
import zoukan.desktop.ui.browse.MigrateMangaScreen
import zoukan.desktop.ui.browse.OpenUrlDialog
import zoukan.desktop.ui.browse.OpenUrlScreen
import zoukan.desktop.ui.browse.QuickLibraryToggleDialogs
import zoukan.desktop.ui.browse.SourceCategoriesDialog
import zoukan.desktop.ui.browse.SourceFeedScreen
import zoukan.desktop.ui.browse.SourceScreen
import zoukan.desktop.ui.browse.rememberQuickLibraryToggle
import zoukan.desktop.ui.common.DesktopPreferences
import zoukan.desktop.ui.library.LibraryScreenModel
import zoukan.desktop.ui.library.LibrarySettingsDialog
import zoukan.desktop.ui.library.SyncFavoritesConfirmDialog
import zoukan.desktop.ui.library.SyncFavoritesNotesDialog
import zoukan.desktop.ui.manga.CategoryDialog
import zoukan.desktop.ui.manga.ChangeCategoryDialog
import zoukan.desktop.ui.manga.ChapterSettingsDialog
import zoukan.desktop.ui.manga.ClearMangaDialog
import zoukan.desktop.ui.manga.CoverDialog
import zoukan.desktop.ui.manga.CustomDownloadDialog
import zoukan.desktop.ui.manga.DuplicateDialog
import zoukan.desktop.ui.manga.EditMangaDialog
import zoukan.desktop.ui.manga.FetchIntervalDialog
import zoukan.desktop.ui.manga.MangaScreen
import zoukan.desktop.ui.manga.MangaScreenModel
import zoukan.desktop.ui.manga.MergedEntriesDialog
import zoukan.desktop.ui.manga.MetadataViewScreen
import zoukan.desktop.ui.manga.MigrateDialog
import zoukan.desktop.ui.manga.PagePreviewScreen
import zoukan.desktop.ui.manga.PickDialog
import zoukan.desktop.ui.manga.SearchPurpose
import zoukan.desktop.ui.migration.MigrationConfigScreen
import zoukan.desktop.ui.migration.MigrationListScreen
import zoukan.desktop.ui.onboarding.FirstRun
import zoukan.desktop.ui.onboarding.Onboarding
import zoukan.desktop.ui.reader.PageActionsDialog
import zoukan.desktop.ui.reader.PageActionsTarget
import zoukan.desktop.ui.reader.ReaderChapterListDialog
import zoukan.desktop.ui.reader.ReaderScreen
import zoukan.desktop.ui.reader.ReaderScreenModel
import zoukan.desktop.ui.reader.ReaderSettingsDialog
import zoukan.desktop.ui.recs.CommonRecommendsScreen
import zoukan.desktop.ui.recs.RecommendationSearchDialog
import zoukan.desktop.ui.recs.RecommendationSearchProgressDialog
import zoukan.desktop.ui.recs.RecommendsScreen
import zoukan.desktop.ui.recs.SearchStatus
import zoukan.desktop.ui.settings.CategoriesIncludeExcludeDialog
import zoukan.desktop.ui.settings.ChoiceSetting
import zoukan.desktop.ui.settings.MultiSelectPreference
import zoukan.desktop.ui.settings.RestoreBackupScreen
import zoukan.desktop.ui.settings.SettingsCategory
import zoukan.desktop.ui.settings.SourceSettingsScreen
import zoukan.desktop.ui.settings.openSettingsCategory
import zoukan.desktop.ui.track.TrackingDialog
import zoukan.desktop.ui.whatsnew.UpdatedDialog
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Opens every screen and dialog of the desktop app, offline and with a throwaway data folder, and
 * fails if any of them throws: in composition, in a LaunchedEffect, in a screen model's coroutines
 * or on any other thread (a default uncaught exception handler records those), or with Injekt's
 * "No registered instance or factory" anywhere in the log, even where the app caught it. Each
 * failure names the screen or dialog that was open. A screen can compile and still crash when it
 * opens, as two screens did that looked up Injekt types the desktop never registered.
 *
 * The library holds two entries of the local source, with chapters, and one category, so the
 * entry, reader and migration screens have something to show. What is opened:
 *
 * - Every Voyager screen of the app. The screens are found in the app's compiled classes, so a
 *   new screen is covered without a change here: an object screen or a class without arguments
 *   is created as it is, and a screen with arguments must be in [screensWithArguments], or the
 *   test fails and names it. Each screen's lists are scrolled to the end, so every row composes.
 * - The five home tabs and every page of the Browse tab.
 * - Every Settings page, and Settings search, which lists every page's settings at once.
 *   MangaDex's page only shows with a MangaDex source, so its check installs an offline stand-in
 *   for MangaDex while it runs (see [MangaDexStandIn]), and fails unless the page's rows show.
 * - The dialogs that other code can call: rendered directly, with the seeded entries and real
 *   screen models (see [dialogs]). The settings dialogs (ChoiceSetting, MultiSelectPreference)
 *   are opened by clicking their row. Dialogs private to a screen are opened through the UI where
 *   a click reaches them (see [uiDialogs]): the settings download password, the Category screen's
 *   new category, the sort tags' new tag, the library's filter sheet. The app lock and the
 *   first-run setup are shown too.
 *
 * Not opened: dialogs private to a screen that need a state the offline setup can't reach (an
 * installed extension, a tracker login, a source with preferences, an E-Hentai or MangaDex
 * account), and anything that opens a native file dialog.
 *
 * Starts the runtime, which can run only once per JVM, so it runs in a JVM of its own through
 * `wiringTest`, which `check` and `build` run (on Linux without a display, under xvfb-run -a):
 *
 *   ./gradlew :desktop:wiringTest
 *   ./gradlew :desktop:wiringTest --tests "*ScreenSmokeTest" -Pzoukan.smoke.only="dialog new category"
 *
 * A failed check prints the semantics tree that was on screen, to tell a crash from a test that
 * looked for the wrong node.
 */
@OptIn(ExperimentalTestApi::class)
class ScreenSmokeTest {

    private companion object {
        /** Checks per Compose test: about a second each, well within its one-minute limit. */
        const val BATCH = 15

        /** Rows per scroll step: fewer than a screen holds, so every row composes on the way. */
        const val SCROLL_STEP = 6

        /** Bounds a list's scrolling, at 3,000 rows. */
        const val MAX_SCROLL_STEPS = 500

        val OUT_OF_BOUNDS = Regex("""^Can't scroll to index -?\d+, it is out of bounds \[0, (\d+)\)""")
    }

    private class Fixture(val alpha: Manga, val bravo: Manga, val chapters: List<Chapter>, val backup: File)

    /**
     * One screen or dialog to open: [open] runs on the UI thread, then [drive] in the test, and
     * [cleanup] on the UI thread even when they fail, to undo state that later checks would see.
     */
    private class Check(
        val name: String,
        val open: (Navigator) -> Unit,
        val drive: ComposeUiTest.() -> Unit = {},
        val cleanup: () -> Unit = {},
    )

    /** A screen that only shows [content], to render a dialog with a navigator and screen models around it. */
    private class Host(private val name: String, @Transient private val content: @Composable Screen.() -> Unit) : Screen {
        override val key: ScreenKey = "smoke-host-$name/"

        @Composable
        override fun Content() = content()
    }

    @Volatile
    private var current = "starting"
    private val failures = Collections.synchronizedList(mutableListOf<String>())

    @Test
    fun `every screen and dialog opens without an error`() {
        System.setProperty(SecretStore.FILE_STORE_PROPERTY, "true")
        val dirs = ZoukanDirs(Files.createTempDirectory("zoukan-smoke").toFile())
        ZoukanRuntime.start(dirs, interactiveBrowser = false)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        // A background thread's error can come after the check that started its work has closed
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            failAt("during or after $current", "uncaught on thread ${thread.name}", e)
            previous?.uncaughtException(thread, e)
        }
        try {
            startUi(dirs)
            val fixture = seed()

            val screens = screens(fixture)
            val all = screens + tabs() + settings() + dialogs(fixture) + uiDialogs()
            println("Smoke: ${screens.size} screens, ${all.size} checks")
            // -Pzoukan.smoke.only=<regex> runs only the matching checks, to debug one
            val only = System.getProperty("zoukan.smoke.only")?.toRegex()
            val checks = if (only == null) all else all.filter { only.containsMatchIn(it.name) }
            // A filter that matches nothing would otherwise pass without opening anything
            check(checks.isNotEmpty()) { "no check matches $only; checks are:\n" + all.joinToString("\n") { it.name } }
            runChecks(checks)

            current = "log"
            // The log and the three older ones it rolls into, as Settings > Advanced shares them
            (listOf(File(dirs.logs, "zoukan.log")) + (1..3).map { File(dirs.logs, "zoukan.$it.log") })
                .filter { it.isFile }
                .flatMap { log -> log.readLines().filter { "No registered instance or factory" in it }.map { "${log.name}: $it" } }
                .forEach { failures += "log: $it" }
            failures.shouldBeEmpty()
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    private fun fail(where: String, e: Throwable) = failAt(current, where, e)

    private fun failAt(what: String, where: String, e: Throwable) {
        println("Smoke: FAILED in $what ($where): $e")
        e.printStackTrace()
        failures += "$what ($where): $e"
    }

    /**
     * Runs the checks [BATCH] at a time in one Compose test, and in a new one after a check that
     * failed, since a composition that threw can't go on.
     */
    private fun runChecks(checks: List<Check>) {
        val preferences = Injekt.get<DesktopPreferences>()
        var next = 0
        var batch = 0
        while (next < checks.size) {
            batch++
            var failed = false
            // The check that was open, until it's done: an error outside a check (setting the
            // content up, or the Compose test's end) is the batch's, and skips no check
            var open = -1
            val start = next
            try {
                runDesktopComposeUiTest(width = 1280, height = 820, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
                    var navigator: Navigator? = null
                    setContent { ZoukanContent(preferences) { navigator = it } }
                    waitForIdle()
                    // A Compose test's body must finish within a minute (kotlinx-coroutines-test's
                    // default, which testTimeout doesn't lift here), so a new one every few checks
                    val end = minOf(next + BATCH, checks.size)
                    while (next < end) {
                        val check = checks[next]
                        current = check.name
                        open = next
                        println("Smoke: ${check.name}")
                        try {
                            try {
                                runOnUiThread { check.open(navigator!!) }
                                settle()
                                check.drive(this)
                                scrollLists()
                                settle()
                            } finally {
                                runOnUiThread { check.cleanup() }
                            }
                            runOnUiThread {
                                navigator!!.popUntilRoot()
                                HomeScreen.selectTab(HomeTab.Library)
                            }
                            waitForIdle()
                        } catch (e: Throwable) {
                            fail("check", e)
                            // What was on screen, to tell a crash from a test that looked for the wrong node
                            runCatching { onAllNodes(isRoot()).printToString(maxDepth = Int.MAX_VALUE) }.onSuccess { println(it) }
                            failed = true
                            next++
                            throw e
                        }
                        next++
                        open = -1
                    }
                }
            } catch (e: Throwable) {
                // A check's failure is thrown again here; anything else came from the Compose test
                // itself: while a check was open (its failure), or outside one (the batch's)
                if (!failed) {
                    if (open == next) {
                        fail("Compose test", e)
                        next++
                    } else {
                        val after = if (next > start) ", after ${checks[next - 1].name}" else ""
                        failAt("batch $batch setup/teardown$after", "Compose test", e)
                        // Nothing could open: running on would fail the same way for every check
                        if (next == start) {
                            failures += "${checks.size - next} checks not run, from ${checks[next].name}"
                            return
                        }
                    }
                }
            }
        }
    }

    /** Lets screen models load on their dispatchers and the UI catch up. */
    private fun ComposeUiTest.settle() {
        waitForIdle()
        repeat(3) {
            Thread.sleep(150)
            waitForIdle()
        }
    }

    /**
     * Scrolls every lazy list on screen to its end, so each of its rows composes. A lazy list
     * doesn't report its length (its CollectionInfo has -1 rows), so this steps through it until
     * Compose's bounds check says an index is past the end, and then goes to the last row.
     */
    private fun ComposeUiTest.scrollLists() {
        val lists = onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().size
        repeat(lists) { index ->
            // Looked up again by index: scrolling can add lists (rows of a feed) after this one
            val list = onAllNodes(hasScrollToIndexAction())[index]
            var row = 0
            var rows: Int? = null
            for (step in 0 until MAX_SCROLL_STEPS) {
                when (val result = scrollTo(list, row)) {
                    is Scroll.Gone -> return@repeat
                    is Scroll.PastEnd -> {
                        rows = result.rows
                        break
                    }
                    is Scroll.Done -> row += SCROLL_STEP
                }
            }
            val count = checkNotNull(rows) { "list $index has more than ${MAX_SCROLL_STEPS * SCROLL_STEP} rows" }
            if (count > 0 && scrollTo(list, count - 1) !is Scroll.Done) return@repeat
            println("  list $index: scrolled through $count rows")
            waitForIdle()
        }
    }

    private sealed interface Scroll {
        data object Done : Scroll

        /** The list is no longer on screen. */
        data object Gone : Scroll

        /** The index was past the end of a list of [rows] rows. */
        data class PastEnd(val rows: Int) : Scroll
    }

    /**
     * Scrolls [list] to [index]. A list that's gone, or an index past its end, is the test's
     * concern, not the app's; anything else, such as a row that throws as it composes, fails.
     */
    private fun scrollTo(list: SemanticsNodeInteraction, index: Int): Scroll = try {
        list.performScrollToIndex(index)
        Scroll.Done
    } catch (e: AssertionError) {
        // Only when the node is no longer in the tree
        if (runCatching { list.fetchSemanticsNode() }.isFailure) Scroll.Gone else throw e
    } catch (e: IllegalArgumentException) {
        // Compose's own bounds check: "Can't scroll to index 12, it is out of bounds [0, 9)"
        val rows = OUT_OF_BOUNDS.find(e.message.orEmpty())?.groupValues?.get(1)?.toInt() ?: throw e
        Scroll.PastEnd(rows)
    }

    // Seeding

    private fun seed(): Fixture {
        current = "seeding"
        val storage = Injekt.get<ZoukanStorage>()
        listOf("Alpha", "Bravo").forEach { title ->
            val dir = File(storage.local, title).apply { mkdirs() }
            (1..2).forEach { cbz(File(dir, "Chapter $it.cbz"), 3, title) }
        }
        Injekt.get<LibraryPreferences>().autoUpdateMangaRestrictions().set(emptySet())
        runBlocking {
            Injekt.get<SourceManager>().isInitialized.first { it }
            Injekt.get<CreateCategoryWithName>().await("Smoke")
            val source = Injekt.get<SourceManager>().get(LocalSource.ID) as CatalogueSource
            val found = source.getPopularManga(1).mangas.map { it.toDomainManga(LocalSource.ID) }
            Injekt.get<NetworkToLocalManga>()(found).forEach { manga ->
                Injekt.get<MangaRepository>().update(MangaUpdate(id = manga.id, favorite = true, dateAdded = System.currentTimeMillis()))
            }
        }
        updateLibrary()
        val favorites = runBlocking { Injekt.get<GetFavorites>().await() }.sortedBy { it.title }
        favorites shouldHaveSize 2
        val chapters = runBlocking { Injekt.get<GetChaptersByMangaId>().await(favorites[0].id) }
        chapters.size shouldBeGreaterThan 0
        val backup = File(ZoukanRuntime.dirs.root, "smoke.tachibk")
        runBlocking { DesktopBackups(ZoukanRuntime.dirs.android).create(backup) }
        return Fixture(favorites[0], favorites[1], chapters, backup)
    }

    /**
     * Runs a library update to completion, so the seeded entries get their chapters. An update
     * already running (one the runtime started) may predate the entries, so this waits for it to
     * end and starts its own.
     */
    private fun updateLibrary() {
        val updater = Injekt.get<LibraryUpdater>()
        val deadline = System.nanoTime() + 60.seconds.inWholeNanoseconds
        fun left() = (deadline - System.nanoTime()).coerceAtLeast(0)
        while (true) {
            val done = CountDownLatch(1)
            if (updater.start(onDone = { done.countDown() })) {
                check(done.await(left(), TimeUnit.NANOSECONDS)) {
                    "the library update didn't finish within 60s (progress: ${updater.progress.value})"
                }
                return
            }
            check(left() > 0) { "the library update couldn't start within 60s: another kept running" }
            Thread.sleep(200)
        }
    }

    private fun cbz(file: File, pages: Int, label: String) {
        CbzWriter(file).use { writer ->
            (1..pages).forEach { i ->
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

    // Screens

    /**
     * The screens that take arguments, with the arguments to open them with. A new screen with
     * arguments goes here; the test fails and names it until it does. Each is made only when its
     * check opens it, so a screen that throws as it's made fails as that screen.
     */
    private fun screensWithArguments(f: Fixture): Map<Class<out Screen>, List<() -> Screen>> = mapOf(
        MangaScreen::class.java to listOf({ MangaScreen(f.alpha.id) }),
        PagePreviewScreen::class.java to listOf({ PagePreviewScreen(f.alpha.id) }),
        MetadataViewScreen::class.java to listOf({ MetadataViewScreen(f.alpha.id, LocalSource.ID) }),
        ReaderScreen::class.java to listOf(
            { ReaderScreen(f.alpha.id, f.chapters.first().id) },
            { ReaderScreen(f.alpha.id, f.chapters.last().id, page = 1) },
        ),
        SourceFeedScreen::class.java to listOf({ SourceFeedScreen(LocalSource.ID) }),
        SourceScreen::class.java to listOf(
            { SourceScreen(LocalSource.ID) },
            { SourceScreen(LocalSource.ID, latest = true) },
            { SourceScreen(LocalSource.ID, query = "Alpha") },
        ),
        GlobalSearchScreen::class.java to listOf(
            { GlobalSearchScreen("Alpha") },
            { GlobalSearchScreen("Bravo", SearchPurpose.Merge(f.alpha.id)) },
            { GlobalSearchScreen("Bravo", SearchPurpose.Migrate(f.alpha.id, LocalSource.ID)) },
            { GlobalSearchScreen("Bravo", SearchPurpose.MigrateInList(f.alpha.id, LocalSource.ID)) },
        ),
        MigrateMangaScreen::class.java to listOf({ MigrateMangaScreen(LocalSource.ID) }),
        ExtensionDetailsScreen::class.java to listOf({ ExtensionDetailsScreen("eu.kanade.tachiyomi.extension.en.notinstalled") }),
        OpenUrlScreen::class.java to listOf({ OpenUrlScreen("https://example.invalid/manga/1") }),
        SourceSettingsScreen::class.java to listOf({ SourceSettingsScreen(LocalSource.ID) }),
        RestoreBackupScreen::class.java to listOf({ RestoreBackupScreen(f.backup.path) }),
        MigrationListScreen::class.java to listOf(
            { MigrationListScreen(listOf(f.alpha.id, f.bravo.id), null) },
            { MigrationListScreen(listOf(f.alpha.id), "Bravo", runManually = true) },
        ),
        MigrationConfigScreen::class.java to listOf({ MigrationConfigScreen(listOf(f.alpha.id, f.bravo.id)) }),
        RecommendsScreen::class.java to listOf({ RecommendsScreen(f.alpha.id) }),
        CommonRecommendsScreen::class.java to listOf({ CommonRecommendsScreen(emptyList()) }),
    )

    private fun screens(f: Fixture): List<Check> {
        val withArguments = screensWithArguments(f)
        val classes = screenClasses()
        // About 30 when this was written; far fewer means the scan stopped finding them
        classes.size shouldBeGreaterThan 20
        val missing = mutableListOf<String>()
        val checks = classes.filter { it != HomeScreen::class.java }.flatMap { type ->
            val makers = withArguments[type] ?: listOfNotNull(maker(type)).ifEmpty {
                missing += type.name
                emptyList()
            }
            makers.mapIndexed { i, make ->
                val variant = if (makers.size > 1) " ${i + 1}" else ""
                Check("screen ${type.simpleName}$variant", { it.push(make()) })
            }
        }
        missing.shouldBeEmpty()
        return checks
    }

    /**
     * How to get a screen of [type] without arguments: an object's instance, or a new one of a
     * class without arguments; null for a class that needs arguments. Nothing is made yet.
     */
    private fun maker(type: Class<out Screen>): (() -> Screen)? {
        runCatching { type.getDeclaredField("INSTANCE") }.getOrNull()
            ?.takeIf { Modifier.isStatic(it.modifiers) }
            ?.let { field -> return { field.apply { isAccessible = true }.get(null) as Screen } }
        return type.declaredConstructors.firstOrNull { it.parameterCount == 0 }
            ?.let { constructor -> { constructor.apply { isAccessible = true }.newInstance() as Screen } }
    }

    /** The app's screen classes, from its compiled classes. */
    @Suppress("UNCHECKED_CAST")
    private fun screenClasses(): List<Class<out Screen>> {
        val root = File(HomeScreen::class.java.protectionDomain.codeSource.location.toURI())
        val loader = javaClass.classLoader
        return root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".class") }
            .map { it.relativeTo(root).path.removeSuffix(".class").replace(File.separatorChar, '.') }
            .mapNotNull { runCatching { Class.forName(it, false, loader) }.getOrNull() }
            .filter { Screen::class.java.isAssignableFrom(it) && !it.isInterface && !Modifier.isAbstract(it.modifiers) }
            .filter { !it.isAnonymousClass && !it.isLocalClass }
            .map { it as Class<out Screen> }
            .sortedBy { it.name }
            .toList()
    }

    // Tabs and settings

    private fun tabs(): List<Check> = HomeTab.entries.map { tab ->
        Check("tab ${tab.name}", { HomeScreen.selectTab(tab) })
    } + Check(
        "Browse pages",
        { HomeScreen.selectTab(HomeTab.Browse) },
        drive = {
            // The Browse tab's own row, not the navigation rail's tabs
            val tab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and hasAnyAncestor(hasTestTag("browse-pages"))
            waitUntil(timeoutMillis = 10_000) { onAllNodes(tab).fetchSemanticsNodes().isNotEmpty() }
            val count = onAllNodes(tab).fetchSemanticsNodes().size
            count shouldBeGreaterThan 3
            repeat(count) { index ->
                current = "Browse page $index"
                onAllNodes(tab)[index].performClick()
                settle()
                scrollLists()
            }
        },
    )

    /**
     * A MangaDex source for one check: the MangaDex extension's stand-in inside the app's enhanced
     * MangaDex, as DelegatedSources wraps the real extension, listed through the SourceManager's
     * test sources while the check runs. Only that check sees it.
     *
     * It is wrapped here rather than by DelegatedSources (which wraps only extensions' sources, not
     * the test sources): that would also count it among the MangaDex sources (mangaDexSourceIds)
     * for the rest of the run, with no way to take it out.
     */
    private class MangaDexStandIn {
        private val testSources get() = Injekt.get<BuiltInSources>().testSources

        fun install() {
            val extension = eu.kanade.tachiyomi.extension.all.mangadex.MangaDex()
            val enhanced = EnhancedHttpSource(extension, MangaDex(extension, Injekt.get<Application>()))
            testSources.value = listOf(enhanced)
            // The SourceManager picks the test sources up on its own thread
            val deadline = System.nanoTime() + 10.seconds.inWholeNanoseconds
            while (MdUtil.getEnabledMangaDexs(Injekt.get()).isEmpty()) {
                check(System.nanoTime() < deadline) { "the SourceManager didn't list the MangaDex stand-in within 10s" }
                Thread.sleep(50)
            }
        }

        fun uninstall() {
            testSources.value = emptyList()
        }
    }

    private fun settings(): List<Check> = SettingsCategory.entries.filter { it != SettingsCategory.MangaDex }.map { page ->
        Check("settings ${page.name}", {
            openSettingsCategory(page)
            HomeScreen.openSettings(it)
        })
    } + listOf(
        run {
            val mangaDex = MangaDexStandIn()
            var navigator: Navigator? = null
            Check(
                "settings MangaDex",
                {
                    navigator = it
                    mangaDex.install()
                    openSettingsCategory(SettingsCategory.MangaDex)
                    HomeScreen.openSettings(it)
                },
                // The page shows its rows only with a MangaDex source. The check waits for its sync
                // row, so it fails rather than pass on an empty page.
                // Clicking the row only opens the follows dialog. Nothing here confirms it, signs in
                // or syncs: those would call MangaDex's servers.
                drive = { clickText(localized(SYMR.strings.mangadex_sync_follows_to_library)) },
                // The page and its dialog close before the source goes away under them
                cleanup = {
                    try {
                        navigator?.popUntilRoot()
                    } finally {
                        mangaDex.uninstall()
                    }
                },
            )
        },
        Check(
            "settings search",
            { HomeScreen.openSettings(it) },
            drive = {
                onAllNodes(hasSetTextAction()).onFirst().performTextInput("e")
                settle()
                scrollLists()
                onAllNodes(hasSetTextAction()).onFirst().performTextClearance()
            },
        ),
    )

    // Dialogs

    private fun dialog(name: String, content: @Composable Screen.() -> Unit) =
        Check("dialog $name", { it.push(Host(name, content)) })

    /** The dialogs that other code can show, rendered directly. */
    private fun dialogs(f: Fixture): List<Check> {
        val categories = runBlocking { Injekt.get<GetCategories>().await() }
        val noop = {}
        return listOf(
            dialog("CategoryDialog") { CategoryDialog(categories, {}, noop) },
            dialog("ChangeCategoryDialog") { ChangeCategoryDialog(categories, setOf(categories.last().id), {}, noop) },
            dialog("PickDialog merge") { PickDialog(SearchPurpose.Merge(f.alpha.id), f.bravo, noop) },
            dialog("PickDialog migrate") { PickDialog(SearchPurpose.Migrate(f.alpha.id, LocalSource.ID), f.bravo, noop) },
            dialog("MigrateDialog") { MigrateDialog(f.alpha.id, f.bravo, noop) },
            dialog("MergedEntriesDialog") { MergedEntriesDialog(f.alpha.id, noop) },
            dialog("ClearMangaDialog") { ClearMangaDialog(noop) { _, _ -> } },
            dialog("FetchIntervalDialog") { FetchIntervalDialog(f.alpha, noop) },
            dialog("CoverDialog") { CoverDialog(f.alpha, noop) },
            dialog("DuplicateDialog") { DuplicateDialog(f.alpha, listOf(MangaWithChapterCount(f.bravo, 2)), noop, {}, {}, noop) },
            dialog("CustomDownloadDialog") { CustomDownloadDialog({}, noop) },
            dialog("EditMangaDialog") { EditMangaDialog(f.alpha, noop) },
            dialog("ChapterSettingsDialog") {
                val model = rememberScreenModel { MangaScreenModel(f.alpha.id) }
                val state by model.state.collectAsState()
                ChapterSettingsDialog(state, model, noop)
            },
            dialog("TrackingDialog") { TrackingDialog(rememberScreenModel { TrackingModel(f.alpha) }, noop) },
            dialog("LibrarySettingsDialog") { LibrarySettingsDialog(rememberScreenModel { LibraryScreenModel() }, noop) },
            dialog("SyncFavoritesNotesDialog") { SyncFavoritesNotesDialog(noop, noop) },
            dialog("SyncFavoritesConfirmDialog") { SyncFavoritesConfirmDialog(noop) },
            dialog("PageActionsDialog") { PageActionsDialog(PageActionsTarget(0), noop, {}, {}, {}, noop, noop) },
            dialog("PageActionsDialog double page") { PageActionsDialog(PageActionsTarget(0, 1), noop, {}, {}, {}, noop, noop) },
            dialog("ReaderChapterListDialog") { ReaderChapterListDialog(f.chapters, f.chapters.first().id, {}, {}, noop) },
            dialog("ReaderSettingsDialog") {
                ReaderSettingsDialog(rememberScreenModel { ReaderScreenModel(f.alpha.id, f.chapters.first().id) }, noop)
            },
            dialog("FeedOrderDialog") { FeedOrderDialog(rememberScreenModel { FeedScreenModel() }, emptyList(), noop) },
            dialog("SourceCategoriesDialog") {
                val source = remember { Injekt.get<SourceManager>().get(LocalSource.ID) as CatalogueSource }
                SourceCategoriesDialog(source, listOf("Smoke", "Other"), setOf("Smoke"), {}, noop, noop)
            },
            dialog("OpenUrlDialog") { OpenUrlDialog(noop) },
            dialog("UpdatedDialog") { UpdatedDialog(noop, noop) },
            dialog("CategoriesIncludeExcludeDialog") {
                CategoriesIncludeExcludeDialog("Smoke", "Smoke", categories, setOf(categories.first().id.toString()), emptySet(), noop) { _, _ -> }
            },
            dialog("RecommendationSearchDialog") { RecommendationSearchDialog(noop, noop) },
        ) + listOf(
            SearchStatus.Initializing,
            SearchStatus.Processing(SManga.create().apply { title = "Alpha" }, 1, 3),
            SearchStatus.Error("Smoke"),
            SearchStatus.Cancelling,
        ).map { status ->
            dialog("RecommendationSearchProgressDialog ${status.javaClass.simpleName}") { RecommendationSearchProgressDialog(status, noop, noop) }
        } + listOf(
            dialog("QuickLibraryToggleDialogs remove") {
                val toggle = rememberQuickLibraryToggle()
                remember { toggle.removing = f.alpha }
                QuickLibraryToggleDialogs(toggle)
            },
            dialog("QuickLibraryToggleDialogs duplicates") {
                val toggle = rememberQuickLibraryToggle()
                remember { toggle.duplicates = f.bravo to listOf(MangaWithChapterCount(f.alpha, 2)) }
                QuickLibraryToggleDialogs(toggle)
            },
            dialog("QuickLibraryToggleDialogs categories") {
                val toggle = rememberQuickLibraryToggle()
                remember { toggle.categories = f.bravo to categories }
                QuickLibraryToggleDialogs(toggle)
            },
            dialog("QuickLibraryToggleDialogs migrate") {
                val toggle = rememberQuickLibraryToggle()
                remember { toggle.migrating = f.bravo to f.alpha }
                QuickLibraryToggleDialogs(toggle)
            },
            Check(
                "dialog ChoiceSetting",
                { it.push(Host("choice") { ChoiceSetting("Smoke choice", 1, listOf(1 to "One", 2 to "Two")) {} }) },
                drive = { onAllNodesWithText("Smoke choice").onFirst().performClick() },
            ),
            Check(
                "dialog MultiSelectPreference",
                {
                    it.push(
                        Host("multi") {
                            val preference = remember { Injekt.get<LibraryPreferences>().autoUpdateDeviceRestrictions() }
                            MultiSelectPreference("Smoke choices", preference, listOf("wifi" to "Wi-Fi", "ac" to "Charging"))
                        },
                    )
                },
                drive = { onAllNodesWithText("Smoke choices").onFirst().performClick() },
            ),
            run {
                val shown = Injekt.get<DesktopPreferences>().shownOnboardingFlow()
                val before = shown.get()
                Check(
                    "dialog first-run setup",
                    {
                        it.push(
                            Host("first-run") {
                                val preferences = remember { Injekt.get<DesktopPreferences>().also { it.shownOnboardingFlow().set(false) } }
                                FirstRun(preferences) { null }
                            },
                        )
                    },
                    // Not set back, the app would show the first-run setup over every later check
                    cleanup = { shown.set(before) },
                )
            },
            Check(
                "onboarding reopened",
                { Onboarding.reopen.value = true },
                cleanup = { Onboarding.reopen.value = false },
            ),
        )
    }

    /** Dialogs private to a screen, opened through the UI. */
    private fun uiDialogs(): List<Check> = listOf(
        Check(
            "app lock",
            {
                val lock = Injekt.get<AppLock>()
                lock.setPassword("smoke")
                Injekt.get<SecurityPreferences>().useAuthenticator().set(true)
                lock.onStart()
            },
            // Left locked, every later check would render the lock screen and nothing else
            cleanup = {
                val lock = Injekt.get<AppLock>()
                try {
                    lock.unlock("smoke")
                } finally {
                    Injekt.get<SecurityPreferences>().useAuthenticator().set(false)
                    lock.clearPassword()
                }
            },
        ),
        Check(
            "dialog download password",
            {
                openSettingsCategory(SettingsCategory.Downloads)
                HomeScreen.openSettings(it)
            },
            drive = { clickText("Set the download password") },
        ),
        Check(
            "dialog new category",
            { it.push(zoukan.desktop.ui.category.CategoryScreen) },
            drive = {
                clickTag("category-add")
                waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag("category-name")).fetchSemanticsNodes().isNotEmpty() }
            },
        ),
        Check(
            "dialog new sort tag",
            { it.push(zoukan.desktop.ui.library.SortTagScreen) },
            drive = { clickDescription("Add a tag") },
        ),
        Check(
            "library filter sheet",
            { HomeScreen.selectTab(HomeTab.Library) },
            drive = {
                clickDescription("Filter, sort and display")
                listOf("Sort", "Display").forEach {
                    settle()
                    clickText(it)
                }
            },
        ),
    )

    private fun ComposeUiTest.clickText(text: String, substring: Boolean = false) {
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithText(text, substring = substring).onFirst().performClick()
        settle()
    }

    private fun ComposeUiTest.clickTag(tag: String) {
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
        onAllNodes(hasTestTag(tag)).onFirst().performClick()
        settle()
    }

    private fun ComposeUiTest.clickDescription(description: String) {
        val matcher = SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(description))
        waitUntil(timeoutMillis = 10_000) { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        onAllNodes(matcher).onFirst().performClick()
        settle()
    }
}
