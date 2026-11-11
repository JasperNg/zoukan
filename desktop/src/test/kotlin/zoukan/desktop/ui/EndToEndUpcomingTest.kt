package zoukan.desktop.ui

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.ZoukanRuntime
import zoukan.desktop.local.LocalSource
import zoukan.desktop.ui.common.DesktopPreferences
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.minutes

/**
 * Updates > Upcoming through the real UI, offline: three library entries with expected release
 * dates, the calendar and list, the next month, and the "To be updated" list. Screenshots go to
 * `build/reports/e2e/u*.png`.
 *
 *   ./gradlew :desktop:test -Pzoukan.e2e=true --tests "*EndToEndUpcomingTest"
 */
@OptIn(ExperimentalTestApi::class)
class EndToEndUpcomingTest {

    private val shots = File("build/reports/e2e").apply { mkdirs() }

    @Test
    fun upcoming() {
        assumeTrue(System.getProperty("zoukan.e2e") == "true", "End-to-end test; run with -Pzoukan.e2e=true")
        val dirs = ZoukanDirs(Files.createTempDirectory("zoukan-e2e-upcoming").toFile())
        ZoukanRuntime.start(dirs, interactiveBrowser = false)
        startUi(dirs)
        val preferences = Injekt.get<DesktopPreferences>()
        // "To be updated" then lists every entry that is due, whatever its unread chapters
        Injekt.get<LibraryPreferences>().autoUpdateMangaRestrictions().set(setOf(LibraryPreferences.MANGA_OUTSIDE_RELEASE_PERIOD))

        val today = LocalDate.now()
        val zone = ZoneId.systemDefault()
        // Alpha tomorrow, Bravo and Charlie in three days; Delta is completed, so not upcoming
        val entries = listOf(
            Triple("Alpha", 1L, SManga.ONGOING),
            Triple("Bravo", 3L, SManga.ONGOING),
            Triple("Charlie", 3L, SManga.PUBLISHING_FINISHED),
            Triple("Delta", 2L, SManga.COMPLETED),
        )
        runBlocking {
            val created = Injekt.get<NetworkToLocalManga>()(
                entries.map { (title, _, _) -> Manga.create().copy(url = "/$title", ogTitle = title, source = LocalSource.ID) },
            )
            created.forEach { manga ->
                val (_, days, status) = entries.first { it.first == manga.title }
                val next = today.plusDays(days).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
                Injekt.get<MangaRepository>().update(
                    MangaUpdate(
                        id = manga.id,
                        favorite = true,
                        dateAdded = System.currentTimeMillis(),
                        status = status.toLong(),
                        nextUpdate = next,
                    ),
                )
            }
        }

        runDesktopComposeUiTest(width = 1280, height = 820, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
            setContent { ZoukanContent(preferences) }

            step("Open Upcoming from the Updates tab") {
                waitFor(hasText("Library"))
                onAllNodesWithText("Updates").onFirst().performClick()
                byDescription("View Upcoming Updates").performClick()
                waitFor(hasText("Tomorrow"))
                waitFor(hasText("In 3 days"))
                onAllNodesWithTag("upcoming-item").fetchSemanticsNodes().size shouldBe 3
                onAllNodesWithText("Delta").fetchSemanticsNodes().size shouldBe 0
                shot("u1-upcoming")
            }

            step("Click a day and change the month") {
                onAllNodesWithTag("upcoming-day-${today.plusDays(3).dayOfMonth}").onFirst().performClick()
                byDescription("Next Month").performClick()
                val next = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()).format(YearMonth.now().plusMonths(1))
                waitFor(hasText(next))
                shot("u2-next-month")
                byDescription("Previous Month").performClick()
            }

            step("Smart update: the entries the next update checks") {
                byDescription("Smart update").performClick()
                waitFor(hasText("To be updated"))
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("upcoming-item").fetchSemanticsNodes().size == 4 }
                shot("u3-to-be-updated")
                byDescription("Smart update").performClick()
                waitFor(hasText("Upcoming"))
            }

            step("Open an entry") {
                onAllNodesWithText("Alpha").onFirst().performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("upcoming-item").fetchSemanticsNodes().isEmpty() }
                shot("u4-entry")
            }
        }
    }

    private fun ComposeUiTest.byDescription(text: String) =
        onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(text))).onFirst()

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
