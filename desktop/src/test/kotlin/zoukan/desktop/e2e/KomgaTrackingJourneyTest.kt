package zoukan.desktop.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.track.model.AutoTrackState
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.extension.all.komga.Komga
import exh.source.getMainSource
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.compat.localized
import zoukan.desktop.exh.BuiltInSources
import zoukan.desktop.testing.Await
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.e2eEffectContext
import zoukan.desktop.ui.manga.MangaScreen
import zoukan.i18n.desktop.DMR
import kotlin.time.Duration.Companion.minutes

/**
 * Tracking against a Komga server, offline: a Komga title added to the library binds the Komga
 * tracker by itself, reading a chapter sends the progress to the server, a server error shows and
 * leaves the stored progress alone (a later update gets through), "Update tracking: Ask" asks
 * after a chapter is marked read and sends only on Update, stopping tracking removes the track,
 * and with "Track when adding to the library" off adding binds again without the dialog. The Komga tracker is the one tracker that needs no site of its own: it calls the
 * series URL the source gave it, here the [FixtureKomga] on the [FixtureServer].
 *
 * The hosted trackers (AniList, MyAnimeList and the others) have fixed hosts and are covered by
 * unit tests (TrackerLoginTest, TrackingSearchTest, TrackChaptersTest).
 *
 *   ./gradlew :desktop:journeyTest --tests "*KomgaTrackingJourneyTest"
 */
@OptIn(ExperimentalTestApi::class)
class KomgaTrackingJourneyTest {

    private lateinit var journey: Journey
    private var komgaServer: FixtureKomga? = null

    @AfterEach
    fun close() {
        komgaServer?.close()
        if (::journey.isInitialized) journey.close()
    }

    @Test
    fun bindSyncAndUnbindAKomgaTitle() {
        journey = Journey.start("komga-tracking")
        val komga = FixtureKomga(journey.server).also { komgaServer = it }
        komga.series(SERIES_ID, SERIES_TITLE, books = 4)
        val source = Komga(journey.server.baseUrl)
        installSource(source)
        // The Settings > Tracking switch ("Turn on") calls this
        Injekt.get<TrackerManager>().komga.loginNoop()
        val preferences = Injekt.get<DesktopPreferences>()
        val trackPreferences = Injekt.get<TrackPreferences>()
        var navigator: Navigator? = null

        with(journey) {
            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
                setContent { ZoukanContent(preferences) { navigator = it } }

                step("Open $SERIES_TITLE from the Komga source") {
                    onAllNodesWithText("Browse").onFirst().performClick()
                    onAllNodesWithText("Sources").onFirst().performClick()
                    waitFor(hasText(source.name))
                    onAllNodesWithText(source.name).onFirst().performScrollTo().performClick()
                    waitFor(hasText(SERIES_TITLE))
                    onAllNodesWithText(SERIES_TITLE).onFirst().performClick()
                    // The books come as chapters, newest first
                    (1..4).forEach { waitFor(hasText("Book $it")) }
                    waitFor(hasText("Start") and isEnabled())
                }

                step("Add to library: the Komga tracker binds itself and the dialog shows it") {
                    onAllNodesWithText("Add to library").onFirst().performClick()
                    waitFor(hasText("In library"))
                    // The tracking dialog opens by itself after adding when a tracker is on
                    waitFor(hasText("Komga"))
                    waitOrDump("for the track in the database") { tracks(mangaId()).isNotEmpty() }
                    val track = tracks(mangaId()).single()
                    track.trackerId shouldBe Injekt.get<TrackerManager>().komga.id
                    track.remoteUrl shouldBe komga.seriesUrl(SERIES_ID)
                    track.lastChapterRead shouldBe 0.0
                    shot("1-bound")
                    closeDialog()
                }

                step("Read Book 2: the progress goes to the server and shows in the dialog") {
                    onAllNodesWithText("Book 2").onFirst().performClick()
                    readThrough(listOf(FixtureCatalog.pages("png").first()), "2-book-2")
                    runOnUiThread { navigator?.pop() }
                    waitFor(hasText("In library"))
                    waitOrDump("for the server to get the progress") { komga.progressUpdates.isNotEmpty() }
                    komga.progressUpdates shouldContainExactly listOf(SERIES_ID to 2.0)
                    waitOrDump("for the stored progress") { tracks(mangaId()).singleOrNull()?.lastChapterRead == 2.0 }

                    openTracking()
                    waitFor(chapterField("2"))
                    shot("3-synced")
                }

                step("A server error shows and leaves the stored progress alone") {
                    val before = komgaCalls()
                    journey.server.fail("/api/v2/", FixtureFailure.Status(500))
                    onNode(chapterField("2")).performTextReplacement("3")
                    onNode(chapterField("3")).performKeyInput { pressKey(Key.Enter) }
                    waitOrDump("for the error toast") { toasts.any { "500" in it } }
                    komgaCalls() shouldBe before + 1
                    toasts.first { "500" in it } shouldContain "500"
                    komga.progressUpdates shouldHaveSize 1
                    tracks(mangaId()).single().lastChapterRead shouldBe 2.0
                    shot("4-error")
                }

                step("Once the server is back, the same update gets through") {
                    journey.server.clearFailures()
                    onNode(chapterField("3")).performKeyInput { pressKey(Key.Enter) }
                    waitOrDump("for the server to get chapter 3") { komga.progressUpdates.size == 2 }
                    komga.progressUpdates shouldContainExactly listOf(SERIES_ID to 2.0, SERIES_ID to 3.0)
                    waitOrDump("for the stored progress") { tracks(mangaId()).singleOrNull()?.lastChapterRead == 3.0 }
                    shot("5-retried")
                }

                step("Update tracking set to Ask: marking Book 4 read asks; Not now sends nothing, Update sends chapter 4") {
                    trackPreferences.autoUpdateTrackOnMarkRead().set(AutoTrackState.ASK)
                    closeDialog()
                    val question = localized(DMR.strings.update_tracking)
                    markBook4(read = true)
                    waitFor(hasText(question))
                    waitFor(hasText(localized(DMR.strings.set_the_trackers_progress_to_chapter, "4")))
                    shot("6-ask")
                    onNode(hasText(localized(MR.strings.action_not_now)) and hasClickAction()).performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText(question)).fetchSemanticsNodes().isEmpty() }
                    tracks(mangaId()).single().lastChapterRead shouldBe 3.0

                    // Unread asks nothing; read again asks again
                    markBook4(read = false)
                    waitForIdle()
                    onAllNodes(hasText(question)).fetchSemanticsNodes().isEmpty() shouldBe true
                    markBook4(read = true)
                    waitFor(hasText(question))
                    onAllNodes(hasText(localized(MR.strings.ext_update)) and hasClickAction()).onLast().performClick()
                    waitOrDump("for the server to get chapter 4") { komga.progressUpdates.size == 3 }
                    // Not now sent nothing: only the one Update went out
                    komga.progressUpdates shouldContainExactly listOf(SERIES_ID to 2.0, SERIES_ID to 3.0, SERIES_ID to 4.0)
                    waitOrDump("for the stored progress") { tracks(mangaId()).singleOrNull()?.lastChapterRead == 4.0 }
                    openTracking()
                    waitFor(chapterField("4"))
                }

