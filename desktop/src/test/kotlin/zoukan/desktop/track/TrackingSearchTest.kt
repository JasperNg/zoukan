package zoukan.desktop.track

import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import java.util.concurrent.Executors

/** A tracker's search results only ever fill that tracker's list (ui.md #8). */
class TrackingSearchTest {

    private fun result(title: String) = TrackSearch.create(0).apply { this.title = title }

    private val slowA = CompletableDeferred<List<TrackSearch>>()
    private val trackerA: BaseTracker = mockk(relaxed = true) {
        every { id } returns 1
        coEvery { search(any()) } coAnswers { slowA.await() }
    }
    private val trackerB: BaseTracker = mockk(relaxed = true) {
        every { id } returns 2
        coEvery { search(any()) } returns listOf(result("from B"))
    }

    // The model's scope runs on Main; a thread of its own stands in for the UI thread
    private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    init {
        Dispatchers.setMain(main)
    }

    private val models = mutableListOf<TrackingModel>()

    private fun newModel(title: String) = TrackingModel(
        manga = Manga.create().copy(id = 1, ogTitle = title),
        trackerManager = mockk { every { loggedInTrackersFlow() } returns flowOf(listOf(trackerA, trackerB)) },
        getTracks = mockk { every { subscribe(any<Long>()) } returns flowOf(emptyList()) },
        deleteTrack = mockk(),
        sourceManager = mockk<SourceManager>(relaxed = true),
        insertTrack = mockk(),
    ).also { models += it }

    private val model = newModel("Title")

    @AfterEach
    fun tearDown() {
        models.forEach { it.onDispose() }
        Dispatchers.resetMain()
        main.close()
    }

    private fun waitUntil(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "Timed out; state ${model.state.value}" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `a slow result from the previous tracker never fills the new tracker's list`() {
        model.startSearch(trackerA)
        model.startSearch(trackerB)
        waitUntil { model.state.value.results?.isNotEmpty() == true }
        // A answers late: it must not replace B's results
        slowA.complete(listOf(result("from A")))
        Thread.sleep(200)
        model.state.value.searching shouldBe trackerB
        model.state.value.results?.map { it.title } shouldBe listOf("from B")
        model.state.value.loading shouldBe false
    }

    @Test
    fun `closing the search drops a result still on its way`() {
        model.startSearch(trackerA)
        model.closeSearch()
        slowA.complete(listOf(result("from A")))
        Thread.sleep(200)
        model.state.value.results shouldBe null
        model.state.value.loading shouldBe false
    }

    @Test
    fun `a result for a tracker no longer searched is dropped even when its job was not cancelled`() {
        model.startSearch(trackerB)
        waitUntil { model.state.value.results?.isNotEmpty() == true }
        // Straight to the guard, as a result that outran the cancellation would arrive
        model.applyResult(trackerA) { it.copy(results = listOf(result("from A")), loading = true) }
        model.state.value.results?.map { it.title } shouldBe listOf("from B")
        model.state.value.loading shouldBe false
        // TrackSearch equality is by ids, so the new result needs its own id to show as a change
        model.applyResult(trackerB) { it.copy(results = listOf(result("B again").apply { remote_id = 7 })) }
        model.state.value.results?.map { it.title } shouldBe listOf("B again")
    }

    @Test
    fun `switching to a tracker with a blank query clears the spinner`() {
        val blank = newModel("")
        blank.startSearch(trackerA)
        blank.setQuery("Title")
        blank.search()
        blank.state.value.loading shouldBe true
        // A's search never answers; B has nothing to search for, so no new search starts
        blank.startSearch(trackerB)
        blank.state.value.searching shouldBe trackerB
        blank.state.value.loading shouldBe false
    }
}
