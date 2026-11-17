package zoukan.desktop.ui

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.InternalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import cafe.adriel.voyager.navigator.Navigator
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.ThemeMode
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.matchers.collections.shouldBeEmpty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.domain.manga.model.toDomainManga
import okhttp3.Request
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import tachiyomi.domain.category.interactor.CreateCategoryWithName
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.interactor.RemoveHistory
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.DesktopAppModule
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.app.FileRequest
import zoukan.desktop.app.SystemEffects
import zoukan.desktop.app.Toasts
import zoukan.desktop.compat.localized
import zoukan.desktop.library.LibraryUpdater
import zoukan.desktop.tray.Notifications
import zoukan.desktop.ui.manga.MangaScreen
import zoukan.desktop.ui.reader.ReaderKeys
import zoukan.desktop.ui.reader.ReaderScreen
import zoukan.i18n.desktop.DMR
import java.awt.EventQueue
import java.awt.Window
import java.awt.datatransfer.Transferable
import java.io.File
import java.net.URI
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes

/**
 * Clicks every button of the app and checks that each one does something without an error.
 *
 * It opens the smoke test's checks ([SmokeChecks]: every screen, tab, Settings page and dialog),
 * plus a few states whose buttons only show with data (a library selection, selected chapters,
 * the reader's bars). For each check it collects every clickable node on every root (the window,
 * dialogs, menus), scrolling each list to reach its rows. Then, for each button, from a freshly
 * opened check: click it, let the app settle, and look at what changed.
 *
 * A click fails when it throws, when the composition throws, when the app logs an ERROR while it
 * runs, or when the window stops answering. It also fails when nothing observable happened: no
 * navigation, no dialog or menu opened or closed, nothing on screen changed, no setting or
 * database row changed, no toast or notification, no callback of a directly rendered dialog, and
 * no outside effect. Outside effects (a web page, a folder, a file or folder picker, the
 * clipboard, an update's installer, quitting) go to a recorder in place of [SystemEffects], so a
 * click never leaves the test; pickers answer "cancelled". A button that may do nothing on purpose
 * goes in button-crawl-allowlist.txt with the reason.
 *
 * The same pass covers the other inputs (desktop/TESTING.md, 'Other inputs'): right clicks and
 * other custom actions, text fields (type, then Enter or Escape), sliders (maximum,
 * then minimum) and the keyboard shortcuts in [KEYS].
 *
 * A button that opens a menu (a popup that is not a dialog) gets its items clicked too, one level
 * down, and an item that opens a submenu gets the submenu's items clicked ([ButtonCrawl.openedMenu],
 * [ButtonCrawl.MAX_MENU_DEPTH]). Dialogs opened by a click are not crawled: the named ones have
 * checks of their own.
 *
 * Between clicks the settings the click changed are put back, and the seeded library (Alpha and
 * Bravo, their chapters, the Smoke category) is restored when a click changed it. The network goes
 * to a closed local proxy (see buttonCrawlTest in build.gradle.kts), so a click that would reach a
 * server fails the same way on every machine.
 *
 * Each click gets an effect signature: the screen it opened, the dialog by its title, the
 * preferences and database columns it wrote (checked to reach the file and, by reopening the
 * check, the screen again), the download queue and folders, a library update, the outside
 * effects with their arguments checked, toasts and notifications (desktop/TESTING.md, 'Effect
 * signatures'). [Contracts] check the key actions wrote the right row of the seeded library.
 * Every button and its signature are listed in build/reports/coverage/buttons.md and checked
 * against button-inventory.txt: a button that appears, goes or does something else fails the
 * run, so a change in what a button does is made on purpose. Runs in a JVM of its own, through
 * its own task:
 *
 *   ./gradlew :desktop:buttonCrawlTest
 *   ./gradlew :desktop:buttonCrawlTest -Pzoukan.crawl.only="settings:Library"
 *   ./gradlew :desktop:buttonCrawlTest -Pzoukan.shard=2/4
 *   ./gradlew :desktop:buttonCrawlTest -Pzoukan.crawl.updateBaseline=true
 */
@OptIn(ExperimentalTestApi::class)
class ButtonCrawlTest {

    private companion object {
        const val WIDTH = 1280
        const val HEIGHT = 820

        /** A Compose test's body must end within a minute, so a new one starts after this much work. */
        const val BATCH_MILLIS = 35_000L

        /** A click that keeps the UI thread longer than this has hung the window. */
        const val CLICK_TIMEOUT_MILLIS = 20_000L

        /** How long a click with no effect yet gets for a slow one to show. */
        const val SLOW_EFFECT_MILLIS = 2_000L

        /** Rows a list scrolls per step while collecting: fewer than the window shows, so no row is skipped. */
        const val LIST_STEP = 8

        /** Steps per list while collecting: 320 rows, more than any list the seeded app has. */
        const val MAX_LIST_STEPS = 40

        /**
         * Checks not crawled, as their buttons are another check's: Settings search lists the
         * rows of every Settings page, the theme row is the Appearance page, and the Settings
         * screen shows whichever page was open last.
         */
        const val NO_EFFECT = "NO-EFFECT"

        /** The app's preferences file, as the store opens it. */
        const val PREFERENCES = "${DesktopAppModule.PACKAGE_NAME}_preferences"

        /**
         * An error the app logs because the crawl runs offline (HTTP goes to a closed port) or
         * without Chromium: a user on a network sees no such error. Such a click gets "logs:offline".
         */
        val OFFLINE = Regex("""(?i)(connect|refused|unknownhost|proxy|timed? ?out|chromium|unable to resolve|failed to connect|java\.io\.IOException|SocketException)""")

        /** A coroutine's cancellation, logged as an error by shared code that catches every Exception. */
        val CANCELLED = Regex("""\b(Job)?CancellationException\b""")

        /** The entry column a trigger in mangas.sq sets to the current second on each write (see [signatureOf]). */
        const val CLOCK_COLUMN = "db:mangas.favorite_modified_at"

        /** App-state keys that a user switches, as Android keeps incognito mode and Downloaded only: the others are the app's timestamps and caches. */
        val USER_APP_STATE = setOf("incognito_mode", "pref_downloaded_only").map { tachiyomi.core.common.preference.Preference.appStateKey(it) }.toSet()

        val SELECTED = Regex("""\d+ selected""")

        val DUPLICATES = setOf("settings:search", "settings:search-hidden", "state:settings-theme-row", "screen:more.SettingsScreen")

        /** What a text field task types: the seeded entry's title, so a search finds something. */
        const val TYPED = "Alpha"

        /** What an empty field of a dialog gets before its buttons are collected, so a Save it disables shows. */
        const val FILLED = "Crawl"

        /** The online entry of flow:library.selection-download: before Alpha in the library's A to Z order, so it is the first item. */
        const val ONLINE_TITLE = "Aeon"
        const val ONLINE_URL = "/aeon"

        /** Fallbacks that say what a text field or slider task saw when nothing changed (see TESTING.md, 'Effect signatures'). */
        const val REJECTED = "field"
        const val OWN_VALUE = "own-value"
        const val FIELD_LOCAL = "field-local"
        const val KEY_UNUSED = "key-unused"
        const val AT_END = "at-end-already"

        /** The check of the reader on a middle page with no bars shown, where its keys are pressed. */
        const val READER = "flow:reader.keys"

        /**
         * The keyboard shortcuts pressed, on the check where each applies: every
         * [zoukan.desktop.ui.Shortcuts.actionFor] action, and every [ReaderKeys] binding.
         */
        val KEYS: List<Pair<String, KeySpec>> = listOf(
            "tab:More" to KeySpec("Ctrl+1", Key.One, ctrl = true),
            "tab:Library" to KeySpec("Ctrl+2", Key.Two, ctrl = true),
            "tab:Library" to KeySpec("Ctrl+3", Key.Three, ctrl = true),
            "tab:Library" to KeySpec("Ctrl+4", Key.Four, ctrl = true),
            "tab:Library" to KeySpec("Ctrl+5", Key.Five, ctrl = true),
            "tab:Library" to KeySpec("Ctrl+,", Key.Comma, ctrl = true),
            "tab:Library" to KeySpec("Ctrl+Shift+U", Key.U, ctrl = true, shift = true),
            "tab:Library" to KeySpec("Ctrl+F", Key.F, ctrl = true),
            // Where the library has a selection: without one, Ctrl/Cmd + A rightly does nothing
            "flow:library.selection" to KeySpec("Ctrl+A", Key.A, ctrl = true),
            "screen:manga.MangaScreen" to KeySpec("Escape", Key.Escape),
            "screen:manga.MangaScreen" to KeySpec("Alt+Left", Key.DirectionLeft, alt = true),
        ) + ReaderKeys.bindings.map { READER to KeySpec(it.name, it.key, shift = it.shift) }
    }

    /** A key and its modifiers; [name] goes in the key of the crawl task. Ctrl is Ctrl on every OS here, which the app takes as Cmd too. */
    private data class KeySpec(val name: String, val key: Key, val ctrl: Boolean = false, val shift: Boolean = false, val alt: Boolean = false)

