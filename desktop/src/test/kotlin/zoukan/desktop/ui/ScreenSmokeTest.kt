package zoukan.desktop.ui

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
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
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.ThemeMode
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.sync.SyncManager
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.all.MangaDex
import exh.md.utils.MdUtil
import exh.source.EnhancedHttpSource
import exh.source.ExhPreferences
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.ZoukanRuntime
import zoukan.desktop.app.DesktopPreferences
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
import zoukan.desktop.ui.browse.BrowsePage
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
import zoukan.desktop.update.Updates
import zoukan.i18n.desktop.DMR
import java.awt.EventQueue
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
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
 *   is created as it is, and a screen with arguments must be in [SmokeChecks.screens], or the
 *   test fails and names it. Each screen's lists are scrolled to the end, so every row composes.
 * - The five home tabs and every page of the Browse tab, each page opened by its BrowsePage name.
 * - Every Settings page, and Settings search, which lists every page's settings at once.
 *   MangaDex's page only shows with a MangaDex source, so its check installs an offline stand-in
 *   for MangaDex while it runs (see SmokeChecks.MangaDexStandIn), and fails unless the page's rows show.
 * - The dialogs that other code can call: rendered directly, with the seeded entries and real
 *   screen models (see [SmokeChecks.dialogs]). The settings dialogs (ChoiceSetting, MultiSelectPreference)
 *   are opened by clicking their row. Dialogs private to a screen are opened through the UI where
 *   a click reaches them (see [SmokeChecks.uiDialogs]): the settings download password, the Category screen's
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
 * -Pzoukan.smoke.only matches a check's name or its coverage-matrix key ("browse:", "dialog:manga.CoverDialog").
 * Every check carries that key, and the test fails when a screen, tab or page has no check, or
 * when coverage-matrix.yaml says "smoke: true" for a key no check has.
 *
 * A failed check prints the semantics tree that was on screen, to tell a crash from a test that
 * looked for the wrong node.
 *
 * Each check also runs the usability rules (see [usabilityChecks]) in the light and the dark theme:
 * a new unlabelled clickable, clipped text or dialog that Escape cannot close fails the run, Tab
 * traversal is only reported. [ScreenSmokeSmallWindowTest] runs the same checks at the app's minimum window
 * size, in a JVM of its own beside this one. Failures name the window size: "[800x560] tab Library".
 */
@OptIn(ExperimentalTestApi::class)
open class ScreenSmokeTest {

    /** The window the checks run in; [ScreenSmokeSmallWindowTest] runs them at the smallest one the app allows. */
    protected open val width = 1280
    protected open val height = 820

    /** A small-window run: it skips scrolling the lists, which the default run already does. */
    protected open val small = false

    /** The window size, named in failures and usability findings. */
    private val size get() = "${width}x$height"

    private companion object {
        /** Checks per Compose test: about a second each, well within its one-minute limit. */
        const val BATCH = 15
    }

    @Volatile
    private var current = "starting"
    private val failures = Collections.synchronizedList(mutableListOf<String>())

    /** Usability problems: reported in build/reports/usability/<size>.md; the new ones of the enforced rules fail the run (see [enforceFailures]). */
    private val usabilityFindings = mutableListOf<UsabilityFinding>()

    /** Null unless -Pzoukan.screenshots=verify|record: then no screenshot is taken, which costs nothing. */
    private val screenshots = ScreenshotRun.fromProperty()

    /** Screens that differ from their golden: reported in build/reports/screenshots/report.md, and a failure at the end in verify mode only. */
    private val screenshotFindings = mutableListOf<ScreenshotFinding>()

    /** Takes the curated screens only, once per key and variant, after the usability rules that only read the screen. */
    private fun ComposeUiTest.screenshot(key: String, variant: String) {
        val run = screenshots ?: return
        if (key !in ScreenshotKeys.curated) return
        run.check(captureAllRoots(), variant, key, ScreenshotKeys.masks(key, this))?.let { screenshotFindings += it }
    }

    @Test
    fun `Dispatchers Main runs on the AWT event thread`() {
        Dispatchers.Main.immediate.javaClass.name shouldContain "Swing"
        // The timeout turns a looper that never runs into a failure instead of a hung build
        runBlocking { withTimeout(10.seconds) { withContext(Dispatchers.Main) { EventQueue.isDispatchThread() } } } shouldBe true
    }

