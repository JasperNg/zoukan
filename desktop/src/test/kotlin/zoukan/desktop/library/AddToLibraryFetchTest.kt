package zoukan.desktop.library

import eu.kanade.tachiyomi.source.Source
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import zoukan.desktop.domain.RefreshManga
import zoukan.desktop.testing.Await
import java.util.concurrent.atomic.AtomicInteger

/** Adding many entries at once (bulk add) must not fetch them all from the site at the same time. */
class AddToLibraryFetchTest {

    private fun manga(id: Long) = mockk<Manga> {
        every { this@mockk.id } returns id
        every { source } returns 10L
        every { title } returns "Entry $id"
    }

    @Test
    fun `fetches after adding run a few at a time`() = runBlocking<Unit> {
        val running = AtomicInteger()
        val most = AtomicInteger()
        val finished = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        val allStarted = CompletableDeferred<Unit>()
        val refreshManga = mockk<RefreshManga>()
        coEvery { refreshManga.invoke(any(), any(), any(), any(), any(), any()) } coAnswers {
            val now = running.incrementAndGet()
            most.accumulateAndGet(now, ::maxOf)
            if (now >= AddToLibrary.PARALLEL_FETCHES) allStarted.complete(Unit)
            release.await()
            running.decrementAndGet()
            finished.incrementAndGet()
            mockk(relaxed = true)
        }
        val preferences = mockk<LibraryPreferences> {
            every { fetchMetadataOnAdd() } returns mockk { every { get() } returns true }
            every { fetchChaptersOnAdd() } returns mockk { every { get() } returns true }
        }
        val addToLibrary = AddToLibrary(
            getCategories = mockk(relaxed = true),
            setMangaCategories = mockk(relaxed = true),
            mangaRepository = mockk(relaxed = true),
            getDuplicateLibraryManga = mockk(relaxed = true),
            libraryPreferences = preferences,
            addTracks = mockk(relaxed = true),
            refreshManga = refreshManga,
            sourceManager = mockk { every { get(10L) } returns mockk<Source>(relaxed = true) },
        )

        val count = 12
        (1L..count).forEach { addToLibrary.add(manga(it), emptyList(), fetchOnAdd = true) }

        withTimeout(10_000) { allStarted.await() }
        // Give any fetch that would start past the limit the chance to do so before releasing;
        // with the limit in place this always waits the full time. Nothing marks a fetch that
        // does not start, so only a pause can show it
        Await.within(NOTHING_HAPPENS_MILLIS) { running.get() > AddToLibrary.PARALLEL_FETCHES }
        most.get() shouldBeLessThanOrEqual AddToLibrary.PARALLEL_FETCHES
        release.complete(Unit)
        Await.untilSuspending("every fetch to finish") { finished.get() >= count }
        most.get() shouldBeLessThanOrEqual AddToLibrary.PARALLEL_FETCHES
        finished.get() shouldBe count
    }

    private companion object {
        /** How long the test watches for a fetch past the limit, which must not start. */
        const val NOTHING_HAPPENS_MILLIS = 500L
    }
}
