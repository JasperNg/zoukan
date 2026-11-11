package zoukan.desktop.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.domain.ui.model.ThemeMode
import eu.kanade.tachiyomi.source.CatalogueSource
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import mihon.domain.manga.model.toDomainManga
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.ZoukanRuntime
import zoukan.desktop.download.CbzWriter
import zoukan.desktop.library.LibraryUpdater
import zoukan.desktop.local.LocalSource
import zoukan.desktop.storage.ZoukanStorage
import zoukan.desktop.ui.common.DesktopPreferences
import zoukan.desktop.ui.reader.ReaderScreen
import zoukan.desktop.ui.settings.SettingsCategory
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.minutes

/**
 * The parity screens through the real UI, offline: the library's display modes, filter sheet and
 * selection actions, the reader's settings, color filters and double pages, the library update
 * errors screen, and the appearance, library and download settings. Screenshots go to
 * `build/reports/e2e/p*.png`.
 *
 *   ./gradlew :desktop:test -Pzoukan.e2e=true --tests "*EndToEndParityTest"
 */
@OptIn(ExperimentalTestApi::class)
class EndToEndParityTest {

    private val shots = File("build/reports/e2e").apply { mkdirs() }

    @Test
    fun libraryAndReaderParity() {
        assumeTrue(System.getProperty("zoukan.e2e") == "true", "End-to-end test; run with -Pzoukan.e2e=true")
        val dirs = ZoukanDirs(Files.createTempDirectory("zoukan-e2e-parity").toFile())
        ZoukanRuntime.start(dirs, interactiveBrowser = false)
        startUi(dirs)
        val preferences = Injekt.get<DesktopPreferences>()
        val library = Injekt.get<LibraryPreferences>()
        val storage = Injekt.get<ZoukanStorage>()
        var navigator: Navigator? = null

        // Three local entries in the library, with their chapters
        listOf("Alpha" to 3, "Bravo" to 2, "Charlie" to 4).forEach { (title, chapters) ->
            val dir = File(storage.local, title).apply { mkdirs() }
            (1..chapters).forEach { cbz(File(dir, "Chapter $it.cbz"), 4, title) }
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
        val favorites = runBlocking { Injekt.get<GetFavorites>().await() }
        favorites.size shouldBe 3

        runDesktopComposeUiTest(width = 1280, height = 820, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
            setContent { ZoukanContent(preferences) { navigator = it } }

            step("Library display modes") {
                waitUntil(timeoutMillis = 30_000) { onAllNodesWithTag("manga-item").fetchSemanticsNodes().size == 3 }
                library.downloadBadge().set(true)
                shot("p1-library-compact")
                library.displayMode().set(LibraryDisplayMode.ComfortableGrid)
                shot("p2-library-comfortable")
                library.displayMode().set(LibraryDisplayMode.List)
                library.showContinueReadingButton().set(true)
                shot("p3-library-list")
                library.displayMode().set(LibraryDisplayMode.CompactGrid)
            }

            step("Filter sheet and a filter") {
                byDescription("Filter, sort and display").performClick()
                waitFor(hasText("Downloaded"))
                shot("p4-filter-sheet")
                onAllNodesWithText("Sort").onFirst().performClick()
                waitFor(hasText("Total chapters"))
                shot("p5-sort-sheet")
                onAllNodesWithText("Close").onFirst().performClick()
                // Bravo is read in the next step; "unread only" then hides it
                library.filterUnread().set(TriState.ENABLED_IS)
            }

            step("Select an entry and mark it read") {
                library.filterUnread().set(TriState.DISABLED)
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("manga-item").fetchSemanticsNodes().size == 3 }
                onAllNodesWithText("Bravo").onFirst().performMouseInput { rightClick() }
                waitFor(hasText("1 selected"))
                shot("p6-selection")
                byDescription("Mark as read").performClick()
                val bravo = favorites.first { it.title == "Bravo" }
                waitUntil(timeoutMillis = 10_000) {
                    runBlocking { Injekt.get<GetChaptersByMangaId>().await(bravo.id).all { it.read } }
                }
                library.filterUnread().set(TriState.ENABLED_IS)
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("manga-item").fetchSemanticsNodes().size == 2 }
                shot("p7-filtered-unread")
                library.filterUnread().set(TriState.DISABLED)
            }

            step("Tag sorting and the Updates filters") {
                // Charlie has the sort tag, so a descending tag sort puts it first
                val charlie = favorites.first { it.title == "Charlie" }
                runBlocking {
                    Injekt.get<MangaRepository>().update(MangaUpdate(id = charlie.id, genre = listOf("female: sole female")))
                }
                Injekt.get<eu.kanade.domain.manga.interactor.CreateSortTag>().await("female: sole female")
                library.sortingMode().set(LibrarySort(LibrarySort.Type.TagList, LibrarySort.Direction.Descending))
                fun SemanticsNode.hasTextDeep(text: String): Boolean =
                    config.getOrNull(SemanticsProperties.Text).orEmpty().any { text in it.text } || children.any { it.hasTextDeep(text) }
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithTag("manga-item", useUnmergedTree = true).onFirst().fetchSemanticsNode().hasTextDeep("Charlie")
                }
                byDescription("Filter, sort and display").performClick()
                onAllNodesWithText("Sort").onFirst().performClick()
                waitFor(hasText("Tag sorting"))
                shot("d1-tag-sorting")
                onAllNodesWithText("Close").onFirst().performClick()
                library.sortingMode().set(LibrarySort.default)
                Injekt.get<eu.kanade.domain.manga.interactor.DeleteSortTag>().await("female: sole female")

                runOnUiThread { HomeScreen.selectTab(HomeTab.Updates) }
                byDescription("Filter").performClick()
                waitFor(hasText("Bookmarked"))
                shot("d2-updates-filter")
                onAllNodesWithText("Close").onFirst().performClick()
                runOnUiThread { HomeScreen.selectTab(HomeTab.Library) }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("manga-item").fetchSemanticsNodes().size == 3 }
            }

            step("Edit categories: add, rename, reorder, hide, default and delete") {
                val getCategories = Injekt.get<GetCategories>()
                fun categories() = runBlocking { getCategories.await() }.filterNot { it.isSystemCategory }
                byDescription("More").performClick()
                onAllNodesWithText("Edit categories").onFirst().performClick()
                waitFor(hasText("Default category"))
                listOf("Reading", "Later").forEach { name ->
                    onNodeWithTag("category-add").performClick()
                    waitFor(hasTestTag("category-name"))
                    onNodeWithTag("category-name").performTextInput(name)
                    onNodeWithTag("category-confirm").performClick()
                    waitUntil(timeoutMillis = 10_000) { categories().any { it.name == name } }
                }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("category-item").fetchSemanticsNodes().size == 2 }
                // Rename the second one
                byDescription("Rename category", 1).performClick()
                waitFor(hasTestTag("category-name"))
                shot("c1-category-rename")
                onNodeWithTag("category-name").performTextClearance()
                onNodeWithTag("category-name").performTextInput("Plan to read")
                onNodeWithTag("category-confirm").performClick()
                waitUntil(timeoutMillis = 10_000) { categories().map { it.name } == listOf("Reading", "Plan to read") }
                // Drag the first one below the second
                // The handle's own node: the row merges it into its click target
                onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Reorder")), useUnmergedTree = true)
                    .onFirst().performMouseInput {
                        moveTo(center)
                        press()
                        repeat(20) { moveBy(Offset(0f, 8f), delayMillis = 30) }
                        release()
                        // Touch input later in the test needs the mouse out of the window
                        exit()
                    }
                waitUntil(timeoutMillis = 10_000) { categories().map { it.name } == listOf("Plan to read", "Reading") }
                // Hide Reading, now second
                byDescription("Hide", 1).performClick()
                waitUntil(timeoutMillis = 10_000) { categories().first { it.name == "Reading" }.hidden }
                // New entries go to Plan to read
                onAllNodesWithText("Default category").onFirst().performClick()
                onAllNodesWithText("Plan to read").onLast().performClick()
                val planToRead = categories().first { it.name == "Plan to read" }
                waitUntil(timeoutMillis = 10_000) { library.defaultCategory().get().toLong() == planToRead.id }
                shot("c2-categories")
                // Deleting the default category resets it to Always ask
                byDescription("Delete", 0).performClick()
                waitFor(hasText("Delete category"))
                shot("c3-category-delete")
                onNodeWithTag("category-confirm").performClick()
                // Fails without DesktopDatabase.fixTriggers
                waitUntil(timeoutMillis = 10_000) { categories().map { it.name } == listOf("Reading") }
                // DeleteCategory resets the preference after the row is gone, so wait for it too
                waitUntil(timeoutMillis = 10_000) { library.defaultCategory().get() == -1 }
                // Leave no categories, so the later steps see the library as before
                byDescription("Delete", 0).performClick()
                waitFor(hasText("Delete category"))
                onNodeWithTag("category-confirm").performClick()
                waitUntil(timeoutMillis = 10_000) { categories().isEmpty() }
                runOnUiThread { navigator?.pop() }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("manga-item").fetchSemanticsNodes().size == 3 }
            }

            step("Reader: double pages, filters and settings") {
                val alpha = favorites.first { it.title == "Alpha" }
                val chapter = runBlocking { Injekt.get<GetChaptersByMangaId>().await(alpha.id) }.first { it.name.contains("1") }
                preferences.pageLayout().set(1)
                preferences.defaultReadingMode().set(zoukan.desktop.ui.common.ReadingModes.LEFT_TO_RIGHT)
                runOnUiThread { navigator?.push(ReaderScreen(alpha.id, chapter.id)) }
                waitUntil(timeoutMillis = 30_000) { onAllNodesWithTag("page-image").fetchSemanticsNodes().isNotEmpty() }
                // Pages 2 and 3 side by side
                onRoot().performKeyInput { pressKey(Key.DirectionRight) }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("page-image").fetchSemanticsNodes().size >= 2 }
                shot("p8-reader-double")
                // Changing the page layout keeps the reader on the same page
                waitFor(hasText("2-3 / 4"), 10_000)
                preferences.pageLayout().set(0)
                waitForIdle()
                waitFor(hasText("3 / 4"), 10_000)
                preferences.pageLayout().set(1)
                waitForIdle()
                waitFor(hasText("2-3 / 4"), 10_000)
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("page-image").fetchSemanticsNodes().size >= 2 }
                preferences.grayscale().set(true)
                preferences.colorFilter().set(true)
                preferences.colorFilterValue().set(0x40FF8000)
                shot("p9-reader-filters")
                onRoot().performKeyInput { pressKey(Key.S) }
                waitFor(hasText("Reading mode for this entry"))
                shot("p10-reader-settings")
                onAllNodesWithText("Filter").onFirst().performClick()
                waitFor(hasText("Inverted colors"))
                shot("p11-reader-filter-tab")
                onAllNodesWithText("Close").onFirst().performClick()
                preferences.grayscale().set(false)
                preferences.colorFilter().set(false)
            }

            step("Reader page actions: save the combined page and set a page as the cover") {
                val alpha = favorites.first { it.title == "Alpha" }
                val pictures = Files.createTempDirectory("zoukan-e2e-pictures").toFile()
                System.setProperty("zoukan.picturesDir", pictures.absolutePath)
                // A right-click on the double page offers the first, the second and the combined page
                onRoot().performMouseInput { rightClick(center) }
                waitFor(hasText("Save combined page"))
                shot("p12-reader-page-actions")
                onAllNodesWithText("Save combined page").onFirst().performClick()
                waitUntil(timeoutMillis = 15_000) { pictures.listFiles().orEmpty().any { it.name.endsWith(" - 2-3.jpg") } }
                val saved = ImageIO.read(pictures.listFiles()!!.first { it.name.endsWith(" - 2-3.jpg") })
                saved.width shouldBe 1200
                saved.height shouldBe 900
                val before = runBlocking { Injekt.get<GetManga>().await(alpha.id)!!.coverLastModified }
                onRoot().performMouseInput { rightClick(center) }
                waitFor(hasText("Set second page as cover"))
                onAllNodesWithText("Set second page as cover").onFirst().performClick()
                waitFor(hasText("Use this image as cover art?"))
                shot("p13-reader-set-cover")
                onAllNodesWithText("OK").onFirst().performClick()
                waitUntil(timeoutMillis = 15_000) {
                    runBlocking { Injekt.get<GetManga>().await(alpha.id)!!.coverLastModified } != before
                }
                File(storage.local, "Alpha").listFiles()!!.any { it.name == "cover.png" } shouldBe true
                System.clearProperty("zoukan.picturesDir")
                // A long-press after the layout changed acts on the pages shown now, not the old spread
                preferences.pageLayout().set(0)
                waitForIdle()
                waitFor(hasText("3 / 4"), 10_000)
                onRoot().performTouchInput { longClick(center) }
                waitFor(hasText("Page 3"))
                onAllNodesWithText("Pages 2 and 3").fetchSemanticsNodes().size shouldBe 0
                onAllNodesWithText("Close").onFirst().performClick()
                preferences.pageLayout().set(1)
            }

            step("Library update errors: a failed entry, then a retry that fixes it") {
                runOnUiThread { navigator?.popUntilRoot() }
                // Charlie's folder is gone, so its update fails
                val charlie = favorites.first { it.title == "Charlie" }
                val folder = File(storage.local, "Charlie")
                val moved = File(storage.local.parentFile, "Charlie-moved")
                folder.renameTo(moved) shouldBe true
                val failed = CountDownLatch(1)
                updater.start(mangaIds = setOf(charlie.id)) { failed.countDown() }
                failed.await(2, TimeUnit.MINUTES) shouldBe true
                updater.lastResult.value!!.failures.map { it.manga.id } shouldBe listOf(charlie.id)
                waitUntil(timeoutMillis = 10_000) { updater.progress.value == null }

                byDescription("More").performClick()
                onAllNodesWithText("Library update errors").onFirst().performClick()
                waitUntil(timeoutMillis = 30_000) { onAllNodesWithTag("update-error").fetchSemanticsNodes().size == 1 }
                waitFor(hasText("Library update errors (1)"))
                shot("p12-update-errors")

                // Right-click selects, as in the library
                onAllNodesWithTag("update-error").onFirst().performMouseInput { rightClick() }
                waitFor(hasText("1 selected"))
                shot("p13-update-errors-selected")
                byDescription("Clear selection").performClick()

                // The folder is back: retrying updates Charlie and its error goes
                moved.renameTo(folder) shouldBe true
                onAllNodes(
                    SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Retry")),
                ).onFirst().performClick()
                waitFor(hasText("You have no library update errors."), 120_000)
                shot("p14-update-errors-empty")
            }

            step("Settings: appearance, library and downloads") {
                val ui = Injekt.get<UiPreferences>()
                runOnUiThread {
                    navigator?.let { HomeScreen.openSettings(it) }
                }
                waitFor(hasText("App theme"))
                shot("p15-settings-appearance")
                // An Android color scheme, dark and pure black, with a date format
                ui.appTheme().set(AppTheme.NORD)
                ui.themeMode().set(ThemeMode.DARK)
                ui.themeDarkAmoled().set(true)
                ui.dateFormat().set("yyyy-MM-dd")
                waitForIdle()
                shot("p16-settings-nord-dark")
                ui.themeDarkAmoled().set(false)
                ui.appTheme().set(AppTheme.CUSTOM)
                waitFor(hasText("Custom color"))
                onAllNodesWithText("Custom color").onFirst().performClick()
                waitFor(hasText("Hex"))
                shot("p17-settings-custom-color")
                onAllNodesWithText("Cancel").onFirst().performClick()

                // Two-pane Settings: the page list on the left, the open page in "settings-page"
                var list = settingsPage(SettingsCategory.Library)
                list.performScrollToNode(hasText("Default category"))
                onAllNodesWithText("Default category").onFirst().performClick()
                waitFor(hasText("Always ask"))
                shot("p18-settings-default-category")
                onAllNodesWithText("Always ask").onFirst().performClick()

                list.performScrollToNode(hasText("Smart update"))
                onAllNodesWithText("Smart update").onFirst().performClick()
                waitFor(hasText("Predict next release time"))
                shot("p19-settings-smart-update")
                onAllNodesWithText("Cancel").onFirst().performClick()

                list = settingsPage(SettingsCategory.Downloads)
                list.performScrollToNode(hasText("After reading automatically delete"))
                shot("p20-settings-downloads")
                list.performScrollToNode(hasText("Excluded categories"))
                onAllNodesWithText("Excluded categories").onFirst().performClick()
                waitFor(hasText("OK"))
                shot("p21-settings-delete-excluded")
                onAllNodesWithText("Cancel").onFirst().performClick()

                // The navigation options hide History, as on Android
                ui.showNavHistory().set(false)
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("History").fetchSemanticsNodes().isEmpty() }
                ui.showNavHistory().set(true)
                ui.appTheme().set(AppTheme.DEFAULT)
                ui.themeMode().set(ThemeMode.SYSTEM)

                // Edit categories opens the same screen as Library > More, as on Android
                settingsPage(SettingsCategory.Library).performScrollToNode(hasText("Edit categories"))
                // The title, not the row's center: a toast can cover the middle of the bottom row
                onAllNodesWithText("Edit categories", useUnmergedTree = true).onFirst().performClick()
                waitFor(hasTestTag("category-add"))
                runOnUiThread { navigator?.pop() }
                waitFor(hasText("Edit categories"))
            }

            step("Settings: browse, data and storage, and advanced") {
                val sources = Injekt.get<eu.kanade.domain.source.service.SourcePreferences>()
                var list = settingsPage(SettingsCategory.Browse)
                list.performScrollToNode(hasText("Replace latest button"))
                shot("p22-settings-browse")
                // A source category with the local source in it, as Android stores them
                sources.sourcesTabCategories().set(setOf("Comics"))
                sources.sourcesTabSourcesInCategories().set(setOf("${LocalSource.ID}|Comics"))
                onAllNodesWithText("Edit categories").onFirst().performClick()
                waitFor(hasText("Comics"))
                shot("p23-source-categories")
                runOnUiThread { navigator?.pop() }

                list = settingsPage(SettingsCategory.Data)
                list.performScrollToNode(hasText("Clear chapter cache"))
                waitFor(hasText("Available: ", substring = true))
                shot("p24-settings-data")
                list.performScrollToNode(hasText("Library List"))
                onAllNodesWithText("Library List").onFirst().performClick()
                waitFor(hasText("Select data to include"))
                shot("p25-settings-export")
                onAllNodesWithText("Cancel").onFirst().performClick()

                list = settingsPage(SettingsCategory.Advanced)
                list.performScrollToNode(hasText("DNS over HTTPS (DoH)"))
                onAllNodesWithText("DNS over HTTPS (DoH)").onFirst().performClick()
                waitFor(hasText("Cloudflare"))
                shot("p26-settings-doh")
                onAllNodesWithText("Cancel").onFirst().performClick()

                // Clear database: an entry outside the library, then deleted
                runBlocking {
                    val delta = eu.kanade.tachiyomi.source.model.SManga.create().apply {
                        title = "Delta"
                        url = "Delta"
                    }
                    Injekt.get<NetworkToLocalManga>()(listOf(delta.toDomainManga(LocalSource.ID)))
                }
                list.performScrollToNode(hasText("Clear database"))
                onAllNodesWithText("Clear database").onFirst().performClick()
                waitFor(hasText("1 non-library entries in database"))
                shot("p27-clear-database")
                byDescription("Select all").performClick()
                byDescription("Delete").performClick()
                waitFor(hasText("Keep entries with read chapters"))
                onAllNodesWithText("OK").onFirst().performClick()
                waitFor(hasText("Nothing to clear"))
                runBlocking { Injekt.get<GetManga>().await("Delta", LocalSource.ID) } shouldBe null
                runOnUiThread { navigator?.pop() }
            }

            step("Browse: source categories, the source filter and the feed tab") {
                runOnUiThread {
                    navigator?.popUntilRoot()
                    HomeScreen.selectTab(HomeTab.Browse)
                }
                onAllNodesWithText("Sources").onFirst().performClick()
                waitFor(hasText("Comics"))
                shot("p28-browse-categories")
                byDescription("Filter sources").performClick()
                waitFor(hasText("Filter"))
                shot("p29-sources-filter")
                runOnUiThread { navigator?.pop() }
                // Coming back to Browse keeps the open tab
                onAllNodesWithText("Extensions", substring = true).onFirst().performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("extension-search").fetchSemanticsNodes().isNotEmpty() }
                runOnUiThread { navigator?.push(zoukan.desktop.ui.settings.ExtensionStoresScreen) }
                waitFor(hasText("Extension stores"))
                runOnUiThread { navigator?.pop() }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("extension-search").fetchSemanticsNodes().isNotEmpty() }
                onAllNodesWithText("Sources").onFirst().performClick()
                // Hiding the feed tab, as Android
                val ui = Injekt.get<UiPreferences>()
                waitFor(hasText("Feed"))
                ui.hideFeedTab().set(true)
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Feed").fetchSemanticsNodes().isEmpty() }
                ui.hideFeedTab().set(false)
                // "Replace latest button" (on by default) opens the source's feed
                onAllNodesWithText("Local source").onFirst().performClick()
                waitFor(hasText("This source's latest, popular and saved searches"))
                shot("p30-source-feed")
                runOnUiThread { navigator?.popUntilRoot() }
            }
        }
    }

    private fun ComposeUiTest.byDescription(text: String, index: Int = 0) =
        onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(text)))[index]

    private fun cbz(file: File, pages: Int, label: String) {
        CbzWriter(file).use { writer ->
            (1..pages).forEach { i ->
                val image = BufferedImage(600, 900, BufferedImage.TYPE_INT_RGB)
                val g = image.createGraphics()
                g.color = java.awt.Color.WHITE
                g.fillRect(0, 0, 600, 900)
                g.color = java.awt.Color(40 * i, 90, 160)
                g.fillRect(60, 60, 480, 780)
                g.color = java.awt.Color.WHITE
                g.font = g.font.deriveFont(64f)
                g.drawString("$label $i", 120, 480)
                g.dispose()
                writer.write("%03d.png".format(i), ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray())
            }
        }
    }

    /** Opens a page of the two-pane Settings from the list on the left; returns the page's list. */
    private fun ComposeUiTest.settingsPage(category: SettingsCategory): SemanticsNodeInteraction {
        onNodeWithTag("settings-list").performScrollToNode(hasText(category.subtitle))
        onAllNodesWithText(category.subtitle).onFirst().performClick()
        waitForIdle()
        return onNodeWithTag("settings-page")
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
        // A dialog is its own root: draw each root over the window at its place
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
