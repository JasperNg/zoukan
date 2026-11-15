package zoukan.desktop.track

import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.source.online.MetadataSource
import exh.metadata.metadata.MangaDexSearchMetadata
import exh.metadata.metadata.RaisedSearchMetadata
import exh.metadata.metadata.base.FlatMetadata
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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

/** SY's "Resolve with source metadata": the tracker id in the entry's metadata before a search. */
class TrackerMetadataResolveTest {

    private val metadata = MangaDexSearchMetadata().apply {
        anilistId = "100"
        kitsuId = "200"
        myAnimeListId = "300"
        mangaUpdatesId = "400"
    }

    @Test
    fun `each tracker reads its own id`() {
        TrackerMetadataIds.idFor(TrackerManager.ANILIST, metadata) shouldBe "100"
        TrackerMetadataIds.idFor(TrackerManager.KITSU, metadata) shouldBe "200"
        TrackerMetadataIds.idFor(TrackerMetadataIds.MY_ANIME_LIST, metadata) shouldBe "300"
        TrackerMetadataIds.idFor(TrackerMetadataIds.MANGA_UPDATES, metadata) shouldBe "400"
    }

    @Test
    fun `other trackers and missing metadata have none`() {
        TrackerMetadataIds.idFor(TrackerManager.MDLIST, metadata) shouldBe null
        TrackerMetadataIds.idFor(TrackerManager.ANILIST, null) shouldBe null
        TrackerMetadataIds.idFor(TrackerManager.ANILIST, MangaDexSearchMetadata()) shouldBe null
    }

    // The model's scope runs on Main; a thread of its own stands in for the UI thread
    private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    init {
        Dispatchers.setMain(main)
    }

    private val models = mutableListOf<TrackingModel>()

    @AfterEach
    fun tearDown() {
        models.forEach { it.onDispose() }
        Dispatchers.resetMain()
        main.close()
    }

    private val found = TrackSearch.create(TrackerManager.ANILIST).apply { title = "Found" }

    private fun tracker(byId: TrackSearch?): BaseTracker = mockk(relaxed = true) {
        every { id } returns TrackerManager.ANILIST
        coEvery { searchById("100") } returns byId
        coEvery { search(any()) } returns listOf(TrackSearch.create(TrackerManager.ANILIST).apply { title = "Searched" })
    }

    private fun newModel(tracker: BaseTracker, resolve: Boolean, stored: MangaDexSearchMetadata? = metadata): TrackingModel {
        val source = mockk<MetadataSource<MangaDexSearchMetadata, Any>>(relaxed = true) {
            every { metaClass } returns MangaDexSearchMetadata::class
        }
        val flat = mockk<FlatMetadata> { every { raise(any<kotlin.reflect.KClass<RaisedSearchMetadata>>()) } returns (stored ?: MangaDexSearchMetadata()) }
        return TrackingModel(
            manga = Manga.create().copy(id = 1, source = 2, ogTitle = "Title"),
            trackerManager = mockk { every { loggedInTrackersFlow() } returns flowOf(listOf(tracker)) },
            getTracks = mockk { every { subscribe(any<Long>()) } returns flowOf(emptyList()) },
            deleteTrack = mockk(),
            sourceManager = mockk<SourceManager>(relaxed = true) { every { get(2) } returns source },
            insertTrack = mockk(),
            trackPreferences = mockk { every { resolveUsingSourceMetadata() } returns mockk { every { get() } returns resolve } },
            getFlatMetadataById = mockk { coEvery { await(1) } returns flat },
        ).also { models += it }
    }

    private fun waitUntil(model: TrackingModel, condition: (TrackingModel.State) -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!condition(model.state.value)) {
            check(System.currentTimeMillis() < end) { "Timed out; state ${model.state.value}" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `the id in the metadata registers that entry without a search`() {
        val tracker = tracker(byId = found)
        val model = newModel(tracker, resolve = true)
        model.startSearch(tracker)
        waitUntil(model) { it.searching == null }
        coVerify(timeout = 5_000) { tracker.register(found, 1) }
        coVerify(exactly = 0) { tracker.search(any()) }
    }

    @Test
    fun `an id the tracker does not know falls back to the search`() {
        val tracker = tracker(byId = null)
        val model = newModel(tracker, resolve = true)
        model.startSearch(tracker)
        waitUntil(model) { it.results?.isNotEmpty() == true }
        model.state.value.searching shouldBe tracker
        model.state.value.results?.map { it.title } shouldBe listOf("Searched")
        coVerify(exactly = 0) { tracker.register(any(), any()) }
    }

    @Test
    fun `metadata without the tracker's id falls back to the search`() {
        val tracker = tracker(byId = found)
        val model = newModel(tracker, resolve = true, stored = MangaDexSearchMetadata())
        model.startSearch(tracker)
        waitUntil(model) { it.results?.isNotEmpty() == true }
        coVerify(exactly = 0) { tracker.searchById(any()) }
    }

    @Test
    fun `the setting off goes straight to the search`() {
        val tracker = tracker(byId = found)
        val model = newModel(tracker, resolve = false)
        model.startSearch(tracker)
        waitUntil(model) { it.results?.isNotEmpty() == true }
        coVerify(exactly = 0) { tracker.searchById(any()) }
        coVerify(exactly = 0) { tracker.register(any(), any()) }
    }
}
