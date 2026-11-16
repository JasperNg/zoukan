package zoukan.desktop.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.e2eEffectContext
import zoukan.desktop.ui.manga.MangaScreen
import kotlin.time.Duration.Companion.minutes

/**
 * Reads the fixture title's AVIF chapter and checks each page drew. Sources such as Atsumaru serve
 * AVIF; it is its own journey so that the other formats' results stand apart from it.
 *
 *   ./gradlew :desktop:journeyTest --tests "*AvifJourneyTest"
 */
@OptIn(ExperimentalTestApi::class)
class AvifJourneyTest {

    private lateinit var journey: Journey

    @AfterEach
    fun close() {
        if (::journey.isInitialized) journey.close()
    }

    @Test
    fun readAvifChapter() {
        // The other journeys share one FFmpeg cache (journeyTest sets the cachedir); this one takes
        // the app's own path, where ZoukanRuntime unpacks FFmpeg into the data folder
        System.clearProperty(JAVACPP_CACHEDIR)
        journey = Journey.start("avif")
        System.getProperty(JAVACPP_CACHEDIR) shouldBe journey.dirs.javacppCache.absolutePath
        val preferences = Injekt.get<DesktopPreferences>()
        var navigator: Navigator? = null
        val manga = journey.addToLibrary(FixtureCatalog.FORMATS_TITLE)

        with(journey) {
            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 5.minutes) {
                setContent { ZoukanContent(preferences) { navigator = it } }
                waitForIdle()

                step("Read chapter 4 (AVIF)") {
                    runOnUiThread { navigator?.push(MangaScreen(manga.id)) }
                    waitFor(hasText("Chapter 4 (AVIF)"))
                    onAllNodesWithText("Chapter 4 (AVIF)").onFirst().performClick()
                    readThrough(FixtureCatalog.pages("avif"), "avif")
                }

                step("FFmpeg unpacked into the data folder") {
                    journey.dirs.javacppCache.walkTopDown().any { it.isFile }.shouldBeTrue()
                }
            }
        }
    }

    private companion object {
        const val JAVACPP_CACHEDIR = "org.bytedeco.javacpp.cachedir"
    }
}
