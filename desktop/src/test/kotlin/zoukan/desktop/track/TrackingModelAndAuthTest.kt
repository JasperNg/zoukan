package zoukan.desktop.track

import android.net.Uri
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.domain.track.store.DelayedTrackingStore
import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.source.online.MetadataSource
import exh.md.utils.MdUtil
import exh.metadata.metadata.MangaDexSearchMetadata
import exh.metadata.metadata.RaisedSearchMetadata
import exh.metadata.metadata.base.FlatMetadata
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.model.Track
import xyz.nulldev.androidcompat.io.sharedprefs.JavaSharedPreferences
import zoukan.desktop.testing.Await
import zoukan.desktop.testing.UncaughtCoroutineErrors
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.reflect.KClass

class TrackerLoginTest {
    @Test
    fun `upper-case host is handled the same as lower-case`() {
        val redirect = TrackerLogin.parseRedirect("KOMIKKU://AniList-Auth#access_token=t")!!
        redirect.host shouldBe "anilist-auth"
        TrackerLogin.decide(redirect, expectedHost = "anilist-auth") shouldBe TrackerLogin.RedirectAction.SIGN_IN
    }

    @Test
    fun `error message does not contain the code`() {
        val message = shouldThrow<IllegalStateException> {
            TrackerLogin.requireRedirect("komikku://other-auth?code=SECRETCODE")
        }.message.orEmpty()
        message shouldNotContain "SECRETCODE"
    }

    @Test
    fun `the AniList window ignores a mangadex-auth redirect`() {
        val redirect = TrackerLogin.parseRedirect("komikku://mangadex-auth?code=attacker")!!
        TrackerLogin.decide(redirect, expectedHost = "anilist-auth") shouldBe TrackerLogin.RedirectAction.IGNORE
    }

    @Test
    fun `a redirect without a code does not sign out`() {
        val redirect = TrackerLogin.parseRedirect("komikku://shikimori-auth")!!
        TrackerLogin.decide(redirect, expectedHost = "shikimori-auth") shouldBe TrackerLogin.RedirectAction.IGNORE
    }

    @Test
    fun `the tracker's own redirect with a code or an error is acted on`() {
        TrackerLogin.decide(TrackerLogin.parseRedirect("komikku://anilist-auth#access_token=t")!!, "anilist-auth") shouldBe
            TrackerLogin.RedirectAction.SIGN_IN
        TrackerLogin.decide(TrackerLogin.parseRedirect("komikku://myanimelist-auth?code=c")!!, "myanimelist-auth") shouldBe
            TrackerLogin.RedirectAction.SIGN_IN
        TrackerLogin.decide(TrackerLogin.parseRedirect("komikku://bangumi-auth?error=access_denied")!!, "bangumi-auth") shouldBe
            TrackerLogin.RedirectAction.SIGN_OUT
    }

    @Test
    fun `recognizes the app-scheme redirects`() {
        TrackerLogin.isRedirect("komikku://anilist-auth#access_token=abc") shouldBe true
        TrackerLogin.isRedirect("mihon://myanimelist-auth?code=1") shouldBe true
        TrackerLogin.isRedirect("KOMIKKU://Bangumi-Auth?code=1") shouldBe true
        TrackerLogin.isRedirect("tachiyomisy://mangadex-auth?code=1") shouldBe true
        TrackerLogin.isRedirect("https://anilist.co/api/v2/oauth/authorize") shouldBe false
        TrackerLogin.isRedirect("komikku://other-auth?code=1") shouldBe false
    }

    @Test
    fun `parses and decodes parameters`() {
        TrackerLogin.parseParameters("access_token=a%2Bb&token_type=Bearer&flag") shouldBe
            mapOf("access_token" to "a+b", "token_type" to "Bearer", "flag" to null)
        TrackerLogin.parseParameters(null) shouldBe emptyMap()
    }

    @Test
    fun `each MangaDex sign-in uses a new verifier`() {
        fun challenge(url: String) = TrackerLogin.parseParameters(Uri.parse(url).encodedQuery)["code_challenge"]
        val first = challenge(TrackerLogin.mangaDexAuthUrl())
        val second = challenge(TrackerLogin.mangaDexAuthUrl())
        second shouldNotBe first
        // The code exchange sends the verifier of the sign-in that was started last
        val verifier = MdUtil.getPkceChallengeCode()
        val expected = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        second shouldBe expected
    }
}

class TrackChaptersTest {

