package zoukan.desktop.library

import eu.kanade.tachiyomi.source.Source
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
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
        // The limit's semaphore counts who asks for a permit
        val original = AddToLibrary.fetches
        val permits = Semaphore(AddToLibrary.PARALLEL_FETCHES)
        val asked = AtomicInteger()
        AddToLibrary.fetches = object : Semaphore by permits {
            override suspend fun acquire() {
                asked.incrementAndGet()
                permits.acquire()
            }
        }
        try {
            (1L..count).forEach { addToLibrary.add(manga(it), emptyList(), fetchOnAdd = true) }

            withTimeout(10_000) { allStarted.await() }
            // Every fetch has asked for a permit: the first ones hold them until released, so the
            // rest wait, and none past the limit can have started
            Await.untilSuspending("every fetch to ask for a permit") { asked.get() == count }
            running.get() shouldBe AddToLibrary.PARALLEL_FETCHES
            most.get() shouldBeLessThanOrEqual AddToLibrary.PARALLEL_FETCHES
            release.complete(Unit)
            Await.untilSuspending("every fetch to finish") { finished.get() >= count }
            most.get() shouldBeLessThanOrEqual AddToLibrary.PARALLEL_FETCHES
            finished.get() shouldBe count
        } finally {
            AddToLibrary.fetches = original
        }
    }
}