    /**
     * How a crawl task reaches its node; [suffix] is what it adds to the node's key (nothing for a
     * click, so the click keys stay as they were).
     */
    private sealed interface Kind {
        val suffix: String

        data object Click : Kind {
            override val suffix = ""
        }

        /** A semantics custom action, such as the "More actions" of a right click (Modifiers.kt). */
        data class Custom(val label: String) : Kind {
            override val suffix get() = " [action: $label]"
        }

        /** Types [TYPED] into a text field, then presses Enter, or with [escape] Escape. */
        data class Type(val escape: Boolean) : Kind {
            override val suffix get() = if (escape) " [type, escape]" else " [type]"
        }

        /** Sets a slider to its maximum, then to its minimum. */
        data object Slide : Kind {
            override val suffix = " [slider max]"
        }

        /** Presses a key on the check, with no node: the focused one gets it, as in the window. */
        data class Press(val spec: KeySpec) : Kind {
            override val suffix = ""
        }
    }

    /** Where a list must be scrolled to for a button to compose: the list's index among the lists on screen, and the row. */
    private data class ListPosition(val list: Int, val row: Int)

    /**
     * A button to click: [raw] is its node id this run (see [nodeId]) and [ordinal] which of the
     * nodes with that id it is, at [position]. A menu item has the button that opens the menu as [parent].
     */
    private data class Target(
        val check: SmokeCheck,
        val key: String,
        val raw: String,
        val ordinal: Int,
        val position: ListPosition?,
        val parent: Target? = null,
        /** The first root its node is looked for in: null for the topmost one (a dialog is modal), else a menu's. */
        val fromRoot: Int? = null,
        val kind: Kind = Kind.Click,
    )

    private sealed interface Task {
        data class Collect(val check: SmokeCheck) : Task

        data class Click(val target: Target) : Task
    }

    /** A clickable on screen: [root] is the index of its root, [ordinal] counts the nodes with the same [raw] id before it, from 1. */
    private class Found(val node: SemanticsNode, val raw: String, val ordinal: Int, val root: Int)

    @Volatile
    private var current = "starting"
    private val failures = Collections.synchronizedList(mutableListOf<String>())

    /** Each button key with what clicking it did, for the report. */
    private val effects = sortedMapOf<String, String>()

    /** Each button key with its signature tokens and the rows it wrote, for the contracts. */
    private val evidence = mutableMapOf<String, List<String>>()

    /** Whether each button is a switch, checkbox or choice. */
    private val toggles = mutableMapOf<String, Boolean>()

    /** Whether each button is in a dialog or menu over the window. */
    private val inDialog = mutableMapOf<String, Boolean>()

    /**
     * The key the contracts match: with " (toggle)" for a switch, checkbox or choice on a screen,
     * which stores its value at once, and " (dialog toggle)" for one in a dialog, which may keep
     * it until the dialog's OK.
     */
    private fun contractKey(key: String) = when {
        toggles[key] != true -> key
        inDialog[key] == true -> "$key (dialog toggle)"
        else -> "$key (toggle)"
    }

    /** The buttons found again after a fresh open but not on screen when it was time to click them. */
    private val unreached = sortedSetOf<String>()

    private lateinit var navigator: Navigator

    /** The window state the screens see: the reader's F key makes it fullscreen, and [close] puts it back. */
    private val window = WindowState()

    // What a click can do outside the window

    /**
     * Records each outside effect as a signature token, and checks its arguments: a web address
     * must be http(s) with a host, a folder must exist inside the app's data or storage folder.
     */
    private object Recorder : SystemEffects {
        val calls = ConcurrentLinkedQueue<String>()
        val problems = ConcurrentLinkedQueue<String>()

        /** File names a picker's filter is tried on, to name the kinds of file it offers. */
        private val PROBES = listOf("a.tachibk", "a.proto.gz", "a.png", "a.jpg", "a.webp", "a.avif", "a.csv", "a.txt", "a.zip")

        override fun browse(uri: URI) {
            if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) problems += "a link that is not a web address: $uri"
            calls += "browse:${uri.host}"
        }

        override fun openFolder(folder: File) {
            val dirs = Injekt.get<zoukan.desktop.ZoukanDirs>()
            val storage = Injekt.get<zoukan.desktop.storage.ZoukanStorage>()
            val path = folder.canonicalFile
            val base = listOf(storage.root, dirs.root).map { it.canonicalFile }.firstOrNull { path.startsWith(it) }
            if (base == null) problems += "opens a folder outside the data and storage folders: $path"
            if (!path.isDirectory) problems += "opens a folder that does not exist: $path"
            calls += "folder:" + ButtonCrawl.stable(base?.let { path.relativeTo(it).invariantSeparatorsPath.ifEmpty { "." } } ?: "?")
        }

        override fun pickFile(request: FileRequest): File? {
            val kinds = request.accept?.let { accept -> PROBES.filter(accept).joinToString(",") { it.removePrefix("a.") } } ?: "*"
            calls += "pick:${if (request.save) "save" else "open"}[$kinds]"
            return null
        }

        override fun pickFolder(window: Window?, title: String, start: File): File? {
            calls += "pick:folder"
            return null
        }

        override fun copy(contents: Transferable) {
            calls += "clipboard"
        }

        override fun installUpdate(file: File, quit: () -> Unit) {
            calls += "install"
        }