    @Test
    fun `a queued tracker update that a later one overtook is dropped`() = runBlocking<Unit> {
        val track = mockk<Track> {
            every { id } returns 7L
            every { mangaId } returns 3L
            every { lastChapterRead } returns 12.0
        }
        val getTracks = mockk<GetTracks> { coEvery { awaitOne(7L) } returns track }
        val store = mockk<DelayedTrackingStore>(relaxed = true) {
            every { getItems() } returns listOf(DelayedTrackingStore.DelayedTrackingItem(7L, 10f))
        }
        val trackChapters = TrackChapters(getTracks, mockk(), mockk(), store, mockk())

        trackChapters.retryDelayed()

        verify { store.remove(7L) }
        // Nothing sent: the tracker already has a later chapter
        coVerify(exactly = 0) { getTracks.await(any<Long>()) }
    }

    private fun preferences(autoUpdate: Boolean): TrackPreferences =
        TrackPreferences(DesktopPreferenceStore(JavaSharedPreferences("track_chapters_test_${System.nanoTime()}")))
            .also { it.autoUpdateTrack().set(autoUpdate) }

    @Test
    fun `progress is not sent after reading when auto update is off`() = runBlocking<Unit> {
        val getTracks = mockk<GetTracks>()

        TrackChapters(getTracks, mockk(), mockk(), mockk(relaxed = true), preferences(false)).afterReading(3L, 5.0)

        coVerify(exactly = 0) { getTracks.await(any<Long>()) }
    }

    @Test
    fun `progress is sent after reading when auto update is on`() = runBlocking<Unit> {
        val getTracks = mockk<GetTracks> { coEvery { await(3L) } returns emptyList() }

        TrackChapters(getTracks, mockk(), mockk(), mockk(relaxed = true), preferences(true)).afterReading(3L, 5.0)

        coVerify(exactly = 1) { getTracks.await(3L) }
    }
}

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
        val flat = mockk<FlatMetadata> { every { raise(any<KClass<RaisedSearchMetadata>>()) } returns (stored ?: MangaDexSearchMetadata()) }
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

    private fun waitUntil(model: TrackingModel, condition: (TrackingModel.State) -> Boolean) =
        Await.until("the tracking model", 5_000, { model.state.value }) { condition(model.state.value) }

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
        trackPreferences = mockk { every { resolveUsingSourceMetadata() } returns mockk { every { get() } returns false } },
        getFlatMetadataById = mockk(),
    ).also { models += it }

    private val model = newModel("Title")

    @AfterEach
    fun tearDown() {
        models.forEach { it.onDispose() }
        // The models' coroutines read Dispatchers.Main until they end: resetting it while one still
        // runs fails ("used concurrently with setting it"). The scope itself lives on (Voyager cancels
        // it only for a registered screen), so its coroutines are cancelled and awaited here
        runBlocking {
            withTimeout(5_000) {
                models.forEach { model ->
                    val job = model.screenModelScope.coroutineContext.job
                    job.cancelChildren()
                    job.children.toList().joinAll()
                }
            }
        }
        Dispatchers.resetMain()
        main.close()
    }

    private fun waitUntil(condition: () -> Boolean) =
        Await.until("the tracking search", 5_000, { model.state.value }, condition)

    /**
     * Waits until every coroutine the model started has ended, so a late result has either been
     * stored or dropped by the time the test looks at the state.
     */
    private fun awaitModelIdle() = runBlocking {
        withTimeout(5_000) { model.screenModelScope.coroutineContext.job.children.toList().joinAll() }
    }

    @Test
    fun `a slow result from the previous tracker never fills the new tracker's list`() {
        model.startSearch(trackerA)
        model.startSearch(trackerB)
        waitUntil { model.state.value.results?.isNotEmpty() == true }
        // A answers late: it must not replace B's results
        slowA.complete(listOf(result("from A")))
        awaitModelIdle()
        model.state.value.searching shouldBe trackerB
        model.state.value.results?.map { it.title } shouldBe listOf("from B")
        model.state.value.loading shouldBe false
    }

    @Test
    fun `closing the search drops a result still on its way`() {
        model.startSearch(trackerA)
        model.closeSearch()
        slowA.complete(listOf(result("from A")))
        awaitModelIdle()
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

    @Test
    fun `running out of memory in a search ends the spinner and still reaches the crash report`() {
        val failing: BaseTracker = mockk(relaxed = true) {
            every { id } returns 3
            coEvery { search(any()) } throws OutOfMemoryError("test")
        }
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
        try {
            model.startSearch(failing)
            waitUntil { uncaught.isNotEmpty() && !model.state.value.loading }
            model.state.value.loading shouldBe false
            (uncaught.single() is OutOfMemoryError) shouldBe true
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
            UncaughtCoroutineErrors.drain()
        }
    }
}
