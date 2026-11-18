@file:OptIn(ExperimentalTestApi::class)

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
import zoukan.desktop.ui.settings.BlockListsDialog
import zoukan.desktop.ui.settings.CategoriesIncludeExcludeDialog
import zoukan.desktop.ui.settings.ChoiceSetting
import zoukan.desktop.ui.settings.MultiSelectPreference
import zoukan.desktop.ui.settings.RestoreBackupScreen
import zoukan.desktop.ui.settings.SettingsCategory
import zoukan.desktop.ui.settings.SourceSettingsScreen
import zoukan.desktop.ui.settings.openSettingsCategory
import zoukan.desktop.ui.track.TrackingDialog
import zoukan.desktop.ui.whatsnew.DohNoticeDialog
import zoukan.desktop.ui.whatsnew.UpdatedDialog
import zoukan.desktop.update.Updates
import zoukan.i18n.desktop.DMR
import java.awt.EventQueue
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * One screen or dialog to open: [open] runs on the UI thread, then [drive] in the test, and
 * [cleanup] on the UI thread even when they fail, to undo state that later checks would see.
 * [key] is its coverage-matrix key (coverage-matrix.yaml); variants of one screen share it.
 */
@OptIn(ExperimentalTestApi::class)
internal class SmokeCheck(
    val key: String,
    val name: String,
    val open: (Navigator) -> Unit,
    val drive: ComposeUiTest.() -> Unit = {},
    val cleanup: () -> Unit = {},
    /** Matrix keys this check also opens, such as a dialog its [drive] waits for. */
    val alsoCovers: List<String> = emptyList(),
    /** A dialog's dismiss, which the usability check's Escape rule asserts. */
    val probe: DismissProbe? = null,
)

internal class SmokeFixture(val alpha: Manga, val bravo: Manga, val chapters: List<Chapter>, val backup: File)

/** A screen that only shows [content], to render a dialog with a navigator and screen models around it. */
internal class SmokeHost(private val name: String, @Transient private val content: @Composable Screen.() -> Unit) : Screen {
    override val key: ScreenKey = "smoke-host-$name/"

    @Composable
    override fun Content() = content()
}

/**
 * The callbacks the directly rendered dialogs get instead of the app's: each call is counted, so
 * the button crawl sees that a dialog's button called back, where the smoke test ignores it.
 */
internal object HostCalls {
    @Volatile
    var count = 0
        private set

    fun record() {
        count++
    }
}

/** Rows per scroll step: fewer than a screen holds, so every row composes on the way. */
internal const val SCROLL_STEP = 6

/** Bounds a list's scrolling, at 3,000 rows. */
private const val MAX_SCROLL_STEPS = 500

private val OUT_OF_BOUNDS = Regex("""^Can't scroll to index -?\d+, it is out of bounds \[0, (\d+)\)""")

/**
 * What [ScreenSmokeTest] and [ButtonCrawlTest] share: the runtime they start, the library they
 * seed and the list of checks, one per screen, tab, Settings page and dialog of the app. See
 * [ScreenSmokeTest] for what the checks open and why.
 */
@OptIn(ExperimentalTestApi::class)
internal object SmokeChecks {
    /** The date of the seeded entries and chapters (2024-01-15), fixed so screenshots show the same dates every day. */
    const val SEED_DATE = 1_705_314_000_000L

    /**
     * Starts the runtime, once per JVM, with the file secret store and a throwaway data folder
     * named after [prefix], and turns the daily update check off. The caller starts the UI.
     */
    fun startRuntime(prefix: String): ZoukanDirs {
        System.setProperty(SecretStore.FILE_STORE_PROPERTY, "true")
        val dirs = ZoukanDirs(newTempDir(prefix))
        ZoukanRuntime.start(dirs, interactiveBrowser = false)
        // The run lasts minutes, and the daily update check would call GitHub after 30 s
        Updates.autoCheck().set(false)
        return dirs
    }

    /**
     * A new folder `<prefix><18 digits>` in the system temp folder. Files.createTempDirectory adds a
     * random number of 1 to 20 digits, and labels that show the data folder are cut to a fixed
     * length before ButtonCrawl.stable turns the digits into `#`, so a different count could move
     * the cut and change a button key between two runs on the same machine. A fixed count cannot.
     */
    private fun newTempDir(prefix: String): File {
        val temp = File(System.getProperty("java.io.tmpdir"))
        repeat(100) {
            val name = prefix + "%018d".format(ThreadLocalRandom.current().nextLong(1_000_000_000_000_000_000L))
            val dir = File(temp, name)
            if (dir.mkdir()) return dir
        }
        error("could not make a $prefix folder in $temp")
    }