        override fun exit(code: Int) {
            calls += "exit"
        }
    }

    /** The ERROR lines the app logs, through logback. */
    private object ErrorLog : AppenderBase<ILoggingEvent>() {
        val lines = ConcurrentLinkedQueue<String>()

        override fun append(event: ILoggingEvent) {
            // An ERROR, or a missing Injekt registration at any level (a screen model may catch it)
            if (!event.level.isGreaterOrEqual(Level.ERROR) && "No registered instance" !in event.formattedMessage.orEmpty()) return
            val cause = event.throwableProxy?.let { " (${it.className}: ${it.message})" } ?: ""
            lines += "${event.loggerName}: ${event.formattedMessage}$cause"
        }
    }

    /**
     * The exceptions printed to stderr without the logger: an e.printStackTrace() in a screen
     * model or a library, which the log capture never sees. A trace printed under a log line is
     * the logger's, already in [ErrorLog], so it is left out.
     */
    private class StderrTap(private val original: java.io.PrintStream) : java.io.OutputStream() {
        val exceptions = ConcurrentLinkedQueue<String>()
        private val line = java.io.ByteArrayOutputStream()
        private var underLogLine = false

        override fun write(b: Int) {
            original.write(b)
            synchronized(line) {
                if (b == '\n'.code) {
                    take(line.toString(Charsets.UTF_8).trimEnd('\r'))
                    line.reset()
                } else {
                    line.write(b)
                }
            }
        }

        override fun flush() = original.flush()

        private fun take(text: String) {
            when {
                LOG_LINE.containsMatchIn(text) -> underLogLine = true
                STACK_LINE.containsMatchIn(text) -> Unit
                EXCEPTION.containsMatchIn(text) -> if (!underLogLine) exceptions += text.trim()
                else -> underLogLine = false
            }
        }

        private companion object {
            val LOG_LINE = Regex("""^\d\d:\d\d:\d\d\.\d{3} """)
            val STACK_LINE = Regex("""^(\s+at |\s*\.\.\. \d+ more|Caused by: |\s+Suppressed: )""")
            val EXCEPTION = Regex("""^(Exception in thread |[\w.$]+(Exception|Error)(: |$))""")
        }
    }

    private val toasts = ConcurrentLinkedQueue<String>()
    private val notifications = ConcurrentLinkedQueue<String>()

    /** Library updates started: LibraryUpdater's progress turned from none to some. */
    private val updates = AtomicInteger()

    private lateinit var tap: StderrTap

    @Test
    fun `every button does something without an error`() {
        val dirs = SmokeChecks.startRuntime("zoukan-crawl")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            failures += "$current: uncaught on thread ${thread.name}: $e"
            e.printStackTrace()
            previous?.uncaughtException(thread, e)
        }
        val listeners = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val stderr = System.err
        val tap = StderrTap(stderr)
        System.setErr(java.io.PrintStream(tap, true))
        this.tap = tap
        val trash = zoukan.desktop.ui.manga.systemTrash
        val sensitive = zoukan.desktop.ui.settings.sensitiveSettingsChoice
        val snapshotFailed = zoukan.desktop.ui.common.snapshotFailedPrompt
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger
        try {
            SystemEffects.current = Recorder
            zoukan.desktop.ui.manga.systemTrash = { file ->
                Recorder.calls += "trash:${file.name}"
                false
            }
            // A restore asks whether to restore security settings and logins: without them, recorded
            zoukan.desktop.ui.settings.sensitiveSettingsChoice = {
                Recorder.calls += "asks:sensitive-settings"
                false
            }
            // A safety snapshot that fails asks whether to go on (a Swing dialog): no, recorded
            zoukan.desktop.ui.common.snapshotFailedPrompt = zoukan.desktop.backup.SnapshotFailedPrompt {
                Recorder.calls += "asks:snapshot-failed"
                false
            }
            listeners.launch { Toasts.flow.collect { toasts += it } }
            listeners.launch { Notifications.flow.collect { notifications += it.title } }
            startUi(dirs)
            // After the start: the updater opens the database, which the start sets up
            val updater = Injekt.get<LibraryUpdater>()
            listeners.launch {
                var running = false
                updater.progress.collect { progress ->
                    if (progress != null && !running) updates.incrementAndGet()
                    running = progress != null
                }
            }
            current = "seeding"
            val fixture = SmokeChecks.seed()
            seedMangas = listOf(fixture.alpha, fixture.bravo)
            db = DbState(Injekt.get())
            seedState = readDb()
            settingsState = settings()
            // After the start: the runtime sets logback up as it starts
            ErrorLog.context = root.loggerContext
            ErrorLog.start()
            root.addAppender(ErrorLog)

            val all = (SmokeChecks.all(fixture) + extraChecks(fixture)).filter { it.key !in DUPLICATES }
            val only = System.getProperty("zoukan.crawl.only")?.toRegex()
            val filtered = if (only == null) all else all.filter { only.containsMatchIn(it.name) || only.containsMatchIn(it.key) }
            check(filtered.isNotEmpty()) { "no check matches $only; checks are:\n" + all.joinToString("\n") { "${it.key}  ${it.name}" } }
            val shard = System.getProperty("zoukan.shard")?.let { value ->
                val (index, count) = value.split('/').map { it.trim().toInt() }
                ButtonCrawl.shard(filtered.map { it.key }, index, count)
            }
            val checks = if (shard == null) filtered else filtered.filter { it.key in shard }
            println("Crawl: ${checks.size} of ${all.size} checks")
            val started = System.nanoTime()
            crawl(checks)
            println("Crawl: ${effects.size} buttons in ${(System.nanoTime() - started) / 1_000_000_000} s")
            compare(checks.map { it.key }.toSet(), partial = only != null)
            failures.forEach { println("Crawl: FAILED $it") }
            failures.shouldBeEmpty()
        } finally {
            System.setErr(stderr)
            root.detachAppender(ErrorLog)
            listeners.cancel()
            SystemEffects.current = SystemEffects.Native
            zoukan.desktop.ui.manga.systemTrash = trash
            zoukan.desktop.ui.settings.sensitiveSettingsChoice = sensitive
            zoukan.desktop.ui.common.snapshotFailedPrompt = snapshotFailed
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    /**
     * States whose buttons only show with data: a library selection, one of an online entry (for
     * the download actions), the entry's chapters all selected and the reader's bars. Each key is the coverage-matrix row of the flow.
     */
    private fun extraChecks(f: SmokeFixture): List<SmokeCheck> = listOf(
        SmokeCheck(
            "flow:library.selection",
            "library selection",
            { HomeScreen.selectTab(HomeTab.Library) },
            drive = {
                // The first category tab: a click on another category stays in the tab's screen model
                val all = hasText("All") and SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Tab)
                if (onAllNodes(all).fetchSemanticsNodes().isNotEmpty()) {
                    onAllNodes(all).onFirst().performClick()
                    settle()
                }
                // A right click opens the entry's menu; Select there starts the selection
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Alpha").fetchSemanticsNodes().isNotEmpty() }
                onAllNodesWithText("Alpha").onFirst().performMouseInput { rightClick() }
                clickText("Select")
                waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("1 selected")).fetchSemanticsNodes().isNotEmpty() }
            },
        ),
        onlineSelection(),
        SmokeCheck(
            "flow:manga.chapter-selection",
            "entry with every chapter selected",
            { it.push(MangaScreen(f.alpha.id)) },
            drive = {
                // A selection left by an earlier click ends first, so this one has every chapter
                val clear = SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Clear selection"))
                if (onAllNodes(clear).fetchSemanticsNodes().isNotEmpty()) clickDescription("Clear selection")
                clickDescription("Select all")
            },
        ),
        SmokeCheck(
            READER,
            "reader on a middle page, for its keys",
            // The middle one of three pages, one page at a time (a wide window shows two), so
            // every page key has a page to go to. The setting is put back before the next open
            {
                Injekt.get<DesktopPreferences>().pageLayout().set(0)
                it.push(ReaderScreen(f.alpha.id, f.chapters.first().id, page = 1))
            },
            // Until the viewer shows the pages: before that, a page key has no viewer to turn
            drive = { waitUntil(timeoutMillis = 10_000) { readerState()?.ready == true } },
        ),
        SmokeCheck(
            "flow:reader.bars",
            "reader with its bars shown",
            { it.push(ReaderScreen(f.alpha.id, f.chapters.first().id)) },
            drive = {
                val shortcuts = localized(DMR.strings.keyboard_shortcuts)
                val bars = SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(shortcuts))
                // M shows the bars, as a click in the middle of the page does
                onAllNodes(isRoot()).onFirst().performKeyInput { pressKey(Key.M) }
                waitUntil(timeoutMillis = 10_000) { onAllNodes(bars).fetchSemanticsNodes().isNotEmpty() }
            },
        ),
    )

    /**
     * The library with an online entry ([ONLINE_TITLE], of [HeldSource]) selected, so the
     * download actions show: the selection bar's Download and the entry menu's Download with its
     * submenu. The seeded entries are local-source, which has no downloads. The entry and its
     * source are added for this check only, and removed again with whatever it queued.
     */
    private fun onlineSelection(): SmokeCheck {
        val source = HeldSource()
        val tests = Injekt.get<zoukan.desktop.exh.BuiltInSources>().testSources
        return SmokeCheck(
            "flow:library.selection-download",
            "library selection of an online entry",
            { HomeScreen.selectTab(HomeTab.Library) },
            drive = {
                if (tests.value.none { it === source }) tests.value = tests.value + source
                waitUntil(timeoutMillis = 10_000) { Injekt.get<SourceManager>().get(source.id) != null }
                runBlocking { seedOnline(source) }
                val all = hasText("All") and SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Tab)
                if (onAllNodes(all).fetchSemanticsNodes().isNotEmpty()) {
                    onAllNodes(all).onFirst().performClick()
                    settle()
                }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(ONLINE_TITLE).fetchSemanticsNodes().isNotEmpty() }
                onAllNodesWithText(ONLINE_TITLE).onFirst().performMouseInput { rightClick() }
                clickText("Select")
                waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("1 selected")).fetchSemanticsNodes().isNotEmpty() }
            },
            cleanup = {
                val downloads = Injekt.get<zoukan.desktop.download.DownloadManager>()
                try {
                    downloads.clearQueue()
                    runBlocking {
                        withTimeout(10_000) { downloads.awaitBackgroundWork() }
                        val mangas = Injekt.get<MangaRepository>()
                        mangas.getMangaByUrlAndSourceId(ONLINE_URL, source.id)?.let { mangas.deleteManga(it.id) }
                    }
                } finally {
                    tests.value = tests.value.filterNot { it === source }
                    // The download made the source's folder before it waited for the pages
                    Injekt.get<zoukan.desktop.download.DownloadPaths>().sourceDir(source.id).deleteRecursively()
                }
            },
        )
    }

    /** Adds [ONLINE_TITLE] to the library, with two unread chapters, as a library update of [source] would. */
    private suspend fun seedOnline(source: HeldSource) {
        val entry = SManga.create().apply {
            url = ONLINE_URL
            title = ONLINE_TITLE
        }.toDomainManga(source.id)
        val manga = Injekt.get<NetworkToLocalManga>()(listOf(entry)).single()
        Injekt.get<MangaRepository>().update(MangaUpdate(id = manga.id, favorite = true))
        if (Injekt.get<GetChaptersByMangaId>().await(manga.id).isNotEmpty()) return
        Injekt.get<ChapterRepository>().addAll(
            (1..2).map { n ->
                tachiyomi.domain.chapter.model.Chapter.create().copy(
                    mangaId = manga.id,
                    url = "$ONLINE_URL/$n",
                    name = "Chapter $n",
                    chapterNumber = n.toDouble(),
                    // The oldest chapter has the highest source order, as a source lists them
                    sourceOrder = (2 - n).toLong(),
                )
            },
        )
    }

    /**
     * An online source whose pages never come: a chapter queued from it stays in the queue,
     * downloading, until the check's cleanup clears the queue. So a download's signature is the
     * queue's change, the same in every run, with no failure or notification racing it. Its other
     * requests (the entry's details, chapters, related titles) go to the crawl's closed network and
     * fail there as an extension's do offline.
     */
    private class HeldSource : HttpSource() {
        override val name = "Crawl online"
        override val lang = "en"
        override val supportsLatest = false
        override val baseUrl = "http://127.0.0.1:9"

        override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/popular?page=$page", headers)

        override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = GET("$baseUrl/search?q=$query&page=$page", headers)

        override suspend fun getPageList(chapter: SChapter): List<Page> = awaitCancellation()
    }

    // The crawl

    /** Runs the tasks in Compose tests of [BATCH_MILLIS] each, and in a new one after a composition broke. */
    private fun crawl(checks: List<SmokeCheck>) {
        val preferences = Injekt.get<DesktopPreferences>()
        Injekt.get<UiPreferences>().themeMode().set(ThemeMode.LIGHT)
        val queue = ArrayDeque<Task>(checks.map { Task.Collect(it) })
        while (queue.isNotEmpty()) {
            try {
                runDesktopComposeUiTest(width = WIDTH, height = HEIGHT, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
                    setContent {
                        InstallEscapeAsBack()
                        CompositionLocalProvider(LocalWindowState provides window) {
                            ZoukanContent(preferences) { navigator = it }
                        }
                    }
                    waitForIdle()
                    val end = System.currentTimeMillis() + BATCH_MILLIS
                    var first = true
                    // Each check starts in a Compose test of its own: what one check's clicks left
                    // in the composition (a Settings page, a tab's state) never reaches the next,
                    // so a button's key does not depend on which checks ran before (or on the shard)
                    while (queue.isNotEmpty() && System.currentTimeMillis() < end && (first || queue.first() !is Task.Collect)) {
                        first = false
                        val task = queue.removeFirst()
                        when (task) {
                            is Task.Collect -> queue.addAll(0, collect(task.check))
                            is Task.Click -> queue.addAll(0, click(task.target))
                        }
                    }
                }
            } catch (e: Unresponsive) {
                failures += e.message.orEmpty()
                // The UI thread is stuck: nothing more can run in this JVM
                failures += "${queue.size} crawl tasks not run"
                return
            } catch (e: Recorded) {
                // The task that threw is gone from the queue; the next Compose test goes on after it
            } catch (e: Throwable) {
                failures += "$current: $e"
                e.printStackTrace()
            }
        }
    }

    private class Unresponsive(message: String) : Exception(message)

    /** A click's error, already in the failures: thrown on to start a new Compose test. */
    private class Recorded(cause: Throwable) : Exception(cause)

    /** Opens [check] as the smoke test does: [SmokeCheck.open] on the UI thread, then its drive. */
    private fun ComposeUiTest.open(check: SmokeCheck, restore: Boolean = true) {
        if (restore) restoreState()
        runOnUiThread {
            // From the root and the Library tab, as the smoke test opens each check: the app
            // restores its last screens when it starts, so a new Compose test may not start there
            navigator.popUntilRoot()
            HomeScreen.selectTab(HomeTab.Library)
        }
        waitForIdle()
        runOnUiThread { check.open(navigator) }
        // As the smoke test waits: a Settings page or a screen model may take a few frames to load
        settle()
        try {
            check.drive(this)
        } catch (e: Throwable) {
            // What was on screen, to tell an app that changed from a drive that looked for the wrong node
            runCatching { onAllNodes(isRoot()).printToString(maxDepth = Int.MAX_VALUE) }.onSuccess { println(it) }
            throw e
        }
        settleQuickly()
        fillDialogFields()
    }

    /**
     * A dialog with a disabled button and an empty text field (a new category's name) gets
     * [FILLED] in the field, so the button it holds back is crawled too.
     */
    private fun ComposeUiTest.fillDialogFields() {
        val roots = roots(merged = true)
        var filled = false
        roots.drop(1).forEach { root ->
            val nodes = root.descendants()
            if (nodes.none { SemanticsActions.OnClick in it.config && SemanticsProperties.Disabled in it.config }) return@forEach
            nodes.filter { matches(Kind.Type(false), it) && it.config.getOrNull(SemanticsProperties.EditableText)?.text.isNullOrEmpty() }.forEach { node ->
                val set = node.config[SemanticsActions.SetText].action ?: return@forEach
                runOnUiThread { set(AnnotatedString(FILLED)) }
                filled = true
            }
        }
        if (filled) settleQuickly()
    }

    /** Closes [check] as the smoke test does, then whatever dialog or menu a click left open. */
    private fun ComposeUiTest.close(check: SmokeCheck) {
        try {
            runOnUiThread { check.cleanup() }
        } finally {
            runOnUiThread {
                navigator.popUntilRoot()
                HomeScreen.selectTab(HomeTab.Library)
                // Settings > Advanced > Onboarding guide opens the guide over every screen until closed
                zoukan.desktop.ui.onboarding.Onboarding.reopen.value = false
                // The reader's F key made the window fullscreen
                window.placement = WindowPlacement.Floating
            }
            waitForIdle()
            // A field of the root screen keeps what a text field task typed (the library's search)
            roots(merged = true).first().descendants()
                .filter { matches(Kind.Type(false), it) && !it.config.getOrNull(SemanticsProperties.EditableText)?.text.isNullOrEmpty() }
                .forEach { node -> node.config[SemanticsActions.SetText].action?.let { set -> runOnUiThread { set(AnnotatedString("")) } } }
            waitForIdle()
            // A selection (library, updates, history) stays on its tab: Escape ends it
            repeat(2) {
                if (onAllNodes(selectionCount).fetchSemanticsNodes().isEmpty()) return@repeat
                pressEscape(roots(merged = true).first())
                waitForIdle()
            }
            // A dialog or menu of a tab stays open over the root screen
            repeat(3) {
                val roots = roots(merged = true)
                if (roots.size <= 1) return
                pressEscape(roots.last())
                waitForIdle()
            }
            runOnUiThread { HomeScreen.selectTab(HomeTab.More) }
            waitForIdle()
            runOnUiThread { HomeScreen.selectTab(HomeTab.Library) }
            waitForIdle()
        }
    }

    /** The title of a selection: "2 selected". */
    private val selectionCount = SemanticsMatcher("a selection's count") { node ->
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { SELECTED.matches(it.text) }
    }

    /** The check's buttons, scrolling every list to reach its rows: one click task each. */
    private fun ComposeUiTest.collect(check: SmokeCheck): List<Task> {
        current = "${check.name} (collecting)"
        println("Crawl: ${check.name}")
        val targets = mutableListOf<Target>()
        val keys = mutableSetOf<String>()
        val raws = mutableSetOf<Pair<Kind, String>>()
        fun add(kind: Kind, found: Found, position: ListPosition?) {
            raws += kind to found.raw
            // A text field gets two tasks: type and Enter, and type and Escape
            val kinds = if (kind is Kind.Type) listOf(Kind.Type(false), Kind.Type(true)) else listOf(kind)
            kinds.forEach { k ->
                val key = uniqueKey("${check.key}/${ButtonCrawl.stable(found.raw + k.suffix)}", keys) ?: return@forEach
                targets += Target(check, key, found.raw, found.ordinal, position, kind = k)
            }
        }
        try {
            open(check)
            // -Pzoukan.crawl.debug=true prints what each check shows when its buttons are collected
            if (System.getProperty("zoukan.crawl.debug") == "true") println(onAllNodes(isRoot()).printToString(maxDepth = Int.MAX_VALUE))
            val seen = mutableSetOf<Pair<Kind, Int>>()
            interactables().forEach { (kind, found) ->
                seen += kind to found.node.id
                add(kind, found, null)
            }
            val lists = onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().size
            for (list in 0 until lists) {
                var row = LIST_STEP
                for (step in 1..MAX_LIST_STEPS) {
                    if (!scroll(ListPosition(list, row))) break
                    // A row that scrolled out and back composes again as a new node with the same id: not a new button
                    interactables().filter { (kind, found) -> (kind to found.node.id) !in seen }.forEach { (kind, found) ->
                        seen += kind to found.node.id
                        if ((kind to found.raw) !in raws) add(kind, found, ListPosition(list, row))
                    }
                    row += LIST_STEP
                }
                scroll(ListPosition(list, 0))
            }
            // The keyboard shortcuts that apply on this check
            KEYS.filter { it.first == check.key }.forEach { (_, spec) ->
                targets += Target(check, "${check.key}/key:${spec.name}", "", 0, null, kind = Kind.Press(spec))
            }
        } catch (e: Throwable) {
            failures += "${check.name}: could not open it to collect its buttons: $e"
        } finally {
            runCatching { close(check) }
        }
        // A variant of a check already crawled (the same key) adds only the buttons it has more
        return targets.filter { it.key !in effects }.map { Task.Click(it) }
    }

    /**
     * [key], or [key] #2 when it is already in [keys]; adds it. Null for a third button with the
     * same key: the rows of one list (chapters, calendar days) are wired alike, and how many there
     * are changes with the data and the date.
     */
    private fun uniqueKey(key: String, keys: MutableSet<String>): String? = when {
        keys.add(key) -> key
        keys.add("$key #2") -> "$key #2"
        else -> null
    }

    /** Scrolls a list to a row; false when the row is past its end or the list is gone. */
    private fun ComposeUiTest.scroll(position: ListPosition): Boolean {
        val lists = onAllNodes(hasScrollToIndexAction())
        if (lists.fetchSemanticsNodes().size <= position.list) return false
        return try {
            lists[position.list].performScrollToIndex(position.row)
            waitForIdle()
            true
        } catch (e: IllegalArgumentException) {
            false
        } catch (e: AssertionError) {
            false
        }
    }

    /**
     * Runs [target] from a fresh open of its check and records its effect signature; returns the
     * items of a menu it opened. A click or custom action presses the node; a text
     * field is typed into, then gets Enter or Escape; a slider goes to its maximum, then its
     * minimum; a shortcut is pressed as the window would pass it on.
     */
    private fun ComposeUiTest.click(target: Target): List<Task> {
        current = "${target.check.name}: ${target.key}"
        var hung = false
        try {
            open(target.check)
            return when (val kind = target.kind) {
                is Kind.Press -> {
                    // N and P on the last or first chapter have nowhere to go, rightly
                    val reader = readerState()
                    val none = when (ReaderKeys.actionFor(kind.spec.key, shift = kind.spec.shift).takeIf { target.check.key == READER }) {
                        ReaderKeys.Action.NEXT_CHAPTER -> if (reader?.hasNext == false) AT_END else NO_EFFECT
                        ReaderKeys.Action.PREVIOUS_CHAPTER -> if (reader?.hasPrevious == false) AT_END else NO_EFFECT
                        else -> NO_EFFECT
                    }
                    measure(target.key, kind, none) { sendKey(kind.spec) }
                    emptyList()
                }
                is Kind.Type -> type(target, kind)
                Kind.Slide -> slide(target)
                else -> press(target)
            }
        } catch (e: Unresponsive) {
            hung = true
            throw e
        } catch (e: Throwable) {
            failures += "${target.key}: $e"
            effects[target.key] = "error"
            e.printStackTrace()
            // The composition may be broken: the next Compose test goes on with the next task
            throw Recorded(e)
        } finally {
            // Not while the UI thread is stuck: closing would wait for it
            if (!hung) runCatching { close(target.check) }
        }
    }

    /**
     * A click or custom action. A switch, checkbox or choice whose click wrote a
     * setting or the database is followed by the round trip: the check opens again, and it must
     * show what was stored.
     */
    private fun ComposeUiTest.press(target: Target): List<Task> {
        target.position?.let { scroll(it) }
        target.parent?.let { parent -> if (!reopen(parent)) return unreached(target) }
        val fromRoot = target.fromRoot
        val found = find(target, fromRoot) ?: return unreached(target)
        toggles[target.key] = isToggle(found.node)
        inDialog[target.key] = found.root > 0
        // Choosing what is already chosen (the current page, tab or option) does nothing, rightly
        // A text field that already has the focus: clicking it only places the cursor
        val none = when {
            found.node.config.getOrNull(SemanticsProperties.Selected) == true -> "selected-already"
            SemanticsActions.SetText in found.node.config -> REJECTED
            else -> NO_EFFECT
        }
        val (before, after, signature) = measure(target.key, target.kind, none) { press(found, target.kind) }
        var next = emptyList<Task>()
        // A menu, not a dialog: its items, one level down. A submenu of a menu item too, but no
        // menu inside a submenu (ButtonCrawl.MAX_MENU_DEPTH)
        val depth = generateSequence(target.parent) { it.parent }.count()
        if ("menu" in signature.tokens && depth < ButtonCrawl.MAX_MENU_DEPTH) {
            // A new popup is on a root of its own after the old ones; a submenu shown in place of its
            // menu, or a popup in place of one that closed, is the topmost root with items
            val menuRoot = if (after.roots > before.roots) before.roots else topRoot(roots(merged = true))
            val keys = mutableSetOf<String>()
            next = clickables(fromRoot = menuRoot).mapNotNull {
                val key = uniqueKey("${target.key} > ${ButtonCrawl.stable(it.raw)}", keys) ?: return@mapNotNull null
                Task.Click(Target(target.check, key, it.raw, it.ordinal, target.position, parent = target, fromRoot = menuRoot))
            }
        }
        // The round trip, for a click that stored something and stayed on the same screen
        val stored = signature.tokens.any { it.startsWith("pref:") || it.startsWith("db:") }
        val moved = signature.tokens.any { it.startsWith("nav:") || it.startsWith("dialog:") || it == "menu" || it == "close" }
        // Only for a switch, checkbox or choice: a button's own label (Restore, then Close) may rightly start over
        if (target.parent == null && stored && !moved && toggles[target.key] == true) {
            val shown = find(target, fromRoot)?.let { state(it.node) }
            if (shown != null) roundTrip(target, fromRoot, shown)
        }
        return next
    }

    /** Opens the menu [opener] opened again: its own opener first, for a submenu. False when a button of the chain is gone. */
    private fun ComposeUiTest.reopen(opener: Target): Boolean {
        opener.parent?.let { if (!reopen(it)) return false }
        val found = find(opener, opener.fromRoot) ?: return false
        press(found, opener.kind)
        settleQuickly()
        return true
    }

    /**
     * Types [TYPED] into a text field, as a user does (the field takes the focus first), then
     * presses Enter; the other task of the field types and presses Escape. A field in a dialog
     * that only shows what was typed is [FIELD_LOCAL]: the dialog's button stores it.
     */
    private fun ComposeUiTest.type(target: Target, kind: Kind.Type): List<Task> {
        target.position?.let { scroll(it) }
        val found = find(target, target.fromRoot) ?: return unreached(target)
        val local = found.root > 0
        inDialog[target.key] = local
        val config = found.node.config
        fun typeIt() = onUi {
            config.getOrNull(SemanticsActions.RequestFocus)?.action?.invoke()
            config[SemanticsActions.SetText].action?.invoke(AnnotatedString(TYPED))
        }
        if (kind.escape) {
            typeIt()
            settleQuickly()
            measure(target.key, kind, KEY_UNUSED, local) { sendKey(KeySpec("Escape", Key.Escape)) }
        } else {
            measure(target.key, kind, REJECTED, local) { typeIt() }
            measure(target.key.replace(Kind.Type(false).suffix, " [type, enter]"), kind, KEY_UNUSED, local) { sendKey(KeySpec("Enter", Key.Enter)) }
        }
        return emptyList()
    }

    /** Sets a slider to its maximum, then to its minimum, as Material's setProgress does (with onValueChangeFinished). */
    private fun ComposeUiTest.slide(target: Target): List<Task> {
        target.position?.let { scroll(it) }
        val found = find(target, target.fromRoot) ?: return unreached(target)
        inDialog[target.key] = found.root > 0
        fun set(node: SemanticsNode, max: Boolean) {
            val info = node.config[SemanticsProperties.ProgressBarRangeInfo]
            val value = if (max) info.range.endInclusive else info.range.start
            if (info.current == value) return
            val action = node.config[SemanticsActions.SetProgress].action ?: return
            onUi { action(value) }
        }
        measure(target.key, Kind.Slide, AT_END) { set(found.node, max = true) }
        // The slider's label may show its value: found again by its position when its id changed
        val again = find(target, target.fromRoot)?.node ?: found.node
        measure(target.key.replace(Kind.Slide.suffix, " [slider min]"), Kind.Slide, AT_END) { set(again, max = false) }
        return emptyList()
    }

    private data class Measured(val before: Snapshot, val after: Snapshot, val signature: Signature)

    /**
     * Runs [act], waits until its effect signature holds still, checks the errors it logged and the
     * settings it wrote, and records the signature under [key], or [none] when nothing changed.
     * [local] is a text field in a dialog.
     */
    private fun ComposeUiTest.measure(key: String, kind: Kind, none: String, local: Boolean = false, act: () -> Unit): Measured {
        val before = snapshot()
        val errors = ErrorLog.lines.size
        val printed = tap.exceptions.size
        val problems = Recorder.problems.size
        act()
        settleQuickly()
        // Until the signature holds still: a click's work may land over several frames
        var after = snapshot()
        var signature = signatureOf(before, after, kind)
        val deadline = System.currentTimeMillis() + SLOW_EFFECT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(250)
            waitForIdle()
            val later = snapshot()
            val again = signatureOf(before, later, kind)
            val still = again == signature
            after = later
            signature = again
            if (still && signature.tokens.isNotEmpty()) break
        }
        val logged = ErrorLog.lines.drop(errors) + tap.exceptions.drop(printed).map { "printed $it" }
        // A screen's job cancelled as the crawl leaves the screen: shared code (GetChapter, from
        // Android) catches every Exception and logs it, the cancellation too, as Android's logcat does
        logged.filterNot { OFFLINE.containsMatchIn(it) || CANCELLED.containsMatchIn(it) }.forEach { failures += "$key: logged an error: $it" }
        Recorder.problems.drop(problems).forEach { failures += "$key: $it" }
        checkPersisted(key, before, after)
        if (logged.any { OFFLINE.containsMatchIn(it) }) signature = Signature(signature.tokens + "logs:offline", signature.details)
        if (local && signature.tokens == listOf(OWN_VALUE)) signature = Signature(listOf(FIELD_LOCAL), signature.details)
        effects[key] = signature.tokens.ifEmpty { listOf(none) }.joinToString(" ")
        evidence[key] = signature.tokens + signature.details
        println("  $key  ${effects[key]}")
        return Measured(before, after, signature)
    }

    /**
     * Presses [spec] as the window passes a key on: into the Compose scene, where the focused node
     * (and then the app's shortcuts on the root) may use it, and an Escape nothing used goes Back.
     * The scene is internal API, as in [pressEscape].
     */
    @OptIn(InternalTestApi::class, InternalComposeUiApi::class)
    private fun ComposeUiTest.sendKey(spec: KeySpec) {
        val scene = (this as SkikoComposeUiTest).scene
        listOf(KeyEventType.KeyDown, KeyEventType.KeyUp).forEach { type ->
            val event = KeyEvent(spec.key, type, isCtrlPressed = spec.ctrl, isShiftPressed = spec.shift, isAltPressed = spec.alt)
            onUi {
                val plainEscape = spec.key == Key.Escape && !spec.ctrl && !spec.shift && !spec.alt
                if (!scene.sendKeyEvent(event) && type == KeyEventType.KeyUp && plainEscape) {
                    checkNotNull(EscapeAsBack.current) { "no InstallEscapeAsBack() in the test's content" }.back()
                }
            }
        }
    }

    /**
     * Opens the check again without putting the stored state back, and checks that the button
     * shows what the click stored: UI, model, storage and back to the UI.
     */
    private fun ComposeUiTest.roundTrip(target: Target, fromRoot: Int?, shown: String) {
        close(target.check)
        open(target.check, restore = false)
        target.position?.let { scroll(it) }
        // A label that changed with the state ("Mark as read" to "Mark as unread") can't be found again: nothing to compare
        val again = find(target, fromRoot) ?: return
        val now = state(again.node)
        if (now != shown) {
            failures += "${target.key}: after the click it showed [$shown], but opened again it shows [$now]: the stored state does not reach the screen"
        }
    }

    /** A setting the click changed must be on disk, not only in memory. */
    @OptIn(xyz.nulldev.androidcompat.io.sharedprefs.ForTestsOnly::class)
    private fun checkPersisted(key: String, before: Snapshot, after: Snapshot) {
        val changed = (after.settings.keys + before.settings.keys).filter { after.settings[it] != before.settings[it] }
        if (changed.isEmpty()) return
        xyz.nulldev.androidcompat.io.sharedprefs.JavaSharedPreferences.awaitPendingSaves()
        val stored = xyz.nulldev.androidcompat.io.sharedprefs.JavaSharedPreferences(PREFERENCES).all
        changed.filter { stored[it] != after.settings[it] }.forEach {
            failures += "$key: the setting $it changed to ${after.settings[it]} but the file holds ${stored[it]}"
        }
    }

    /** What a button shows of its state: checked, selected, its text. Focus is left out. */
    private fun state(node: SemanticsNode): String {
        val c = node.config
        return listOfNotNull(
            c.getOrNull(SemanticsProperties.ToggleableState)?.name,
            c.getOrNull(SemanticsProperties.Selected)?.let { "selected=$it" },
            c.getOrNull(SemanticsProperties.StateDescription),
            label(node),
        ).joinToString(", ")
    }

    /**
     * A switch, checkbox or choice (a radio button). Tabs and the Settings page list are selectable
     * too, but choosing one only shows a page: that is navigation, not a setting.
     */
    private fun isToggle(node: SemanticsNode) = SemanticsProperties.ToggleableState in node.config ||
        (SemanticsProperties.Selected in node.config && node.config.getOrNull(SemanticsProperties.Role) == androidx.compose.ui.semantics.Role.RadioButton)

    private fun unreached(target: Target): List<Task> {
        unreached += target.key
        effects.putIfAbsent(target.key, "unreached")
        return emptyList()
    }

    /**
     * The clickable nodes on every root from [fromRoot] on, in tree order. Without [fromRoot], those
     * of the topmost root that has any: a dialog or menu over the window is modal, so a user can
     * only click in it.
     */
    private fun ComposeUiTest.clickables(fromRoot: Int? = null, kind: Kind = Kind.Click): List<Found> {
        val counts = mutableMapOf<String, Int>()
        val roots = roots(merged = true)
        val first = fromRoot ?: topRoot(roots)
        return roots.withIndex().filter { it.index >= first }.flatMap { (index, root) ->
            root.descendants().filter { matches(kind, it) }.map { node ->
                val raw = nodeId(node)
                Found(node, raw, counts.merge(raw, 1, Int::plus)!!, index)
            }
        }
    }

    /** The topmost root with something to click: a tooltip over the window is a root too. */
    private fun topRoot(roots: List<SemanticsNode>) = roots.indexOfLast { root -> root.descendants().any { matches(Kind.Click, it) } }.coerceAtLeast(0)

    /** Whether [node] takes [kind]: enabled, shown to accessibility, and, for a text field, not a password. */
    private fun matches(kind: Kind, node: SemanticsNode): Boolean {
        val c = node.config
        if (SemanticsProperties.HideFromAccessibility in c) return false
        val enabled = SemanticsProperties.Disabled !in c
        return when (kind) {
            Kind.Click -> SemanticsActions.OnClick in c && enabled
            is Kind.Custom -> c.getOrNull(SemanticsActions.CustomActions).orEmpty().any { it.label == kind.label }
            is Kind.Type ->
                SemanticsActions.SetText in c && enabled && SemanticsProperties.Password !in c &&
                    c.getOrNull(SemanticsProperties.IsEditable) != false
            Kind.Slide -> SemanticsActions.SetProgress in c && SemanticsProperties.ProgressBarRangeInfo in c && enabled
            is Kind.Press -> false
        }
    }

    /**
     * Every node a user can act on, with how: click, custom action, text, slider. Long presses are
     * left out: the one that opens a menu (the entry's title) has the same menu as its right-click
     * action, and the others show a tooltip.
     */
    private fun ComposeUiTest.interactables(): List<Pair<Kind, Found>> {
        val roots = roots(merged = true)
        val labels = roots.drop(topRoot(roots)).flatMap { it.descendants() }
            .flatMap { it.config.getOrNull(SemanticsActions.CustomActions).orEmpty() }.map { it.label }.distinct()
        val kinds = listOf(Kind.Click, Kind.Type(false), Kind.Slide) + labels.map { Kind.Custom(it) }
        return kinds.flatMap { kind -> clickables(kind = kind).map { kind to it } }
    }

    private fun ComposeUiTest.find(target: Target, fromRoot: Int?): Found? =
        clickables(fromRoot, target.kind).firstOrNull { it.raw == target.raw && it.ordinal == target.ordinal }

    /**
     * Runs the node's click action on the UI thread, as a click does, and waits for it. A click
     * that keeps the UI thread (a modal native dialog) is [Unresponsive]; one that throws fails.
     */
    private fun press(found: Found, kind: Kind = Kind.Click) {
        val c = found.node.config
        val action: (() -> Boolean)? = when (kind) {
            is Kind.Custom -> c[SemanticsActions.CustomActions].first { it.label == kind.label }.action
            else -> c[SemanticsActions.OnClick].action
        }
        if (action != null) onUi { action() }
    }

    /** Runs [action] on the UI thread, as an input does, and waits for it; see [press]. */
    private fun onUi(action: () -> Unit) {
        val done = CountDownLatch(1)
        var error: Throwable? = null
        EventQueue.invokeLater {
            try {
                action()
            } catch (e: Throwable) {
                error = e
            } finally {
                done.countDown()
            }
        }
        if (!done.await(CLICK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            // A native modal dialog the recorder missed: closing it frees the UI thread
            Window.getWindows().filter { it is java.awt.Dialog && it.isVisible }.forEach { it.dispose() }
            if (!done.await(5, TimeUnit.SECONDS)) {
                throw Unresponsive("$current: the window did not answer within ${CLICK_TIMEOUT_MILLIS / 1000} s of the click")
            }
            failures += "$current: the click opened a native modal dialog, which blocks the window (route it through SystemEffects)"
        }
        error?.let { throw IllegalStateException("the click threw", it) }
    }

    /** waitForIdle, and two short pauses for screen models that load on their own dispatchers. */
    private fun ComposeUiTest.settleQuickly() {
        waitForIdle()
        repeat(2) {
            Thread.sleep(100)
            waitForIdle()
        }
    }

    // Effects

    private class Snapshot(
        val stack: List<cafe.adriel.voyager.core.screen.Screen>,
        val roots: Int,
        /** The clickable items of each menu over the window, by node id, in root order. */
        val menus: List<Set<String>>,
        /** The dialogs' titles: the first text of each root that is not a menu. */
        val dialogs: List<String>,
        val screen: String,
        val settings: Map<String, Any?>,
        val rows: DbState.Rows,
        val outside: Int,
        val toasts: Int,
        val notifications: Int,
        val callbacks: Int,
        val queue: Int,
        val files: Map<String, Int>,
        val updates: Int,
        /** The screen without the text fields and sliders: what changed besides a control's own value. */
        val others: String,
        val tab: HomeTab?,
        /** The "2 selected" titles. */
        val selection: String,
        /** The focused node's id: what a shortcut moved the focus to. */
        val focus: String?,
        /** What the reader's keys change that the screen does not name. */
        val reader: ReaderKeys.State?,
    )

    private fun ComposeUiTest.snapshot(): Snapshot {
        val roots = roots(merged = false)
        // A menu is a popup; any other root over the window is a dialog
        val (menus, dialogs) = roots.withIndex().drop(1).partition { (_, root) -> root.descendants().any { SemanticsProperties.IsPopup in it.config } }
        // A menu's items as the crawl names them: by the merged tree's node ids
        val merged = roots(merged = true)
        val storage = Injekt.get<zoukan.desktop.storage.ZoukanStorage>()
        return Snapshot(
            stack = runOnUiThread { navigator.items.toList() },
            roots = roots.size,
            menus = menus.map { (index, _) -> merged.getOrNull(index)?.descendants()?.filter { matches(Kind.Click, it) }?.map(::nodeId)?.toSet().orEmpty() },
            dialogs = dialogs.map { (_, root) -> root.descendants().firstNotNullOfOrNull { it.config.getOrNull(SemanticsProperties.Text)?.joinToString { t -> t.text } } ?: "" },
            screen = roots.joinToString("\n") { root -> root.descendants().joinToString("|", transform = ::describe) },
            settings = settings(),
            rows = readDb(),
            outside = Recorder.calls.size,
            toasts = toasts.size,
            notifications = notifications.size,
            callbacks = HostCalls.count,
            queue = Injekt.get<zoukan.desktop.download.DownloadManager>().queue.value.size,
            files = mapOf("downloads" to storage.downloads, "local" to storage.local).mapValues { (_, dir) -> dir.walk().count { it.isFile } },
            updates = updates.get(),
            others = roots.joinToString("\n") { root ->
                // A field's or slider's own nodes (its text, its placeholder, its value) are its value
                val own = root.descendants().filter { matches(Kind.Type(false), it) || matches(Kind.Slide, it) }.flatMap { it.descendants() }.map { it.id }.toSet()
                root.descendants().filter { it.id !in own }.joinToString("|", transform = ::describe)
            },
            tab = runOnUiThread { HomeScreen.selectedTab },
            selection = onAllNodes(selectionCount).fetchSemanticsNodes().joinToString { it.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString { t -> t.text } },
            focus = onAllNodes(androidx.compose.ui.test.isFocused()).fetchSemanticsNodes().lastOrNull()?.let(::nodeId),
            reader = readerState(),
        )
    }

    /** The open reader's [ReaderKeys.State], read on the UI thread; null with no reader on screen. */
    private fun ComposeUiTest.readerState(): ReaderKeys.State? = roots(merged = false).flatMap { it.descendants() }
        .firstNotNullOfOrNull { it.config.getOrNull(ReaderKeys.StateKey) }?.let { state -> runOnUiThread { state() } }

    /** What a node shows: what changes when a click changes the screen. */
    private fun describe(node: SemanticsNode): String {
        val c = node.config
        return listOf(
            c.getOrNull(SemanticsProperties.Text)?.joinToString { it.text },
            c.getOrNull(SemanticsProperties.EditableText)?.text,
            c.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(),
            c.getOrNull(SemanticsProperties.StateDescription),
            c.getOrNull(SemanticsProperties.ToggleableState)?.name,
            c.getOrNull(SemanticsProperties.Selected)?.toString(),
            c.getOrNull(SemanticsProperties.Focused)?.toString(),
            c.getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.toString(),
            if (SemanticsProperties.Disabled in c) "disabled" else null,
        ).joinToString(",") { it.orEmpty() }
    }

    /**
     * A click's effect signature: [tokens] are stable from run to run and go in the inventory;
     * [details] name the rows a click wrote, for the contracts.
     */
    private class Signature(val tokens: List<String>, val details: List<String>) {
        override fun equals(other: Any?) = other is Signature && other.tokens == tokens && other.details == details

        override fun hashCode() = tokens.hashCode()
    }

    private fun quoted(text: String) = "\"" + ButtonCrawl.stable(ButtonCrawl.withoutHome(text.replace(Regex("\\s+"), " ").trim()).take(50)) + "\""

    private fun signatureOf(before: Snapshot, after: Snapshot, kind: Kind): Signature {
        val details = db.diff(before.rows, after.rows)
        val tokens = sortedSetOf<String>()
        val prefix = after.stack.size <= before.stack.size && after.stack == before.stack.take(after.stack.size)
        when {
            after.stack == before.stack -> Unit
            prefix -> tokens += "nav:back"
            else -> tokens += "nav:" + when (val top = after.stack.last()) {
                is SmokeHost -> "host"
                else -> UiInventory.screenKey(top.javaClass).removePrefix("screen:")
            }
        }
        // The new dialogs, by title
        if (after.dialogs.size > before.dialogs.size) after.dialogs.drop(before.dialogs.size).forEach { tokens += "dialog:" + quoted(it) }
        if (ButtonCrawl.openedMenu(before.menus, after.menus)) tokens += "menu"
        if (after.roots < before.roots) tokens += "close"
        Recorder.calls.drop(before.outside).take(after.outside - before.outside).forEach { tokens += it }
        toasts.drop(before.toasts).take(after.toasts - before.toasts).forEach { tokens += "toast:" + quoted(it) }
        notifications.drop(before.notifications).take(after.notifications - before.notifications).forEach { tokens += "notification:" + quoted(it) }
        if (after.callbacks > before.callbacks) tokens += "calls-back"
        (after.settings.keys + before.settings.keys).filter { after.settings[it] != before.settings[it] }.forEach { tokens += "pref:$it" }
        // CLOCK_COLUMN is set by a database trigger to the current second on every write of the entry,
        // so it shows only when the clock ticked during the click: it stays in the details, not the tokens
        details.forEach { row -> ("db:" + row.substringBefore('[')).takeIf { it != CLOCK_COLUMN }?.let { tokens += it } }
        if (after.queue > before.queue) tokens += "queue+"
        if (after.queue < before.queue) tokens += "queue-"
        after.files.forEach { (dir, count) ->
            val was = before.files.getValue(dir)
            if (count > was) tokens += "files:$dir+"
            if (count < was) tokens += "files:$dir-"
        }
        if (after.updates > before.updates) tokens += "job:library-update"
        if (after.tab != before.tab && after.tab != null) tokens += "tab:${after.tab.name}"
        if (after.selection != before.selection) tokens += "selection"
        // Only for a key: a click or typing moves the focus as a side effect. A node known only by its
        // position (@x,y) is left out: where focus lands after a key that switches screens varies
        // from run to run, so it would make the inventory flaky
        val focus = after.focus?.let(ButtonCrawl::stable)
        if (kind is Kind.Press && after.focus != before.focus && focus != null && !focus.startsWith("@")) tokens += "focus:$focus"
        if (before.reader != null && after.reader != null) {
            val (b, a) = before.reader to after.reader
            listOf(
                "page" to (a.page != b.page),
                "menu" to (a.menu != b.menu),
                "zoom" to (a.zoom != b.zoom),
                "fullscreen" to (a.fullscreen != b.fullscreen),
                "help" to (a.help != b.help),
                "auto-scroll" to (a.autoScroll != b.autoScroll),
                "settings" to (a.settings != b.settings),
                "chapters" to (a.chapters != b.chapters),
            ).filter { it.second }.forEach { tokens += "reader:${it.first}" }
        }
        if (tokens.isEmpty() && after.screen != before.screen) tokens += if (after.others == before.others) OWN_VALUE else "ui"
        return Signature(tokens.toList(), details + tokens.filter { it.startsWith("pref:") })
    }

    // The state a click may change

    private val preferences by lazy {
        Injekt.get<Context>().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    }

    /** The settings the user can change: not the app's own state (window, timestamps), which it writes as it runs. */
    private fun settings(): Map<String, Any?> = preferences.all.filterKeys {
        !tachiyomi.core.common.preference.Preference.isAppState(it) || it in USER_APP_STATE
    }

    private lateinit var settingsState: Map<String, Any?>
    private lateinit var seedState: DbState.Rows
    private var seedMangas = emptyList<tachiyomi.domain.manga.model.Manga>()
    private lateinit var db: DbState

    /** Reads on the database's own thread: the app writes there, and a second connection would find the file locked. */
    private fun readDb(): DbState.Rows = runBlocking { Injekt.get<tachiyomi.data.DatabaseHandler>().await { db.read() } }

    /**
     * Before each open: the settings as they were after seeding, and the seeded library when a
     * click changed it (Alpha and Bravo in the library, their chapters unread, no history, the
     * Smoke category, the chapter files), so each click starts from the same app.
     */
    private fun restoreState() {
        waitForLibraryUpdate()
        val now = settings()
        if (now != settingsState) {
            val editor = preferences.edit()
            (now.keys + settingsState.keys).filter { now[it] != settingsState[it] }.forEach { key ->
                @Suppress("UNCHECKED_CAST")
                when (val value = settingsState[key]) {
                    null -> editor.remove(key)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> editor.putStringSet(key, value as Set<String>)
                    else -> editor.putString(key, value.toString())
                }
            }
            editor.commit()
        }
        if (db.diff(seedState, readDb()).isEmpty()) return
        runBlocking {
            val storage = Injekt.get<zoukan.desktop.storage.ZoukanStorage>()
            listOf("Alpha", "Bravo").forEach { title ->
                (1..2).forEach { n ->
                    val file = File(File(storage.local, title), "Chapter $n.cbz")
                    if (!file.isFile) failures += "$current: the click before this removed ${file.path}"
                }
            }
            val mangas = Injekt.get<MangaRepository>()
            seedMangas.forEach { seeded ->
                val id = seeded.id
                val now = mangas.getMangaById(id)
                // In the library, with the reading mode and chapter sort and filter it was seeded with
                if (!now.favorite || now.viewerFlags != seeded.viewerFlags || now.chapterFlags != seeded.chapterFlags) {
                    mangas.update(MangaUpdate(id = id, favorite = true, viewerFlags = seeded.viewerFlags, chapterFlags = seeded.chapterFlags))
                }
                Injekt.get<SetMangaCategories>().await(id, emptyList())
                val chapters = Injekt.get<GetChaptersByMangaId>().await(id)
                Injekt.get<ChapterRepository>().updateAll(
                    chapters.map { ChapterUpdate(id = it.id, read = false, bookmark = false, lastPageRead = 0) },
                )
            }
            if (Injekt.get<GetCategories>().await().none { it.name == "Smoke" }) Injekt.get<CreateCategoryWithName>().await("Smoke")
            Injekt.get<RemoveHistory>().awaitAll()
        }
        val after = readDb()
        db.diff(seedState, after).takeIf { it.isNotEmpty() }?.let { println("Crawl: after restoring the seed, still changed: ${it.joinToString()}") }
        // What restoring cannot undo (a sort order) stays, and the next click starts from it
        seedState = after
    }

    /** A click may start a library update, a backup or a restore; the next click waits for it to end. */
    private fun waitForLibraryUpdate() {
        val updater = Injekt.get<LibraryUpdater>()
        val deadline = System.currentTimeMillis() + 30_000
        while ((updater.isRunning || zoukan.desktop.ui.settings.backupStatusFlow.value != null) && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
        }
    }

    // Report and baseline

    /** Signatures of a text field or slider task that changed nothing but the control itself. */
    private val inert = setOf(REJECTED, OWN_VALUE, KEY_UNUSED, AT_END)

    /**
     * Why [key]'s [signature] fails the run, or null: no effect; only the screen changed where a
     * setting or data should have; a text field on a screen where typing and Enter changed nothing
     * but the field; a slider that neither stored nor showed anything at either end.
     */
    private fun problem(key: String, signature: String): String? {
        val label = key.substringAfterLast('/').substringAfterLast(" > ")
        val settingToggle = toggles[key] == true && Contracts.all.any { it.button.containsMatchIn(contractKey(key)) }
        return when {
            signature == NO_EFFECT -> "it did nothing"
            signature == "ui" && settingToggle -> "a setting's switch or choice that changed the screen but stored nothing"
            // A click that opened a menu or submenu ("Download" with its choices) is "menu", not "ui":
            // its items are crawled and checked instead
            signature == "ui" && toggles[key] != true && dataAction.containsMatchIn(label) -> "the label says it changes data, but the click only changed the screen"
            typeKey.containsMatchIn(key) && inDialog[key] != true && signature in inert &&
                effects[key.replace(" [type]", " [type, enter]")] in inert -> "typing and Enter changed nothing but the field: no setting, search or screen took it"
            sliderKey.containsMatchIn(key) && signature in inert &&
                effects[key.replace(" [slider max]", " [slider min]")] in inert -> "the slider stored nothing and changed nothing else at either end"
            else -> null
        }
    }

    private val typeKey = Regex(""" \[type]( #2)?$""")
    private val sliderKey = Regex(""" \[slider max]( #2)?$""")

    /** A label that says the button changes data: such a button must do more than change the screen. */
    private val dataAction = Regex("""(?i)^"(mark as|bookmark|add to library|in library|remove|delete|download|save|restore|clear(?! (selection|search))|reset|migrate|merge)\b""")

    /**
     * Writes the report, then fails on: clicks with no effect, and clicks that only changed the
     * screen where the button is a setting's switch or choice or its label says it changes data,
     * unless the allowlist names them; a broken contract; and a button whose signature differs
     * from button-inventory.txt. A [partial] run (-Pzoukan.crawl.only) cannot tell a button that
     * went from one it did not open.
     */
    private fun compare(keys: Set<String>, partial: Boolean) {
        val allowlist = ButtonCrawl.parseAllowlist(ButtonCrawl.resource(ButtonCrawl.ALLOWLIST))
        val report = File("build/reports/coverage/buttons.md")
        report.parentFile.mkdirs()
        fun cell(s: String) = s.replace("|", "\\|")
        report.writeText(
            buildString {
                appendLine("# Buttons")
                appendLine()
                appendLine("${effects.size} buttons in ${keys.size} checks; ${effects.count { it.value == NO_EFFECT }} with no effect, ${unreached.size} not found again.")
                appendLine()
                appendLine("| Button | Signature | Rows written |")
                appendLine("|---|---|---|")
                effects.forEach { (key, effect) ->
                    val rows = evidence[key].orEmpty().filter { '[' in it }.joinToString(", ")
                    appendLine("| ${cell(key)} | ${cell(effect)} | ${cell(rows)} |")
                }
            },
        )
        println("Crawl: report in $report")

        // No effect, or only the screen changed where data should have
        effects.forEach { (key, signature) ->
            if (key in allowlist) return@forEach
            problem(key, signature)?.let { failures += "$key: $it (if that is on purpose, add it to ${ButtonCrawl.ALLOWLIST} with the reason)" }
        }
        if (!partial && System.getProperty("zoukan.shard") == null) {
            // The allowlist names only buttons that still need it
            allowlist.keys.filter { key -> effects[key].let { it == null || (it != "ui" && problem(key, it) == null) } }.forEach {
                failures += "$it is in ${ButtonCrawl.ALLOWLIST} but the click now does something (or the button is gone): remove its line"
            }
            // Every shortcut is pressed on a check that exists
            KEYS.map { it.first }.distinct().filter { it !in keys }.forEach { failures += "the shortcuts of $it have no check to be pressed on: update KEYS" }
        }
        // The contracts: the right row, the right preference
        Contracts.all.forEach { contract ->
            val matched = effects.keys.filter { contract.button.containsMatchIn(contractKey(it)) }
            matched.forEach { key ->
                // Choosing what is already chosen rightly writes nothing
                if (key !in allowlist && effects[key] != "selected-already" && evidence[key].orEmpty().none { contract.expects.containsMatchIn(it) }) {
                    failures += "$key: ${contract.means}, but it left ${evidence[key].orEmpty().ifEmpty { listOf(effects[key].orEmpty()) }}"
                }
            }
            val checkKey = contract.button.pattern.removePrefix("^").substringBefore('/').replace("\\.", ".")
            if (matched.isEmpty() && checkKey in keys && !partial) failures += "contract \"${contract.means}\" matched no button: update it in ButtonEffects.kt"
        }

        fun ofThisRun(key: String) = key.substringBefore('/') in keys
        // What this run found, in the inventory's format: CI uploads it, so a shard's part of a
        // new inventory can be taken from there without crawling locally
        File(report.parentFile, "button-inventory-found.txt").writeText(ButtonCrawl.formatInventory(effects))
        val baseline = ButtonCrawl.parseInventory(ButtonCrawl.resource(ButtonCrawl.INVENTORY))
        if (System.getProperty("zoukan.crawl.updateBaseline") == "true") {
            val file = File(ButtonCrawl.sources, ButtonCrawl.INVENTORY)
            val kept = if (partial) baseline - effects.keys else baseline.filterKeys { !ofThisRun(it) }
            file.writeText(ButtonCrawl.formatInventory(kept + effects))
            println("Crawl: wrote ${file.path}")
            return
        }
        val added = effects.keys - baseline.keys
        val gone = if (partial) emptySet() else baseline.keys.filter(::ofThisRun).toSet() - effects.keys
        val changed = effects.filter { (key, signature) -> key in baseline && baseline[key] != signature }
        if (added.isNotEmpty() || gone.isNotEmpty() || changed.isNotEmpty()) {
            failures += buildString {
                appendLine("the buttons differ from ${ButtonCrawl.INVENTORY}:")
                added.sorted().forEach { appendLine("  new:     $it  ${effects[it]}") }
                gone.sorted().forEach { appendLine("  gone:    $it  ${baseline[it]}") }
                changed.forEach { (key, signature) -> appendLine("  changed: $key  ${baseline[key]}  ->  $signature") }
                append(
                    "After a change on purpose, regenerate it: ./gradlew :desktop:buttonCrawlTest -Pzoukan.crawl.updateBaseline=true " +
                        "(desktop/TESTING.md, 'The button crawl')",
                )
            }
        }
    }
}
