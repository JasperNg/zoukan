package zoukan.desktop.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
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
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.e2eEffectContext
import zoukan.desktop.ui.reader.ReaderScreen
import zoukan.i18n.desktop.DMR
import kotlin.time.Duration.Companion.minutes

/**
 * A page the source refuses shows a readable reason under "Page n could not load", not the raw
 * status line.
 *
 *   ./gradlew :desktop:journeyTest --tests "*ReaderErrorMessageJourneyTest"
 */
@OptIn(ExperimentalTestApi::class)
class ReaderErrorMessageJourneyTest {

    private lateinit var journey: Journey

    @AfterEach
    fun close() {
        if (::journey.isInitialized) journey.close()
    }

    @Test
    fun `a page the source refuses shows a readable reason`() {
        journey = Journey.start("reader-error")
        val preferences = Injekt.get<DesktopPreferences>()
        preferences.readerInstantRetry().set(false)
        var navigator: Navigator? = null
        val manga = journey.addToLibrary(FixtureCatalog.FORMATS_TITLE)
        val chapter1 = runBlocking { Injekt.get<GetChaptersByMangaId>().await(manga.id) }.first { it.name == "Chapter 1 (JPEG)" }
        val refused = FixtureCatalog.pages("jpeg").first().file
        journey.server.extraRoutes += { exchange, parts ->
            if (parts.lastOrNull() == refused) {
                exchange.sendResponseHeaders(403, -1)
                true
            } else {
                false
            }
        }
        with(journey) {
            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
                setContent { ZoukanContent(preferences) { navigator = it } }
                waitForIdle()
                runOnUiThread { navigator?.push(ReaderScreen(manga.id, chapter1.id)) }
                waitOrDump("for the page error", 30_000) {
                    onAllNodes(hasText(localized(DMR.strings.page_could_not_load, 1))).fetchSemanticsNodes().isNotEmpty()
                }
                onAllNodes(hasText(localized(MR.strings.exception_http, 403))).fetchSemanticsNodes().isNotEmpty() shouldBe true
                onAllNodes(hasText("HTTP 403")).fetchSemanticsNodes().isEmpty() shouldBe true
            }
        }
    }
}
