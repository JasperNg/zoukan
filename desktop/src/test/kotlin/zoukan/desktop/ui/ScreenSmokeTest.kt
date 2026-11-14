package zoukan.desktop.ui

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
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
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
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.ZoukanRuntime
import zoukan.desktop.backup.DesktopBackups
import zoukan.desktop.download.CbzWriter
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
import zoukan.desktop.ui.settings.mangaDexSettings
import zoukan.desktop.ui.settings.openSettingsCategory
import zoukan.desktop.ui.track.TrackingDialog
import zoukan.desktop.ui.whatsnew.UpdatedDialog
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.util.Collections
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.minutes

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
 * - Every Settings page (MangaDex's, which only shows with a MangaDex source installed, is rendered
 *   on its own), and Settings search, which lists every page's settings at once.
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
 * Starts the runtime, which can run only once per JVM, so it needs a JVM of its own:
 *
 *   xvfb-run -a ./gradlew :desktop:test --tests "*ScreenSmokeTest" -Pzoukan.e2e=true
 */
@OptIn(ExperimentalTestApi::class)
class ScreenSmokeTest {

    private class Fixture(val alpha: Manga, val bravo: Manga, val chapters: List<Chapter>, val backup: File)

    /** One screen or dialog to open: [open] runs on the UI thread, then [drive] in the test. */
    private class Check(
        val name: String,
        val open: (Navigator) -> Unit,
        val drive: ComposeUiTest.() -> Unit = {},
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
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            fail("uncaught on thread ${thread.name}", e)
            previous?.uncaughtException(thread, e)
        }
        startUi(dirs)
        val fixture = seed()

        val screens = screens(fixture)
        val checks = screens + tabs() + settings() + dialogs(fixture) + uiDialogs()
        println("Smoke: ${screens.size} screens, ${checks.size} checks")
        runChecks(checks)