    /** Every check, in the smoke test's order. */
    fun all(f: SmokeFixture, screens: List<SmokeCheck> = screens(f)): List<SmokeCheck> =
        screens + tabs() + settings() + dialogs(f) + uiDialogs()

    // Seeding

    fun seed(): SmokeFixture {
        val storage = Injekt.get<ZoukanStorage>()
        listOf("Alpha", "Bravo").forEach { title ->
            val dir = File(storage.local, title).apply { mkdirs() }
            // A fixed modified time too: it becomes the chapter's upload date, which a screenshot shows
            (1..2).forEach { cbz(File(dir, "Chapter $it.cbz"), 3, title).setLastModified(SEED_DATE) }
        }
        Injekt.get<LibraryPreferences>().autoUpdateMangaRestrictions().set(emptySet())
        runBlocking {
            Injekt.get<SourceManager>().isInitialized.first { it }
            Injekt.get<CreateCategoryWithName>().await("Smoke")
            val source = Injekt.get<SourceManager>().get(LocalSource.ID) as CatalogueSource
            val found = source.getPopularManga(1).mangas.map { it.toDomainManga(LocalSource.ID) }
            Injekt.get<NetworkToLocalManga>()(found).forEach { manga ->
                Injekt.get<MangaRepository>().update(MangaUpdate(id = manga.id, favorite = true, dateAdded = SEED_DATE))
            }
        }
        updateLibrary()
        val favorites = runBlocking { Injekt.get<GetFavorites>().await() }.sortedBy { it.title }
        favorites shouldHaveSize 2
        val chapters = runBlocking { Injekt.get<GetChaptersByMangaId>().await(favorites[0].id) }
        chapters.size shouldBeGreaterThan 0
        val backup = File(ZoukanRuntime.dirs.root, "smoke.tachibk")
        runBlocking { DesktopBackups(ZoukanRuntime.dirs.android).create(backup) }
        return SmokeFixture(favorites[0], favorites[1], chapters, backup)
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

    private fun cbz(file: File, pages: Int, label: String): File {
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
        return file
    }

    // Screens

    /**
     * The screens that take arguments, with the arguments to open them with. A new screen with
     * arguments goes here; the test fails and names it until it does. Each is made only when its
     * check opens it, so a screen that throws as it's made fails as that screen.
     */
    private fun screensWithArguments(f: SmokeFixture): Map<Class<out Screen>, List<() -> Screen>> = mapOf(
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
        CommonRecommendsScreen::class.java to listOf({ CommonRecommendsScreen(listOf(f.alpha.id)) }),
    )

    fun screens(f: SmokeFixture): List<SmokeCheck> {
        val withArguments = screensWithArguments(f)
        val classes = ScreenScan.screenClasses()
        // About 30 when this was written; far fewer means the scan stopped finding them
        classes.size shouldBeGreaterThan 20
        val missing = mutableListOf<String>()
        val checks = classes.filter { it != HomeScreen::class.java }.flatMap { type ->
            val makers = withArguments[type] ?: listOfNotNull(ScreenScan.maker(type)).ifEmpty {
                missing += type.name
                emptyList()
            }
            makers.mapIndexed { i, make ->
                val variant = if (makers.size > 1) " ${i + 1}" else ""
                SmokeCheck(UiInventory.screenKey(type), "screen ${type.simpleName}$variant", { it.push(make()) })
            }
        }
        missing.shouldBeEmpty()
        return checks + deletedEntryCheck()
    }

    /**
     * The entry screen of an entry the database then loses, as Settings > Clear database does it:
     * the screen must say so, and no failure may reach a thread's uncaught handler.
     */
    private fun deletedEntryCheck(): SmokeCheck {
        val message = localized(DMR.strings.this_entry_no_longer_exists)
        return SmokeCheck(
            key = UiInventory.screenKey(MangaScreen::class.java),
            name = "manga screen of a deleted entry",
            open = { navigator ->
                val gone = SManga.create().apply {
                    url = "Gone"
                    title = "Gone"
                }.toDomainManga(LocalSource.ID)
                val entry = runBlocking { Injekt.get<NetworkToLocalManga>()(listOf(gone)).single() }
                navigator.push(MangaScreen(entry.id))
            },
            drive = {
                runBlocking {
                    Injekt.get<tachiyomi.data.DatabaseHandler>().await(inTransaction = true) {
                        mangasQueries.deleteNonLibraryManga(listOf(LocalSource.ID), 0L)
                    }
                }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty() }
            },
        )
    }

