package zoukan.desktop.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.ThemeMode
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.secrets.SecretStore
import zoukan.desktop.update.Updates
import java.awt.EventQueue
import java.io.File
import java.util.Collections
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
 * -Pzoukan.smoke.only matches a check's name or its key ("browse:", "dialog:manga.CoverDialog").
 * Every check carries that key, and the test fails when a discovered screen, tab or page has no check.
 *
 * A failed check prints the semantics tree that was on screen, to tell a crash from a test that
 * looked for the wrong node.
 */
@OptIn(ExperimentalTestApi::class)
class ScreenSmokeTest {

    private companion object {
        const val WIDTH = 1280
        const val HEIGHT = 820

        /** Checks per Compose test: about a second each, well within its one-minute limit. */
        const val BATCH = 15
    }

    @Volatile
    private var current = "starting"
    private val failures = Collections.synchronizedList(mutableListOf<String>())

    @Test
    fun `Dispatchers Main runs on the AWT event thread`() {
        Dispatchers.Main.immediate.javaClass.name shouldContain "Swing"
        // The timeout turns a looper that never runs into a failure instead of a hung build
        runBlocking { withTimeout(10.seconds) { withContext(Dispatchers.Main) { EventQueue.isDispatchThread() } } } shouldBe true
    }

    @Test
    fun `every screen and dialog opens without an error`() {
        val dirs = SmokeChecks.startRuntime("zoukan-smoke")
        // What's new would read the release notes from GitHub: a fixed release keeps the run offline
        Updates.checkerForTests = SmokeReleaseNotes.checker(Injekt.get<SecretStore>())
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
            // Before the filter below, so a run that debugs one check still holds this
            val keys = all.map { it.key }.toSet()
            // A screen, tab or page that no check opens
            val discovered = UiInventory.screenClasses().filter { it != HomeScreen::class.java }.map(UiInventory::screenKey) +
                UiInventory.enumKeys()
            val missing = discovered.toSet() - keys
            check(missing.isEmpty()) {
                "screens, tabs or pages that no smoke check opens:\n" + missing.sorted().joinToString("\n") { "  $it" }
            }
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
        // Light, not SYSTEM: the result must not depend on the machine's theme.
        Injekt.get<UiPreferences>().themeMode().set(ThemeMode.LIGHT)
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
                runDesktopComposeUiTest(width = WIDTH, height = HEIGHT, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
                    var navigator: Navigator? = null
                    setContent {
                        ZoukanContent(preferences) { navigator = it }
                    }
                    waitForIdle()
                    // A Compose test's body must finish within a minute (kotlinx-coroutines-test's
                    // default, which testTimeout doesn't lift here), so a new one every few checks
                    val end = minOf(next + BATCH, checks.size)
                    while (next < end) {
                        val check = checks[next]
                        current = check.name
                        open = next
                        println("Smoke: $current")
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
}