        current = "log"
        val log = File(dirs.logs, "zoukan.log").takeIf { it.exists() }?.readLines().orEmpty()
        log.filter { "No registered instance or factory" in it }.forEach { failures += "log: $it" }
        failures.shouldBeEmpty()
    }

    private fun fail(where: String, e: Throwable) {
        println("Smoke: FAILED in $current ($where): $e")
        e.printStackTrace()
        failures += "$current ($where): $e"
    }

    /**
     * Runs the checks in one Compose test, and in a new one after a check that failed, since a
     * composition that threw can't go on.
     */
    private fun runChecks(checks: List<Check>) {
        val preferences = Injekt.get<DesktopPreferences>()
        var next = 0
        while (next < checks.size) {
            var failed = false
            try {
                runDesktopComposeUiTest(width = 1280, height = 820, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
                    var navigator: Navigator? = null
                    setContent { ZoukanContent(preferences) { navigator = it } }
                    waitForIdle()
                    while (next < checks.size) {
                        val check = checks[next]
                        current = check.name
                        println("Smoke: ${check.name}")
                        try {
                            runOnUiThread { check.open(navigator!!) }
                            settle()
                            check.drive(this)
                            scrollLists()
                            settle()
                            runOnUiThread {
                                navigator!!.popUntilRoot()
                                HomeScreen.selectTab(HomeTab.Library)
                            }
                            waitForIdle()
                        } catch (e: Throwable) {
                            fail("check", e)
                            failed = true
                            next++
                            throw e
                        }
                        next++
                    }
                }
            } catch (e: Throwable) {
                // Thrown again at the end of the test it failed in, or at its end
                if (!failed) {
                    fail("end of the Compose test", e)
                    next++
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

    /** Scrolls every lazy list on screen to its end, so each of its rows composes. */
    private fun ComposeUiTest.scrollLists() {
        val lists = onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().size
        repeat(lists) { index ->
            val node = onAllNodes(hasScrollToIndexAction())[index]
            val rows = runCatching {
                node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.CollectionInfo)?.let { maxOf(it.rowCount, it.columnCount) }
            }.getOrNull() ?: return@repeat
            if (rows < 0) return@repeat
            for (row in 0 until rows step 6) {
                // A node gone from the screen is the test's problem, not the app's
                try {
                    node.performScrollToIndex(row)
                } catch (_: AssertionError) {
                    return@repeat
                } catch (_: IllegalArgumentException) {
                    return@repeat
                }
            }
            if (rows > 0) runCatching { node.performScrollToIndex(rows - 1) }
            waitForIdle()
        }
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
        val updater = Injekt.get<LibraryUpdater>()
        updater.start()
        while (updater.progress.value != null || updater.lastResult.value == null) Thread.sleep(200)
        val favorites = runBlocking { Injekt.get<GetFavorites>().await() }.sortedBy { it.title }
        favorites shouldHaveSize 2
        val chapters = runBlocking { Injekt.get<GetChaptersByMangaId>().await(favorites[0].id) }
        chapters.size shouldBeGreaterThan 0
        val backup = File(ZoukanRuntime.dirs.root, "smoke.tachibk")
        runBlocking { DesktopBackups(ZoukanRuntime.dirs.android).create(backup) }
        return Fixture(favorites[0], favorites[1], chapters, backup)
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
     * arguments goes here; the test fails and names it until it does.
     */
    private fun screensWithArguments(f: Fixture): Map<Class<out Screen>, List<Screen>> = mapOf(
        MangaScreen::class.java to listOf(MangaScreen(f.alpha.id)),
        PagePreviewScreen::class.java to listOf(PagePreviewScreen(f.alpha.id)),
        MetadataViewScreen::class.java to listOf(MetadataViewScreen(f.alpha.id, LocalSource.ID)),
        ReaderScreen::class.java to listOf(ReaderScreen(f.alpha.id, f.chapters.first().id), ReaderScreen(f.alpha.id, f.chapters.last().id, page = 1)),
        SourceFeedScreen::class.java to listOf(SourceFeedScreen(LocalSource.ID)),
        SourceScreen::class.java to listOf(
            SourceScreen(LocalSource.ID),
            SourceScreen(LocalSource.ID, latest = true),
            SourceScreen(LocalSource.ID, query = "Alpha"),
        ),
        GlobalSearchScreen::class.java to listOf(
            GlobalSearchScreen("Alpha"),
            GlobalSearchScreen("Bravo", SearchPurpose.Merge(f.alpha.id)),
            GlobalSearchScreen("Bravo", SearchPurpose.Migrate(f.alpha.id, LocalSource.ID)),
            GlobalSearchScreen("Bravo", SearchPurpose.MigrateInList(f.alpha.id, LocalSource.ID)),
        ),
        MigrateMangaScreen::class.java to listOf(MigrateMangaScreen(LocalSource.ID)),
        ExtensionDetailsScreen::class.java to listOf(ExtensionDetailsScreen("eu.kanade.tachiyomi.extension.en.notinstalled")),
        OpenUrlScreen::class.java to listOf(OpenUrlScreen("https://example.invalid/manga/1")),
        SourceSettingsScreen::class.java to listOf(SourceSettingsScreen(LocalSource.ID)),
        RestoreBackupScreen::class.java to listOf(RestoreBackupScreen(f.backup.path)),
        MigrationListScreen::class.java to listOf(
            MigrationListScreen(listOf(f.alpha.id, f.bravo.id), null),
            MigrationListScreen(listOf(f.alpha.id), "Bravo", runManually = true),
        ),
        MigrationConfigScreen::class.java to listOf(MigrationConfigScreen(listOf(f.alpha.id, f.bravo.id))),
        RecommendsScreen::class.java to listOf(RecommendsScreen(f.alpha.id)),
        CommonRecommendsScreen::class.java to listOf(CommonRecommendsScreen(emptyList())),
    )

    private fun screens(f: Fixture): List<Check> {
        val withArguments = screensWithArguments(f)
        val classes = screenClasses()
        // About 30 when this was written; far fewer means the scan stopped finding them
        classes.size shouldBeGreaterThan 20
        val missing = mutableListOf<String>()
        val screens = classes.filter { it != HomeScreen::class.java }.flatMap { type ->
            withArguments[type] ?: listOfNotNull(instance(type)).ifEmpty {
                missing += type.name
                emptyList()
            }
        }
        missing.shouldBeEmpty()
        return screens.map { screen -> Check("screen ${screen.javaClass.simpleName} (${screen.key})", { it.push(screen) }) }
    }

    /** An object screen's instance, or a new screen of a class without arguments; null otherwise. */
    private fun instance(type: Class<out Screen>): Screen? {
        runCatching { type.getDeclaredField("INSTANCE") }.getOrNull()
            ?.takeIf { Modifier.isStatic(it.modifiers) }
            ?.let { return it.apply { isAccessible = true }.get(null) as Screen }
        return type.declaredConstructors.firstOrNull { it.parameterCount == 0 }
            ?.apply { isAccessible = true }
            ?.newInstance() as Screen?
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
            val tab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
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

    private fun settings(): List<Check> = SettingsCategory.entries.filter { it != SettingsCategory.MangaDex }.map { page ->
        Check("settings ${page.name}", {
            openSettingsCategory(page)
            HomeScreen.openSettings(it)
        })
    } + listOf(
        Check("settings MangaDex", {
            it.push(
                Host("mangadex") {
                    androidx.compose.foundation.lazy.LazyColumn { mangaDexSettings() }
                },
            )
        }),
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
            dialog("first-run setup") {
                val preferences = remember { Injekt.get<DesktopPreferences>().also { it.shownOnboardingFlow().set(false) } }
                FirstRun(preferences) { null }
            },
            Check("onboarding reopened", { Onboarding.reopen.value = true }, drive = {
                settle()
                runOnUiThread { Onboarding.reopen.value = false }
            }),
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
            drive = {
                val lock = Injekt.get<AppLock>()
                settle()
                runOnUiThread { lock.unlock("smoke") }
                Injekt.get<SecurityPreferences>().useAuthenticator().set(false)
                lock.clearPassword()
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
            drive = { clickText("Add") },
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

    private fun ComposeUiTest.clickDescription(description: String) {
        val matcher = SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(description))
        waitUntil(timeoutMillis = 10_000) { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        onAllNodes(matcher).onFirst().performClick()
        settle()
    }
}