    // Tabs and settings

    fun tabs(): List<SmokeCheck> = HomeTab.entries.map { tab ->
        SmokeCheck(
            "tab:${tab.name}",
            "tab ${tab.name}",
            { HomeScreen.selectTab(tab) },
            // Every check renders the main window and its shell; the first tab's check names them
            alsoCovers = if (tab == HomeTab.entries.first()) listOf("screen:HomeScreen", "state:shell") else emptyList(),
        )
    } + BrowsePage.entries.map { page ->
        // The default preferences show every page (hiding the feed is a setting); clickTag fails
        // when the page's tab is not there
        SmokeCheck(
            "browse:${page.name}",
            "browse ${page.name}",
            { HomeScreen.selectTab(HomeTab.Browse) },
            drive = {
                clickTag("browse-page-${page.name}")
                // The click must select this page, not leave another one showing
                waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag("browse-page-${page.name}") and isSelected()).fetchSemanticsNodes().isNotEmpty() }
                settle()
                when (page) {
                    BrowsePage.SOURCES -> assertOneGlobalSearch("source-search")
                    BrowsePage.EXTENSIONS -> assertOneGlobalSearch("extension-search")
                    else -> Unit
                }
            },
        )
    }

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

    fun settings(): List<SmokeCheck> = SettingsCategory.entries.filter { it != SettingsCategory.MangaDex }.map { page ->
        SmokeCheck("settings:${page.name}", "settings ${page.name}", {
            openSettingsCategory(page)
            HomeScreen.openSettings(it)
        })
    } + listOf(
        run {
            val mangaDex = MangaDexStandIn()
            var navigator: Navigator? = null
            SmokeCheck(
                "settings:MangaDex",
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
                drive = {
                    val sync = localized(SYMR.strings.mangadex_sync_follows_to_library)
                    clickText(sync)
                    // The follows dialog's title repeats the row's text
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(sync).fetchSemanticsNodes().size >= 2 }
                },
                // The follows dialog opens only through this page
                alsoCovers = listOf("dialog:settings.SyncFollowsDialog"),
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
        SmokeCheck(
            "settings:search",
            "settings search",
            { HomeScreen.openSettings(it) },
            drive = {
                onAllNodes(hasSetTextAction()).onFirst().performTextInput("e")
                settle()
                scrollLists()
                onAllNodes(hasSetTextAction()).onFirst().performTextClearance()
            },
        ),
        SmokeCheck(
            "state:settings-theme-row",
            "settings Appearance theme row scrolls",
            {
                openSettingsCategory(SettingsCategory.Appearance)
                HomeScreen.openSettings(it)
            },
            drive = {
                // More themes than fit: the row shows a scrollbar to reach them
                waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("theme-row-scrollbar")).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("theme-row-scrollbar").assertIsDisplayed()
            },
        ),
        run {
            val exh by lazy { Injekt.get<ExhPreferences>().isHentaiEnabled() }
            val sync by lazy { Injekt.get<SyncPreferences>().syncService() }
            val lock by lazy { Injekt.get<SecurityPreferences>().useAuthenticator() }
            var before = Triple(false, SyncManager.SyncService.NONE.value, false)
            SmokeCheck(
                "settings:search-hidden",
                "settings search finds hidden settings",
                {
                    // The switches that reveal these settings are off
                    before = Triple(exh.get(), sync.get(), lock.get())
                    exh.set(false)
                    sync.set(SyncManager.SyncService.NONE.value)
                    lock.set(false)
                    HomeScreen.openSettings(it)
                },
                drive = {
                    listOf(
                        localized(DMR.strings.use_japanese_titles),
                        localized(DMR.strings.lock_on_these_days),
                        localized(KMR.strings.web_dav),
                        localized(MR.strings.theme_nord),
                    ).forEach { query ->
                        onAllNodes(hasSetTextAction()).onFirst().performTextInput(query)
                        settle()
                        check(onAllNodes(hasText(localized(DMR.strings.no_settings_match, query))).fetchSemanticsNodes().isEmpty()) {
                            "settings search found nothing for \"$query\""
                        }
                        onAllNodes(hasSetTextAction()).onFirst().performTextClearance()
                    }
                },
                cleanup = {
                    exh.set(before.first)
                    sync.set(before.second)
                    lock.set(before.third)
                },
            )
        },
    )

    // Dialogs

    /**
     * A check that renders [content]; [key] is "dialog:" + the dialog function's package under ui and name.
     * [content] gets the dialog's dismiss, which records its call for the usability check's Escape rule.
     */
    private fun dialog(key: String, name: String, content: @Composable Screen.(dismiss: () -> Unit) -> Unit): SmokeCheck {
        val probe = DismissProbe()
        return SmokeCheck(key, "dialog $name", {
            probe.called = false
            it.push(SmokeHost(name) { content(probe.dismiss) })
        }, probe = probe)
    }

    /** The dialogs that other code can show, rendered directly. */
    fun dialogs(f: SmokeFixture): List<SmokeCheck> {
        // As the app hands them to the dialogs: the nameless system category is never offered
        val categories = runBlocking { Injekt.get<GetCategories>().await() }.filterNot { it.isSystemCategory }
        val noop = { HostCalls.record() }
        return listOf(
            dialog("dialog:manga.CategoryDialog", "CategoryDialog") { CategoryDialog(categories, { noop() }, it) },
            dialog("dialog:manga.ChangeCategoryDialog", "ChangeCategoryDialog") {
                ChangeCategoryDialog(categories, setOf(categories.last().id), { noop() }, it)
            },
            dialog("dialog:manga.PickDialog", "PickDialog merge") { PickDialog(SearchPurpose.Merge(f.alpha.id), f.bravo, it) },
            dialog("dialog:manga.PickDialog", "PickDialog migrate") {
                PickDialog(SearchPurpose.Migrate(f.alpha.id, LocalSource.ID), f.bravo, it)
            },
            dialog("dialog:manga.MigrateDialog", "MigrateDialog") { MigrateDialog(f.alpha.id, f.bravo, it) },
            dialog("dialog:manga.MergedEntriesDialog", "MergedEntriesDialog") { MergedEntriesDialog(f.alpha.id, it) },
            dialog("dialog:common.ConfirmDestructiveDialog", "ConfirmDestructiveDialog") {
                zoukan.desktop.ui.common.ConfirmDestructiveDialog("Title", "Text", "Confirm", { noop() }, it)
            },
            dialog("dialog:manga.ClearMangaDialog", "ClearMangaDialog") { ClearMangaDialog(it, localFolders = listOf("Alpha")) { _, _, _ -> noop() } },
            dialog("dialog:manga.FetchIntervalDialog", "FetchIntervalDialog") { FetchIntervalDialog(f.alpha, it) },
            dialog("dialog:manga.CoverDialog", "CoverDialog") { CoverDialog(f.alpha, it) },
            dialog("dialog:manga.DuplicateDialog", "DuplicateDialog") {
                DuplicateDialog(f.alpha, listOf(MangaWithChapterCount(f.bravo, 2)), noop, { noop() }, { noop() }, it)
            },
            dialog("dialog:manga.CustomDownloadDialog", "CustomDownloadDialog") { CustomDownloadDialog({ noop() }, it) },
            dialog("dialog:manga.EditMangaDialog", "EditMangaDialog") { EditMangaDialog(f.alpha, it) },
            dialog("dialog:manga.ChapterSettingsDialog", "ChapterSettingsDialog") { dismiss ->
                val model = rememberScreenModel { MangaScreenModel(f.alpha.id) }
                val state by model.state.collectAsState()
                ChapterSettingsDialog(state, model, dismiss)
            },
            dialog("dialog:track.TrackingDialog", "TrackingDialog") { TrackingDialog(rememberScreenModel { TrackingModel(f.alpha) }, it) },
            dialog("dialog:library.LibrarySettingsDialog", "LibrarySettingsDialog") {
                LibrarySettingsDialog(rememberScreenModel { LibraryScreenModel() }, it)
            },
            dialog("dialog:library.SyncFavoritesNotesDialog", "SyncFavoritesNotesDialog") { SyncFavoritesNotesDialog(it, noop) },
            dialog("dialog:library.SyncFavoritesConfirmDialog", "SyncFavoritesConfirmDialog") { SyncFavoritesConfirmDialog(it) },
            dialog("dialog:reader.PageActionsDialog", "PageActionsDialog") {
                PageActionsDialog(PageActionsTarget(0), it, { noop() }, { noop() }, { noop() }, noop, noop)
            },
            dialog("dialog:reader.PageActionsDialog", "PageActionsDialog double page") {
                PageActionsDialog(PageActionsTarget(0, 1), it, { noop() }, { noop() }, { noop() }, noop, noop)
            },
            dialog("dialog:reader.ReaderChapterListDialog", "ReaderChapterListDialog") {
                ReaderChapterListDialog(f.chapters, f.chapters.first().id, { noop() }, { noop() }, it)
            },
            dialog("dialog:reader.ReaderSettingsDialog", "ReaderSettingsDialog") {
                ReaderSettingsDialog(rememberScreenModel { ReaderScreenModel(f.alpha.id, f.chapters.first().id) }, it)
            },
            dialog("dialog:browse.FeedOrderDialog", "FeedOrderDialog") {
                FeedOrderDialog(rememberScreenModel { FeedScreenModel() }, emptyList(), it)
            },
            dialog("dialog:browse.SourceCategoriesDialog", "SourceCategoriesDialog") { dismiss ->
                val source = remember { Injekt.get<SourceManager>().get(LocalSource.ID) as CatalogueSource }
                SourceCategoriesDialog(source, listOf("Smoke", "Other"), setOf("Smoke"), { noop() }, dismiss, noop)
            },
            dialog("dialog:browse.OpenUrlDialog", "OpenUrlDialog") { OpenUrlDialog(it) },
            dialog("dialog:whatsnew.UpdatedDialog", "UpdatedDialog") { UpdatedDialog(it, noop) },
            dialog("dialog:whatsnew.DohNoticeDialog", "DohNoticeDialog") { DohNoticeDialog(it, noop) },
            dialog("dialog:settings.BlockListsDialog", "BlockListsDialog") {
                BlockListsDialog("https://example.org/list.txt", it) { noop() }
            },
            dialog("dialog:settings.CategoriesIncludeExcludeDialog", "CategoriesIncludeExcludeDialog") {
                CategoriesIncludeExcludeDialog("Smoke", "Smoke", categories, setOf(categories.first().id.toString()), emptySet(), it) { _, _ -> noop() }
            },
            dialog("dialog:recs.RecommendationSearchDialog", "RecommendationSearchDialog") { RecommendationSearchDialog(it, noop) },
        ) + listOf(
            SearchStatus.Initializing,
            SearchStatus.Processing(SManga.create().apply { title = "Alpha" }, 1, 3),
            SearchStatus.Error("Smoke"),
            SearchStatus.Cancelling,
        ).map { status ->
            dialog("dialog:recs.RecommendationSearchProgressDialog", "RecommendationSearchProgressDialog ${status.javaClass.simpleName}") {
                RecommendationSearchProgressDialog(status, it, noop)
            }
        } + listOf(
            dialog("dialog:browse.QuickLibraryToggleDialogs", "QuickLibraryToggleDialogs remove") { dismiss ->
                val toggle = rememberQuickLibraryToggle()
                remember { toggle.removing = f.alpha }
                QuickLibraryToggleDialogs(toggle)
                if (toggle.removing == null) SideEffect(dismiss)
            },
            dialog("dialog:browse.QuickLibraryToggleDialogs", "QuickLibraryToggleDialogs duplicates") { dismiss ->
                val toggle = rememberQuickLibraryToggle()
                remember { toggle.duplicates = f.bravo to listOf(MangaWithChapterCount(f.alpha, 2)) }
                QuickLibraryToggleDialogs(toggle)
                if (toggle.duplicates == null) SideEffect(dismiss)
            },
            dialog("dialog:browse.QuickLibraryToggleDialogs", "QuickLibraryToggleDialogs categories") { dismiss ->
                val toggle = rememberQuickLibraryToggle()
                remember { toggle.categories = f.bravo to categories }
                QuickLibraryToggleDialogs(toggle)
                if (toggle.categories == null) SideEffect(dismiss)
            },
            dialog("dialog:browse.QuickLibraryToggleDialogs", "QuickLibraryToggleDialogs migrate") { dismiss ->
                val toggle = rememberQuickLibraryToggle()
                remember { toggle.migrating = f.bravo to f.alpha }
                QuickLibraryToggleDialogs(toggle)
                if (toggle.migrating == null) SideEffect(dismiss)
            },
            SmokeCheck(
                "state:ChoiceSetting",
                "dialog ChoiceSetting",
                { it.push(SmokeHost("choice") { ChoiceSetting("Smoke choice", 1, listOf(1 to "One", 2 to "Two")) { noop() } }) },
                drive = { onAllNodesWithText("Smoke choice").onFirst().performClick() },
            ),
            SmokeCheck(
                "state:MultiSelectPreference",
                "dialog MultiSelectPreference",
                {
                    it.push(
                        SmokeHost("multi") {
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
                SmokeCheck(
                    "state:first-run",
                    "dialog first-run setup",
                    {
                        it.push(
                            SmokeHost("first-run") {
                                val preferences = remember { Injekt.get<DesktopPreferences>().also { it.shownOnboardingFlow().set(false) } }
                                FirstRun(preferences) { null }
                            },
                        )
                    },
                    // Not set back, the app would show the first-run setup over every later check
                    cleanup = { shown.set(before) },
                )
            },
            SmokeCheck(
                "state:onboarding",
                "onboarding reopened",
                { Onboarding.reopen.value = true },
                cleanup = { Onboarding.reopen.value = false },
            ),
        )
    }

    /** Dialogs private to a screen, opened through the UI. */
    fun uiDialogs(): List<SmokeCheck> = listOf(
        SmokeCheck(
            "state:app-lock",
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
        // An AlertDialog inline in SettingsTab, so its key is a hand-made state: one
        SmokeCheck(
            "state:download-password",
            "dialog download password",
            {
                openSettingsCategory(SettingsCategory.Downloads)
                HomeScreen.openSettings(it)
            },
            drive = { clickText("Set the download password") },
        ),
        SmokeCheck(
            "dialog:category.CategoryNameDialog",
            "dialog new category",
            { it.push(zoukan.desktop.ui.category.CategoryScreen) },
            drive = {
                clickTag("category-add")
                waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag("category-name")).fetchSemanticsNodes().isNotEmpty() }
            },
        ),
        SmokeCheck(
            "dialog:library.AddTagDialog",
            "dialog new sort tag",
            { it.push(zoukan.desktop.ui.library.SortTagScreen) },
            drive = { clickDescription("Add a tag") },
        ),
        SmokeCheck(
            "dialog:library.LibrarySettingsDialog",
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
}

/** Lets screen models load on their dispatchers and the UI catch up. */
internal fun ComposeUiTest.settle() {
    waitForIdle()
    repeat(3) {
        Thread.sleep(150)
        waitForIdle()
    }
}

/**
 * One field searches every source; the in-tab fields only filter the list. So the page shows the
 * global search's placeholder once, and the filter under [tag] does not start with the same word.
 * A page whose filter is not on screen (the Sources filter shows only when there are sources) is skipped.
 */
internal fun ComposeUiTest.assertOneGlobalSearch(tag: String) {
    if (onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) return
    val global = localized(DMR.strings.search_all_sources)
    onAllNodes(hasText(global), useUnmergedTree = true).fetchSemanticsNodes().size shouldBe 1
    val verb = global.substringBefore(' ')
    onAllNodes(hasAnyAncestor(hasTestTag(tag)) and hasText(verb, substring = true, ignoreCase = true), useUnmergedTree = true)
        .fetchSemanticsNodes().shouldBeEmpty()
}

/**
 * Scrolls every lazy list on screen to its end, so each of its rows composes. A lazy list
 * doesn't report its length (its CollectionInfo has -1 rows), so this steps through it until
 * Compose's bounds check says an index is past the end, and then goes to the last row.
 */
internal fun ComposeUiTest.scrollLists() {
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

internal fun ComposeUiTest.clickText(text: String, substring: Boolean = false) {
    waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty() }
    onAllNodesWithText(text, substring = substring).onFirst().performClick()
    settle()
}

internal fun ComposeUiTest.clickTag(tag: String) {
    waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
    onAllNodes(hasTestTag(tag)).onFirst().performClick()
    settle()
}

internal fun ComposeUiTest.clickDescription(description: String) {
    val matcher = SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(description))
    waitUntil(timeoutMillis = 10_000) { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
    onAllNodes(matcher).onFirst().performClick()
    settle()
}
