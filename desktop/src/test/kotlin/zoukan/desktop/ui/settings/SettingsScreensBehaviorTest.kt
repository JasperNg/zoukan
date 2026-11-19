package zoukan.desktop.ui.settings

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import exh.source.ExhPreferences
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.data.AndroidDatabaseHandler
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.model.GroupLibraryMode
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.get
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.app.AppVersion
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.app.FileRequest
import zoukan.desktop.app.FitModes
import zoukan.desktop.app.ReadingModes
import zoukan.desktop.app.SystemEffects
import zoukan.desktop.app.Toasts
import zoukan.desktop.compat.localized
import zoukan.desktop.db.DatabaseThread
import zoukan.desktop.db.DesktopDatabase
import zoukan.desktop.db.DesktopSqliteDriver
import zoukan.desktop.discord.DiscordPresence
import zoukan.desktop.e2e.FixtureReleases
import zoukan.desktop.e2e.FixtureServer
import zoukan.desktop.exh.EhLogin
import zoukan.desktop.secrets.SecretStore
import zoukan.desktop.security.AppLock
import zoukan.desktop.security.Incognito
import zoukan.desktop.testing.Await
import zoukan.desktop.testing.FreshInjekt
import zoukan.desktop.testing.fakes.MemorySecretStore
import zoukan.desktop.ui.common.LazyColumn
import zoukan.desktop.ui.onboarding.Onboarding
import zoukan.desktop.ui.reader.ReaderNavigation
import zoukan.desktop.ui.whatsnew.WhatsNewScreen
import zoukan.desktop.update.UpdateChecker
import zoukan.desktop.update.Updates
import zoukan.extension.DesktopExtensionManager
import zoukan.i18n.desktop.DMR
import java.awt.Window
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Isolated Compose UI and database-level coverage for the settings pages pushed down from the
 * settings journeys: Appearance custom color picker, Library dialogs, Reader settings rows,
 * Security incognito toggle, E-Hentai language/category dialogs, Connections (Discord) hidden
 * categories, Advanced user-agent validation + HTTP header + onboarding guide reopen,
 * Clear database SQL deletion, and What's new external link filtering.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsScreensBehaviorTest {
    @JvmField
    @RegisterExtension
    val injekt = FreshInjekt()

    @TempDir
    lateinit var temp: File

    private lateinit var store: InMemoryPreferenceStore
    private lateinit var desktopPrefs: DesktopPreferences
    private lateinit var uiPrefs: UiPreferences
    private lateinit var libraryPrefs: LibraryPreferences
    private lateinit var networkPrefs: NetworkPreferences
    private lateinit var sourcePrefs: SourcePreferences
    private lateinit var exhPrefs: ExhPreferences

    private object Blank : Screen {
        @Composable
        override fun Content() = Unit
    }

    @BeforeEach
    fun setUp() {
        store = InMemoryPreferenceStore()
        desktopPrefs = DesktopPreferences(store)
        uiPrefs = UiPreferences(store)
        libraryPrefs = LibraryPreferences(store)
        networkPrefs = NetworkPreferences(store)
        sourcePrefs = SourcePreferences(store)
        exhPrefs = ExhPreferences(store)

        Injekt.addSingleton<PreferenceStore>(store)
        Injekt.addSingleton(desktopPrefs)
        Injekt.addSingleton(uiPrefs)
        Injekt.addSingleton(libraryPrefs)
        Injekt.addSingleton(networkPrefs)
        Injekt.addSingleton(sourcePrefs)
        Injekt.addSingleton(exhPrefs)
        Injekt.addSingleton(DownloadPreferences(store))
        Injekt.addSingleton(SecurityPreferences(store))
        Injekt.addSingleton(ZoukanDirs(temp))
        Injekt.addSingleton<SecretStore>(MemorySecretStore())
        Injekt.addSingleton(mockk<Application>(relaxed = true))
        Injekt.addSingleton(mockk<SourceManager>(relaxed = true))
        Injekt.addSingleton(
            mockk<DesktopExtensionManager>(relaxed = true) {
                every { installedExtensionsFlow } returns MutableStateFlow(emptyMap())
            },
        )
    }

    @AfterEach
    fun tearDown() {
        Onboarding.reopen.value = false
        SystemEffects.current = SystemEffects.Native
        Updates.checkerForTests = null
    }

    @Test
    fun `appearance custom color palette updates colorTheme within rounding`() {
        uiPrefs.appTheme().set(AppTheme.CUSTOM)
        runDesktopComposeUiTest {
            setContent { ThemeSection() }
            onNodeWithText(localized(DMR.strings.custom_color)).performClick()
            waitForIdle()
            onNodeWithText(localized(DMR.strings.hex)).assertExists()
            onNodeWithTag("theme-palette-FF3E9CBF").performClick()
            onNodeWithText(localized(KMR.strings.action_confirm_color)).performClick()
            waitForIdle()

            val picked = uiPrefs.colorTheme().get()
            listOf(16, 8, 0).all { shift -> abs((picked shr shift and 0xFF) - (0x3E9CBF shr shift and 0xFF)) <= 1 } shouldBe true
        }
    }

    @Test
    fun `library dynamic group updates, default category, and smart update dialog`() {
        runDesktopComposeUiTest {
            setContent {
                Navigator(Blank) {
                    LazyColumn(Modifier.testTag("library-settings")) {
                        librarySettings(emptyList())
                    }
                }
            }
            val list = onNodeWithTag("library-settings")

            list.performScrollToNode(hasText(localized(SYMR.strings.library_group_updates)))
            onNodeWithText(localized(SYMR.strings.library_group_updates)).performClick()
            onNodeWithText(localized(SYMR.strings.library_group_updates_all)).performClick()
            waitForIdle()
            libraryPrefs.groupLibraryUpdateType().get() shouldBe GroupLibraryMode.ALL

            list.performScrollToNode(hasText(localized(MR.strings.default_category)))
            onNodeWithText(localized(MR.strings.default_category)).performClick()
            onAllNodesWithText(localized(MR.strings.default_category_summary)).onFirst().performClick()
            waitForIdle()
            libraryPrefs.defaultCategory().get() shouldBe -1

            list.performScrollToNode(hasText(localized(MR.strings.pref_library_update_smart_update)))
            onNodeWithText(localized(MR.strings.pref_library_update_smart_update)).performClick()
            waitForIdle()
            onNodeWithText(localized(MR.strings.pref_update_only_in_release_period)).assertExists()
            onNodeWithText(localized(MR.strings.action_cancel)).performClick()
            waitForIdle()
            onNodeWithText(localized(MR.strings.pref_update_only_in_release_period)).assertDoesNotExist()
        }
    }

    @Test
    fun `reader settings rows write expected preferences`() {
        runDesktopComposeUiTest {
            setContent {
                LazyColumn(Modifier.testTag("reader-settings")) {
                    readerSettings(desktopPrefs)
                }
            }
            val list = onNodeWithTag("reader-settings")

            fun choose(row: String, option: String) {
                list.performScrollToNode(hasText(row))
                onNodeWithText(row).performClick()
                waitForIdle()
                onNodeWithText(option).performClick()
                waitForIdle()
            }

            choose(localized(MR.strings.pref_viewer_type), ReadingModes.label(ReadingModes.WEBTOON))
            desktopPrefs.defaultReadingMode().get() shouldBe ReadingModes.WEBTOON

            list.performScrollToNode(hasText(localized(MR.strings.pref_show_reading_mode)))
            onNodeWithText(localized(MR.strings.pref_show_reading_mode)).performClick()
            desktopPrefs.showReadingMode().get() shouldBe false

            choose(localized(DMR.strings.page_scaling_paged_modes), FitModes.label(FitModes.ORIGINAL))
            desktopPrefs.imageScaleType().get() shouldBe FitModes.ORIGINAL

            choose(localized(DMR.strings.background), localized(MR.strings.white_background))
            desktopPrefs.readerTheme().get() shouldBe 0

            choose(localized(MR.strings.pref_viewer_nav), localized(MR.strings.kindlish_nav))
            desktopPrefs.navigationModePager().get() shouldBe ReaderNavigation.KINDLISH

            list.performScrollToNode(hasText(localized(MR.strings.pref_keep_screen_on)))
            onNodeWithText(localized(MR.strings.pref_keep_screen_on)).performClick()
            desktopPrefs.keepScreenOn().get() shouldBe true

            list.performScrollToNode(hasText(localized(MR.strings.pref_page_transitions)))
            onNodeWithText(localized(MR.strings.pref_page_transitions)).performClick()
            desktopPrefs.pageTransitionsPager().get() shouldBe false

            choose(localized(SYMR.strings.page_layout), localized(SYMR.strings.single_page))
            desktopPrefs.pageLayout().get() shouldBe 0

            list.performScrollToNode(hasText(localized(MR.strings.pref_crop_borders)))
            onNodeWithText(localized(MR.strings.pref_crop_borders)).performClick()
            desktopPrefs.cropBorders().get() shouldBe true
        }
    }

    @Test
    fun `security incognito toggle updates Incognito mode`() {
        val incognito = Incognito(store, sourcePrefs, Injekt.get(), exhPrefs)
        Injekt.addSingleton(incognito)
        Injekt.addSingleton(AppLock(Injekt.get(), MemorySecretStore()))

        runDesktopComposeUiTest {
            setContent { LazyColumn(Modifier.testTag("security-settings")) { securitySettings() } }
            val list = onNodeWithTag("security-settings")
            list.performScrollToNode(hasText(localized(MR.strings.pref_incognito_mode)))
            incognito.mode().get() shouldBe false
            onNodeWithText(localized(MR.strings.pref_incognito_mode)).performClick()
            waitForIdle()
            incognito.mode().get() shouldBe true
            onNodeWithText(localized(MR.strings.pref_incognito_mode)).performClick()
            waitForIdle()
            incognito.mode().get() shouldBe false
        }
    }

    @Test
    fun `e-hentai language filtering and front page categories keep Android format`() {
        Injekt.addSingleton(mockk<EhLogin>(relaxed = true))
        exhPrefs.isHentaiEnabled().set(true)
        exhPrefs.enableExhentai().set(true)
        val defaultLanguages = exhPrefs.exhSettingsLanguages().get()

        runDesktopComposeUiTest {
            setContent { LazyColumn(Modifier.testTag("eh-settings")) { ehSettings() } }
            val list = onNodeWithTag("eh-settings")

            list.performScrollToNode(hasText(localized(DMR.strings.language_filtering)))
            onNodeWithText(localized(DMR.strings.language_filtering)).performClick()
            waitForIdle()

            // Japanese has 2 boxes (no original); English translated is index 3, Chinese rewrite is index 7
            val languageBoxesCount = 2 + 16 * 3
            val boxes = onAllNodes(isToggleable())
            val base = boxes.fetchSemanticsNodes().size - languageBoxesCount
            boxes[base + 3].performClick()
            boxes[base + 7].performClick()
            onNodeWithText(localized(MR.strings.action_ok)).performClick()
            waitForIdle()

            val stored = exhPrefs.exhSettingsLanguages().get()
            stored shouldBeNotEqualTo defaultLanguages
            val lines = stored.split("\n")
            lines.size shouldBe 17
            lines[1] shouldBe "false*true*false"
            lines[2] shouldBe "false*false*true"

            // Cancel preserves stored value and dialog opens with saved checkbox checked
            onNodeWithText(localized(DMR.strings.language_filtering)).performClick()
            waitForIdle()
            val reopenBoxes = onAllNodes(isToggleable())
            val reopenBase = reopenBoxes.fetchSemanticsNodes().size - languageBoxesCount
            reopenBoxes[reopenBase + 3].assertIsOn()
            reopenBoxes[reopenBase].performClick()
            onNodeWithText(localized(MR.strings.action_cancel)).performClick()
            waitForIdle()
            exhPrefs.exhSettingsLanguages().get() shouldBe stored

            // Front page categories: Doujinshi (0) and Misc (9) hidden
            list.performScrollToNode(hasText(localized(DMR.strings.front_page_categories)))
            onNodeWithText(localized(DMR.strings.front_page_categories)).performClick()
            waitForIdle()
            val catBoxes = onAllNodes(isToggleable())
            val catBase = catBoxes.fetchSemanticsNodes().size - 10
            catBoxes[catBase].performClick()
            catBoxes[catBase + 9].performClick()
            onNodeWithText(localized(MR.strings.action_ok)).performClick()
            waitForIdle()
            exhPrefs.exhEnabledCategories().get() shouldBe "true,false,false,false,false,false,false,false,false,true"
        }
    }

    @Test
    fun `discord connections hides options when off and stores hidden categories as ids`() {
        val uncategorized = Category(id = Category.UNCATEGORIZED_ID, name = "", order = 0L, flags = 0L, hidden = false)
        val shelf = Category(id = 42L, name = "Private Shelf", order = 1L, flags = 0L, hidden = false)
        val getCategories = mockk<GetCategories> {
            coEvery { await() } returns listOf(uncategorized, shelf)
            coEvery { await(any<Long>()) } returns emptyList()
        }
        val presence = DiscordPresence(store, getCategories, Incognito(store, sourcePrefs, Injekt.get(), exhPrefs))
        Injekt.addSingleton(getCategories)
        Injekt.addSingleton(presence)

        runDesktopComposeUiTest {
            setContent { LazyColumn(Modifier.testTag("discord-settings")) { discordSettings() } }
            onNodeWithText(localized(DMR.strings.hide_what_i_read)).assertDoesNotExist()

            presence.enabled().set(true)
            waitForIdle()
            onNodeWithText(localized(DMR.strings.hide_what_i_read)).assertExists()

            onNodeWithText(localized(DMR.strings.hide_these_categories)).performClick()
            waitForIdle()
            onNodeWithText("Private Shelf").assertExists()
            val boxes = onAllNodes(isToggleable())
            boxes[boxes.fetchSemanticsNodes().size - 1].performClick()
            onNodeWithText(localized(MR.strings.action_ok)).performClick()
            waitForIdle()
            presence.hiddenCategories().get() shouldBe setOf("42")
        }
    }

    @Test
    fun `advanced user agent validates, sends header over NetworkHelper, resets, and onboarding reopens`() {
        val context = mockk<Context> { every { cacheDir } returns temp }
        val networkHelper = NetworkHelper(context, networkPrefs)
        Injekt.addSingleton(networkHelper)
        Injekt.addSingleton(mockk<DatabaseHandler>(relaxed = true))
        Injekt.addSingleton(mockk<zoukan.desktop.download.DownloadManager>(relaxed = true))
        Injekt.addSingleton(mockk<zoukan.extension.ExtensionTrust>(relaxed = true))

        val server = FixtureServer()
        val toasts = mutableListOf<String>()
        val collector = CoroutineScope(Dispatchers.Unconfined).launch { Toasts.flow.collect { toasts += it } }
        try {
            fun sentUserAgent(): List<String> {
                val path = "/ua-${System.nanoTime()}"
                networkHelper.client.newCall(Request.Builder().url(server.baseUrl + path).build()).execute().close()
                return server.requestLog.last { it.path == path }.userAgents
            }

            val defaultUa = networkPrefs.defaultUserAgent().defaultValue()
            sentUserAgent() shouldBe listOf(defaultUa)

            runDesktopComposeUiTest {
                setContent {
                    Navigator(Blank) {
                        LazyColumn(Modifier.testTag("advanced-settings")) { advancedSettings() }
                    }
                }
                val list = onNodeWithTag("advanced-settings")

                fun editUa(value: String) {
                    list.performScrollToKey("advanced-user-agent")
                    onNodeWithText(localized(MR.strings.pref_user_agent_string)).performClick()
                    waitForIdle()
                    onNode(hasSetTextAction()).performTextReplacement(value)
                    onNodeWithText(localized(MR.strings.action_ok)).performClick()
                    waitForIdle()
                }

                editUa("ZoukanTest/1.0")
                networkPrefs.defaultUserAgent().get() shouldBe "ZoukanTest/1.0"
                Await.until("restart toast") { localized(MR.strings.requires_app_restart) in toasts }
                sentUserAgent() shouldBe listOf("ZoukanTest/1.0")

                // Invalid header value is rejected without closing dialog or overwriting stored UA
                editUa("Bad\u0007Agent")
                Await.until("invalid UA toast") { localized(MR.strings.error_user_agent_string_invalid) in toasts }
                networkPrefs.defaultUserAgent().get() shouldBe "ZoukanTest/1.0"
                onNodeWithText(localized(MR.strings.action_cancel)).performClick()
                waitForIdle()

                // Blank header value is allowed and sent as empty string
                editUa("")
                networkPrefs.defaultUserAgent().get() shouldBe ""
                sentUserAgent() shouldBe listOf("")

                // Reset restores default and unsets preference
                list.performScrollToKey("advanced-reset-user-agent")
                onNodeWithText(localized(MR.strings.pref_reset_user_agent_string)).performClick()
                waitForIdle()
                networkPrefs.defaultUserAgent().get() shouldBe defaultUa
                networkPrefs.defaultUserAgent().isSet() shouldBe false
                sentUserAgent() shouldBe listOf(defaultUa)

                // Onboarding guide reopens without changing shownOnboardingFlow
                val beforeOnboarding = desktopPrefs.shownOnboardingFlow().get()
                list.performScrollToKey("advanced-onboarding")
                onNodeWithText(localized(MR.strings.pref_onboarding_guide)).performClick()
                waitForIdle()
                Onboarding.reopen.value shouldBe true
                desktopPrefs.shownOnboardingFlow().get() shouldBe beforeOnboarding
            }
        } finally {
            collector.cancel()
            server.close()
        }
    }

    @Test
    fun `clear database deletes only non-library entries from real sqlite database`() {
        val dbThread = DatabaseThread()
        val driver = DesktopSqliteDriver.withSchema(
            url = "jdbc:sqlite:${File(temp, "clear_db_test.db").absolutePath}",
            properties = DesktopDatabase.properties(ByteArray(32) { it.toByte() }),
            databaseThread = dbThread,
            schema = Database.Schema,
        )
        val dbDispatcher = dbThread.executor.asCoroutineDispatcher()
        val db = DesktopDatabase.createDatabase(driver)
        val handler = AndroidDatabaseHandler(db, driver, dbDispatcher, dbDispatcher)
        val mainDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(mainDispatcher)
        try {
            val nonLibraryId = runBlocking {
                handler.awaitOneExecutable(true) {
                    mangasQueries.insert(
                        source = 1L,
                        url = "/delta",
                        artist = "",
                        author = "",
                        description = "",
                        genre = emptyList(),
                        title = "Delta",
                        status = 0L,
                        thumbnailUrl = "",
                        favorite = false,
                        lastUpdate = 0L,
                        nextUpdate = 0L,
                        calculateInterval = 0L,
                        initialized = true,
                        viewerFlags = 0L,
                        chapterFlags = 0L,
                        coverLastModified = 0L,
                        dateAdded = 0L,
                        updateStrategy = eu.kanade.tachiyomi.source.model.UpdateStrategy.ALWAYS_UPDATE,
                        version = 0L,
                        notes = "",
                        memo = JsonObject(emptyMap()),
                    )
                    mangasQueries.selectLastInsertedRowId()
                }
            }
            val libraryId = runBlocking {
                handler.awaitOneExecutable(true) {
                    mangasQueries.insert(
                        source = 1L,
                        url = "/alpha",
                        artist = "",
                        author = "",
                        description = "",
                        genre = emptyList(),
                        title = "Alpha",
                        status = 0L,
                        thumbnailUrl = "",
                        favorite = true,
                        lastUpdate = 0L,
                        nextUpdate = 0L,
                        calculateInterval = 0L,
                        initialized = true,
                        viewerFlags = 0L,
                        chapterFlags = 0L,
                        coverLastModified = 0L,
                        dateAdded = 0L,
                        updateStrategy = eu.kanade.tachiyomi.source.model.UpdateStrategy.ALWAYS_UPDATE,
                        version = 0L,
                        notes = "",
                        memo = JsonObject(emptyMap()),
                    )
                    mangasQueries.selectLastInsertedRowId()
                }
            }

            val model = ClearDatabaseScreenModel(
                handler = handler,
                sourceManager = mockk(relaxed = true),
                snapshot = zoukan.desktop.backup.SafetySnapshot(folder = { temp }, write = { it.writeText("snap") }),
                snapshotPrompt = { true },
            )
            try {
                Await.until("non-library source item loaded") { model.state.value.items?.isNotEmpty() == true }
                model.selectAll()
                model.delete(keepReadManga = true)
                Await.until("non-library manga deleted") {
                    runBlocking { handler.awaitOneOrNull { mangasQueries.getMangaById(nonLibraryId) } } == null
                }
                val kept = runBlocking { handler.awaitOneOrNull { mangasQueries.getMangaById(libraryId) } }
                kept?.favorite shouldBe true
                kept?.title shouldBe "Alpha"
            } finally {
                model.onDispose()
            }
        } finally {
            Dispatchers.resetMain()
            mainDispatcher.close()
            driver.close()
            dbThread.executor.shutdownNow()
        }
    }

    @Test
    fun `whats new screen opens only web links and release page through SystemEffects`() {
        val server = FixtureServer()
        val releases = FixtureReleases(server)
        val tag = AppVersion.TAG_PREFIX + AppVersion.version
        releases.publish(
            releases.release(
                tag,
                "Zoukan ${AppVersion.name}",
                "## Fixed\n\n- Reading **offline** works again\n\n[Read the full notes](https://example.com/zoukan/notes)\n\n[Not a web page](file:///etc/hosts)\n",
            ),
        )
        Updates.checkerForTests = UpdateChecker(releases.client(OkHttpClient()), Injekt.get())
        val recorder = RecordingBrowseEffects()
        SystemEffects.current = recorder
        try {
            runDesktopComposeUiTest {
                setContent { Navigator(WhatsNewScreen()) }
                Await.until("notes loaded") {
                    onAllNodesWithText("Reading offline works again").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithText("Not a web page").performClick()
                waitForIdle()
                recorder.browsed shouldBe emptyList()

                onNodeWithText("Read the full notes").performClick()
                Await.until("external web link opened") { recorder.browsed.size == 1 }
                onNodeWithText(localized(MR.strings.update_check_open)).performClick()
                Await.until("release page opened") { recorder.browsed.size == 2 }
                recorder.browsed.map { it.toString() } shouldBe listOf(
                    "https://example.com/zoukan/notes",
                    "https://github.com/${AppVersion.releaseRepo}/releases/tag/$tag",
                )
            }
        } finally {
            releases.close()
            server.close()
        }
    }

    private class RecordingBrowseEffects : SystemEffects {
        val browsed = ConcurrentLinkedQueue<URI>()

        override fun browse(uri: URI) {
            browsed += uri
        }

        override fun openFolder(folder: File) = Unit
        override fun pickFile(request: FileRequest): File? = null
        override fun pickFolder(window: Window?, title: String, start: File): File? = null
        override fun copy(contents: java.awt.datatransfer.Transferable) = Unit
        override fun installUpdate(file: File, quit: () -> Unit) = Unit
        override fun exit(code: Int) = Unit
    }
}

private infix fun String.shouldBeNotEqualTo(other: String) {
    (this != other) shouldBe true
}