                step("Stop tracking: the row and the track go, and the server hears nothing more") {
                    onNode(hasContentDescription("Stop tracking") and hasClickAction()).performClick()
                    waitFor(hasText("Stop tracking on Komga?"))
                    onNode(hasText("Stop tracking") and hasClickAction()).performClick()
                    waitOrDump("for the track to go") { tracks(mangaId()).isEmpty() }
                    waitFor(hasText("Add tracking"))
                    val after = komgaCalls()
                    awaitAppIdle()
                    komgaCalls() shouldBe after
                    komga.progressUpdates shouldHaveSize 3
                    shot("7-unbound")
                    closeDialog()
                }

                step("Track when adding to the library off: adding binds Komga again, without opening the tracking dialog") {
                    trackPreferences.trackOnAddingToLibrary().set(false)
                    val id = mangaId()
                    runBlocking { Injekt.get<MangaRepository>().update(MangaUpdate(id = id, favorite = false)) }
                    // The entry's screen afresh, out of the library
                    runOnUiThread { navigator?.pop() }
                    runOnUiThread { navigator?.push(MangaScreen(id)) }
                    waitFor(hasText("Add to library") and isEnabled())
                    onAllNodesWithText("Add to library").onFirst().performClick()
                    waitFor(hasText("In library"))
                    // The enhanced tracker binds whatever the setting says, as on Android
                    waitOrDump("for the track in the database") { tracks(mangaId()).isNotEmpty() }
                    waitForIdle()
                    onAllNodes(hasText("Close") and hasClickAction()).fetchSemanticsNodes().isEmpty() shouldBe true
                    shot("8-added-without-dialog")
                    trackPreferences.trackOnAddingToLibrary().set(true)
                }
            }
        }
    }

    private fun ComposeUiTest.chapterField(text: String): SemanticsMatcher = hasSetTextAction() and hasText(text)

    /** Selects Book 4 by its checkbox and marks it read or unread from the selection's bar. */
    private fun ComposeUiTest.markBook4(read: Boolean) {
        onNode(hasContentDescription("Book 4") and isToggleable()).performClick()
        with(journey) { byDescription(localized(if (read) MR.strings.action_mark_as_read else MR.strings.action_mark_as_unread)).performClick() }
        waitForIdle()
    }

    private fun ComposeUiTest.closeDialog() {
        onNode(hasText("Close") and hasClickAction()).performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("Close") and hasClickAction()).fetchSemanticsNodes().isEmpty() }
    }

    private fun ComposeUiTest.openTracking() {
        onAllNodesWithText("1 tracker").onFirst().performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("Komga")).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Requests the Komga routes got, whatever their answer. */
    private fun komgaCalls(): Int = journey.server.requestLog.count { it.path.startsWith("/api/") }

    private fun mangaId(): Long = runBlocking { Injekt.get<GetFavorites>().await().single { it.title == SERIES_TITLE }.id }

    private fun tracks(mangaId: Long) = runBlocking { Injekt.get<GetTracks>().await(mangaId) }

    /** Adds the Komga stand-in beside the fixture source and waits until the SourceManager lists it. */
    private fun installSource(source: Komga) {
        val tests = Injekt.get<BuiltInSources>().testSources
        tests.value = tests.value + source
        val sources = Injekt.get<SourceManager>()
        runBlocking { sources.isInitialized.first { it } }
        Await.until("the SourceManager to list the Komga source") { sources.get(source.id)?.getMainSource<Komga>() === source }
    }

    private companion object {
        const val SERIES_ID = "1"
        const val SERIES_TITLE = "Komga Title"
    }
}