    @Test
    fun `every screen and dialog opens without an error`() {
        val dirs = SmokeChecks.startRuntime("zoukan-smoke")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        // A background thread's error can come after the check that started its work has closed
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            failAt("during or after $current", "uncaught on thread ${thread.name}", e)
            previous?.uncaughtException(thread, e)
        }
        try {
            startUi(dirs)
            current = "seeding"
            val fixture = SmokeChecks.seed()

            val screens = SmokeChecks.screens(fixture)
            val all = SmokeChecks.all(fixture, screens)
            println("Smoke: ${screens.size} screens, ${all.size} checks")
            // Before the filter below, so a run that debugs one check still holds these
            val keys = all.flatMap { listOf(it.key) + it.alsoCovers }.toSet()
            val matrix = CoverageMatrix.load()
            // Every key listed, so one run shows all the drift
            fun mustBeEmpty(what: String, missing: Collection<String>) =
                check(missing.isEmpty()) { "$what:\n" + missing.sorted().joinToString("\n") { "  $it" } }
            // A screen, tab or page that no check opens
            val discovered = UiInventory.screenClasses().filter { it != HomeScreen::class.java }.map(UiInventory::screenKey) +
                UiInventory.enumKeys()
            mustBeEmpty("screens, tabs or pages that no smoke check opens", discovered.toSet() - keys)
            // A coverage-matrix row that says "smoke: true" but no check has its key
            mustBeEmpty("coverage-matrix rows with smoke: true that no smoke check has", matrix.filter { it.smoke }.map { it.key }.toSet() - keys)
            // A check whose key has no coverage-matrix row
            mustBeEmpty("smoke check keys with no row in coverage-matrix.yaml", keys - matrix.map { it.key }.toSet())
            // A curated screenshot key that no check has would never be compared
            mustBeEmpty("screenshot keys (ScreenshotKeys.curated) that no smoke check has", ScreenshotKeys.curated - keys)
            // A malformed allowlist (no reason, unknown rule, a key with no matrix row) is a real failure
            val allowlist = UsabilityAllowlist.load(matrix.map { it.key })
            // -Pzoukan.smoke.only=<regex> runs only the checks whose key or name matches, to debug one
            val only = System.getProperty("zoukan.smoke.only")?.toRegex()
            val checks = if (only == null) all else all.filter { only.containsMatchIn(it.name) || only.containsMatchIn(it.key) }
            // A filter that matches nothing would otherwise pass without opening anything
            check(checks.isNotEmpty()) { "no check matches $only; checks are:\n" + all.joinToString("\n") { "${it.key}  ${it.name}" } }
            runChecks(checks)

            current = "log"
            // The log and the three older ones it rolls into, as Settings > Advanced shares them
            (listOf(File(dirs.logs, "zoukan.log")) + (1..3).map { File(dirs.logs, "zoukan.$it.log") })
                .filter { it.isFile }
                .flatMap { log -> log.readLines().filter { "No registered instance or factory" in it }.map { "${log.name}: $it" } }
                .forEach { failures += "log: $it" }
            // One file per window size: the default and the small-window runs are separate JVMs, side by side
            val report = File("build/reports/usability/$size.md")
            println("Usability: ${usabilityFindings.size} findings, in $report")
            val classified = classify(usabilityFindings, allowlist)
            writeUsabilityReport(classified, report)
            // A new finding of an enforced rule fails the run; Tab traversal stays in the report only
            failures += enforceFailures(classified)
            screenshots?.let { run ->
                // A filtered run skips screens on purpose; a full one must take every curated screen
                if (only == null) screenshotFindings += run.notTaken(ScreenshotKeys.curated, listOf("$size/light", "$size/dark"))
                run.writeReport(size, screenshotFindings)
                println("Screenshots: ${screenshotFindings.size} differ, in build/reports/screenshots/report.md")
                // Collected over the whole run, so one failure here never ended a batch; only verify fails
                if (run.compares) screenshotFindings.forEach { failures += "screenshot [${it.variant}] ${it.key}: ${it.message}" }
            }
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
    private fun runChecks(checks: List<SmokeCheck>) {
        val preferences = Injekt.get<DesktopPreferences>()
        // Light, not SYSTEM: the result must not depend on the machine's theme. The dark pass of each check switches it.
        val ui = Injekt.get<UiPreferences>()
        ui.themeMode().set(ThemeMode.LIGHT)
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
                runDesktopComposeUiTest(width = width, height = height, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
                    var navigator: Navigator? = null
                    setContent {
                        // Escape as the window turns it into Back, for the usability check's Escape rule
                        InstallEscapeAsBack()
                        ZoukanContent(preferences) { navigator = it }
                    }
                    waitForIdle()
                    // A Compose test's body must finish within a minute (kotlinx-coroutines-test's
                    // default, which testTimeout doesn't lift here), so a new one every few checks
                    val end = minOf(next + BATCH, checks.size)
                    while (next < end) {
                        val check = checks[next]
                        current = "[$size] ${check.name}"
                        open = next
                        println("Smoke: $current")
                        try {
                            try {
                                runOnUiThread { check.open(navigator!!) }
                                settle()
                                check.drive(this)
                                // What the user sees first, before the lists scroll; findings never fail the
                                // check, but an exception while the theme switches does, as in any check.
                                // The order matters: the rules that only read the screen run in light and
                                // then in dark, and the ones that press Tab and Escape last, in light,
                                // because Escape may close the dialog.
                                usabilityFindings += staticUsabilityChecks(check.key, "$size/light")
                                screenshot(check.key, "$size/light")
                                // The theme preference reaches ZoukanTheme as Compose goes idle, so
                                // waitForIdle is enough here; settle's sleeps would add ~1 s per check
                                try {
                                    // A failure while dark names the dark variant: "[1280x820/dark] tab Library"
                                    current = "[$size/dark] ${check.name}"
                                    ui.themeMode().set(ThemeMode.DARK)
                                    waitForIdle()
                                    usabilityFindings += staticUsabilityChecks(check.key, "$size/dark")
                                    screenshot(check.key, "$size/dark")
                                } finally {
                                    // Light again for the next checks even when the dark pass threw; waiting
                                    // here would throw again over a broken composition and hide the first error
                                    ui.themeMode().set(ThemeMode.LIGHT)
                                }
                                waitForIdle()
                                current = "[$size] ${check.name}"
                                usabilityFindings += interactiveUsabilityChecks(check.key, "$size/light", check.probe)
                                // The default run scrolls every list; the small window adds nothing there
                                if (!small) scrollLists()
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
}
