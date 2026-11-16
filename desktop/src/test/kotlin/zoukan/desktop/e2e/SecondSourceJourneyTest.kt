package zoukan.desktop.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import eu.kanade.tachiyomi.source.model.FilterList
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.interactor.GetFavorites
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.exh.BuiltInSources
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.e2eEffectContext
import kotlin.time.Duration.Companion.minutes

/**
 * A second fixture source over the same server ([Journey.addSource]): both are listed in Browse,
 * and the second one searches and adds an entry with its chapters fetched over HTTP, as its own
 * source with its own id. The kit that merge and migration journeys build on.
 *
 *   ./gradlew :desktop:journeyTest --tests "*SecondSourceJourneyTest"
 */
@OptIn(ExperimentalTestApi::class)
class SecondSourceJourneyTest {

    private lateinit var journey: Journey

    @AfterEach
    fun close() {
        if (::journey.isInitialized) journey.close()
    }

    @Test
    fun aSecondFixtureSourceIsItsOwnSource() {
        journey = Journey.start("second-source")
        val mirror = journey.addSource()
        mirror.id shouldNotBe journey.source.id
        journey.step("A name already installed is refused, the first source's or the mirror's") {
            shouldThrow<IllegalArgumentException> { journey.addSource(FixtureSource.NAME) }
            shouldThrow<IllegalArgumentException> { journey.addSource() }.message shouldContain FixtureSource.SECOND_NAME
            Injekt.get<BuiltInSources>().testSources.value shouldContainExactly listOf(journey.source, mirror)
        }
        val preferences = Injekt.get<DesktopPreferences>()

        with(journey) {
            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
                setContent { ZoukanContent(preferences) }

                step("Browse > Sources lists both fixture sources") {
                    onAllNodesWithText("Browse").onFirst().performClick()
                    onAllNodesWithText("Sources").onFirst().performClick()
                    waitFor(hasText(FixtureSource.NAME))
                    waitFor(hasText(FixtureSource.SECOND_NAME))
                    shot("1-sources")
                }

                step("${FixtureSource.SECOND_NAME} shows the catalogue") {
                    onAllNodesWithText(FixtureSource.SECOND_NAME).onFirst().performScrollTo().performClick()
                    waitOrDump("for the catalogue") { onAllNodesWithTag("manga-item").fetchSemanticsNodes().size == 3 }
                    shot("2-mirror")
                }
            }
        }

        journey.step("${FixtureSource.SECOND_NAME} searches") {
            val found = runBlocking { mirror.getSearchManga(1, FixtureCatalog.FORMATS_TITLE, FilterList()) }
            found.mangas.map { it.title } shouldContainExactly listOf(FixtureCatalog.FORMATS_TITLE)
        }

        val fromDefault = journey.addToLibrary(FixtureCatalog.FORMATS_TITLE)
        val fetchedBefore = journey.server.requestLog.count { it.path == "/manga/formats" }
        val fromMirror = journey.step("Add ${FixtureCatalog.FORMATS_TITLE} from ${FixtureSource.SECOND_NAME}") {
            journey.addToLibrary(FixtureCatalog.FORMATS_TITLE, from = mirror)
        }

        // Its own entry, from its own source, with the chapters fetched from the server
        fromDefault.source shouldBe journey.source.id
        fromMirror.source shouldBe mirror.id
        fromMirror.id shouldNotBe fromDefault.id
        journey.server.requestLog.count { it.path == "/manga/formats" } shouldBeGreaterThan fetchedBefore
        runBlocking {
            Injekt.get<GetChaptersByMangaId>().await(fromMirror.id) shouldHaveSize FixtureCatalog.formats.size
            Injekt.get<GetChaptersByMangaId>().await(fromDefault.id) shouldHaveSize FixtureCatalog.formats.size
            Injekt.get<GetFavorites>().await().map { it.source } shouldContainExactlyInAnyOrder listOf(journey.source.id, mirror.id)
        }
    }
}
