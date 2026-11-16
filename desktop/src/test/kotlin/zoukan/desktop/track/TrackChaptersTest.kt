package zoukan.desktop.track

import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.domain.track.store.DelayedTrackingStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.model.Track
import xyz.nulldev.androidcompat.io.sharedprefs.JavaSharedPreferences

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
