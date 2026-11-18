package zoukan.desktop.exh

import io.kotest.matchers.shouldBe
import io.mockk.coVerify
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test

/** Pausing the E-Hentai updates (the favorites sync does) must also stop a round that is running. */
class EhGalleryUpdaterPauseTest {

    @Test
    fun `pausing stops a running round after the current gallery`() = runBlocking<Unit> {
        val fixture = EhUpdaterFixture()
        val round = async(Dispatchers.Default) { fixture.updater.run() }

        withTimeout(10_000) { fixture.entered.await() }
        fixture.updater.setPaused(true)
        fixture.release.complete(Unit)
        withTimeout(10_000) { round.await() }

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
    }

    @Test
    fun `cancelling the background update waits for the running round to end`() = runBlocking<Unit> {
        val fixture = EhUpdaterFixture()
        val round = async(Dispatchers.Default) { fixture.updater.run() }
        withTimeout(10_000) { fixture.entered.await() }
        fixture.updater.setPaused(true)

        // Runs until it suspends: it returns at once unless it waits for the round
        val idle = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { fixture.updater.awaitIdle() }
        idle.isCompleted shouldBe false

        fixture.release.complete(Unit)
        withTimeout(10_000) { idle.await() }
        withTimeout(10_000) { round.await() }
    }
}
