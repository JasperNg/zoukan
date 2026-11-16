package zoukan.desktop.exh

import io.mockk.coVerify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import zoukan.desktop.backup.RestoreGate

/** A backup restore rewrites the library, so the E-Hentai update must not write while it waits or runs. */
class EhGalleryUpdaterRestoreTest {

    @Test
    fun `a restore that is asked for stops the round before the next gallery`() = runBlocking<Unit> {
        val fixture = EhUpdaterFixture()
        val round = async(Dispatchers.Default) { fixture.updater.run() }

        withTimeout(10_000) { fixture.entered.await() }
        RestoreGate.pending.incrementAndGet()
        try {
            fixture.release.complete(Unit)
            withTimeout(10_000) { round.await() }
        } finally {
            RestoreGate.pending.decrementAndGet()
        }

        coVerify(exactly = 1) {
            fixture.updateMangaFromRemote.invoke(
                source = any(),
                manga = any(),
                fetchDetails = any(),
                fetchChapters = any(),
                manualFetch = any(),
                fetchWindow = any(),
                throttleFunc = any(),
            )
        }
        // No further entry was read or written after the restore was asked for
        coVerify(exactly = 1) { fixture.getChaptersByMangaId.await(any()) }
    }
}
