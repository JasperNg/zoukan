package zoukan.desktop.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.compat.localized
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.common.DesktopPreferences
import zoukan.desktop.ui.e2eEffectContext
import zoukan.desktop.ui.manga.MangaScreen
import kotlin.time.Duration.Companion.minutes

/**
 * More > Downloaded only: with one of two library titles downloaded, the library shows only that
 * one, and turning the switch off shows both again.
 *
 *   ./gradlew :desktop:journeyTest --tests "*DownloadedOnlyJourneyTest"
 */
@OptIn(ExperimentalTestApi::class)
class DownloadedOnlyJourneyTest {

    private lateinit var journey: Journey

    @AfterEach
    fun close() {
        if (::journey.isInitialized) journey.close()
    }

    @Test
    fun downloadedOnlyShowsOnlyDownloadedTitles() {
        journey = Journey.start("downloaded-only")
        val preferences = Injekt.get<DesktopPreferences>()
        val downloads = Injekt.get<DownloadManager>()
        var navigator: Navigator? = null
        val formats = journey.addToLibrary(FixtureCatalog.FORMATS_TITLE)
        journey.addToLibrary(SECOND_TITLE)
        val label = localized(MR.strings.label_downloaded_only)
        val libraryTab = localized(MR.strings.label_library)

        with(journey) {
            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
                setContent { ZoukanContent(preferences) { navigator = it } }
                fun items() = onAllNodesWithTag("manga-item").fetchSemanticsNodes().size
                fun labelNodes() = onAllNodesWithText(label).fetchSemanticsNodes().size

                step("The library lists both titles") {
                    waitOrDump("for two library items") { items() == 2 }
                    shot("1-library")
                }

                step("Download one chapter of ${FixtureCatalog.FORMATS_TITLE}") {
                    runOnUiThread { navigator?.push(MangaScreen(formats.id)) }
                    waitFor(hasText("Start") and isEnabled())
                    onAllNodes(hasContentDescription("Download")).onFirst().performClick()
                    waitFor(hasText("Next chapter"))
                    onAllNodesWithText("Next chapter").onFirst().performClick()
                    waitOrDump("for the download to finish", timeout = 120_000) {
                        val chapters = runBlocking { Injekt.get<GetChaptersByMangaId>().await(formats.id) }
                        downloads.queue.value.isEmpty() && !downloads.running.value && downloads.downloadedIds(formats, chapters).size == 1
                    }
                    runOnUiThread { navigator?.pop() }
                }

                step("Downloaded only on: the banner shows and only the downloaded title is left") {
                    onAllNodesWithText(localized(MR.strings.label_more)).onFirst().performClick()
                    // Before the switch the words are the switch's own title only
                    waitOrDump("for the More tab") { labelNodes() == 1 }
                    onAllNodesWithText(label).onFirst().performClick()
                    waitOrDump("for the banner") { labelNodes() == 2 }
                    preferences.downloadedOnly().get() shouldBe true
                    onAllNodesWithText(libraryTab).onFirst().performClick()
                    waitOrDump("for the library to narrow") { items() == 1 }
                    waitFor(hasText(FixtureCatalog.FORMATS_TITLE))
                    onAllNodesWithText(SECOND_TITLE).fetchSemanticsNodes().size shouldBe 0
                    shot("2-downloaded-only")
                }

                step("Downloaded only off: both titles again") {
                    onAllNodesWithText(localized(MR.strings.label_more)).onFirst().performClick()
                    waitOrDump("for the banner") { labelNodes() == 2 }
                    // The banner above is the first match, the switch's own title the last
                    onAllNodesWithText(label).onLast().performClick()
                    waitOrDump("for the banner to go") { labelNodes() == 1 }
                    preferences.downloadedOnly().get() shouldBe false
                    onAllNodesWithText(libraryTab).onFirst().performClick()
                    waitOrDump("for both titles") { items() == 2 }
                    shot("3-both")
                }
            }
        }
    }

    private companion object {
        const val SECOND_TITLE = "Fixture Second"
    }
}
