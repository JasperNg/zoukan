package zoukan.desktop.e2e

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.library.service.LibraryPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.storage.ZoukanStorage
import zoukan.desktop.ui.HomeScreen
import zoukan.desktop.ui.HomeTab
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.common.DesktopPreferences
import zoukan.desktop.ui.e2eEffectContext
import zoukan.desktop.ui.settings.SettingsCategory
import zoukan.desktop.ui.settingsPage
import kotlin.time.Duration.Companion.minutes

/**
 * Categories through the real UI, offline: Edit categories from Library > More (add, rename,
 * drag to reorder, hide, set the default, delete), putting an entry in a category from the
 * library selection, and the same screen from Settings > Library.
 *
 *   ./gradlew :desktop:journeyTest --tests "*CategoriesJourneyTest"
 */
@OptIn(ExperimentalTestApi::class)
class CategoriesJourneyTest {

    private lateinit var journey: Journey

    @AfterEach
    fun close() {
        if (::journey.isInitialized) journey.close()
    }

    @Test
    fun editAssignAndDeleteCategories() {
        journey = Journey.start("categories")
        val preferences = Injekt.get<DesktopPreferences>()
        val library = Injekt.get<LibraryPreferences>()
        val favorites = LocalFixtures.seedParityLibrary(Injekt.get<ZoukanStorage>())
        var navigator: Navigator? = null
        val getCategories = Injekt.get<GetCategories>()
        fun categories() = runBlocking { getCategories.await() }.filterNot { it.isSystemCategory }

        with(journey) {
            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
                setContent { ZoukanContent(preferences) { navigator = it } }
                runOnUiThread { HomeScreen.selectTab(HomeTab.Library) }
                fun items() = onAllNodesWithTag("manga-item").fetchSemanticsNodes().size
                waitOrDump("for the 3 entries", 30_000) { items() == 3 }

                step("Edit categories: add, rename, reorder, hide, default and delete") {
                    byDescription("More").performClick()
                    onAllNodesWithText("Edit categories").onFirst().performClick()
                    waitFor(hasText("Default category"))
                    listOf("Reading", "Later").forEach { name ->
                        onNodeWithTag("category-add").performClick()
                        waitFor(hasTestTag("category-name"))
                        onNodeWithTag("category-name").performTextInput(name)
                        onNodeWithTag("category-confirm").performClick()
                        waitOrDump("for $name to be added", 10_000) { categories().any { it.name == name } }
                    }
                    waitOrDump("for both rows", 10_000) { onAllNodesWithTag("category-item").fetchSemanticsNodes().size == 2 }
                    // Rename the second one
                    byDescription("Rename category", 1).performClick()
                    waitFor(hasTestTag("category-name"))
                    shotWithPopups("rename")
                    onNodeWithTag("category-name").performTextClearance()
                    onNodeWithTag("category-name").performTextInput("Plan to read")
                    onNodeWithTag("category-confirm").performClick()
                    waitOrDump("for the rename", 10_000) { categories().map { it.name } == listOf("Reading", "Plan to read") }
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
                    waitOrDump("for the reorder", 10_000) { categories().map { it.name } == listOf("Plan to read", "Reading") }
                    // Hide Reading, now second
                    byDescription("Hide", 1).performClick()
                    waitOrDump("for Reading to hide", 10_000) { categories().first { it.name == "Reading" }.hidden }
                    // New entries go to Plan to read
                    onAllNodesWithText("Default category").onFirst().performClick()
                    onAllNodesWithText("Plan to read").onLast().performClick()
                    val planToRead = categories().first { it.name == "Plan to read" }
                    waitOrDump("for the default category", 10_000) { library.defaultCategory().get().toLong() == planToRead.id }
                    shot("categories")
                    // Deleting the default category resets it to Always ask
                    byDescription("Delete", 0).performClick()
                    waitFor(hasText("Delete category"))
                    shotWithPopups("delete")
                    onNodeWithTag("category-confirm").performClick()
                    // Fails without DesktopDatabase.fixTriggers
                    waitOrDump("for the delete", 10_000) { categories().map { it.name } == listOf("Reading") }
                    // DeleteCategory resets the preference after the row is gone, so wait for it too
                    waitOrDump("for the default to reset", 10_000) { library.defaultCategory().get() == -1 }
                    // Leave no categories, so the next step sees the library as before
                    byDescription("Delete", 0).performClick()
                    waitFor(hasText("Delete category"))
                    onNodeWithTag("category-confirm").performClick()
                    waitOrDump("for no categories", 10_000) { categories().isEmpty() }
                    runOnUiThread { navigator?.pop() }
                    waitOrDump("for the library", 10_000) { items() == 3 }
                }

                step("Change categories: an entry joins a category and its tab lists it") {
                    // The category exists before the entry joins it
                    runBlocking { Injekt.get<tachiyomi.domain.category.interactor.CreateCategoryWithName>().await("Shelf") }
                    waitOrDump("for the category", 10_000) { categories().map { it.name } == listOf("Shelf") }
                    val shelf = categories().single()
                    onAllNodesWithText("Alpha").onFirst().performMouseInput { rightClick() }
                    waitFor(hasText("Select"))
                    onAllNodesWithText("Select").onFirst().performClick()
                    waitFor(hasText("1 selected"))
                    byDescription("Change categories").performClick()
                    waitFor(hasText("Shelf"))
                    shotWithPopups("change-categories")
                    onAllNodesWithText("Shelf").onLast().performClick()
                    onAllNodesWithText("OK").onFirst().performClick()
                    val alpha = favorites.first { it.title == "Alpha" }
                    waitOrDump("for Alpha to join Shelf", 10_000) {
                        runBlocking { getCategories.await(alpha.id) }.map { it.id } == listOf(shelf.id)
                    }
                    // The Shelf tab lists Alpha alone
                    waitFor(hasText("Shelf"))
                    onAllNodesWithText("Shelf").onFirst().performClick()
                    waitOrDump("for the Shelf tab to list Alpha alone", 10_000) { items() == 1 }
                    shot("shelf-tab")
                }

                step("Edit categories opens from Settings > Library") {
                    runOnUiThread { navigator?.let { HomeScreen.openSettings(it) } }
                    settingsPage(SettingsCategory.Library).performScrollToNode(hasText("Edit categories"))
                    // The title, not the row's center: a toast can cover the middle of the bottom row
                    onAllNodesWithText("Edit categories", useUnmergedTree = true).onFirst().performClick()
                    waitFor(hasTestTag("category-add"))
                    // The row shows Shelf, as the library's own screen does
                    waitFor(hasText("Shelf"))
                    runOnUiThread { navigator?.pop() }
                    waitFor(hasText("Edit categories"))
                    categories().size shouldBe 1
                }
            }
        }
    }
}
