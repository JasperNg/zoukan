package zoukan.desktop.compat

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import mihon.domain.source.interactor.UpdateMangaFromRemote
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga
import zoukan.desktop.domain.RefreshManga
import kotlin.coroutines.cancellation.CancellationException

class UpdateMangaFromRemoteTest {
    private val manga = Manga.create().copy(id = 1)

    @Test
    fun `cancelling a refresh is passed on, not returned as a failure`() {
        val refreshManga = mockk<RefreshManga> {
            coEvery { this@mockk.invoke(any(), any(), any(), any(), any(), any()) } throws CancellationException("stopped")
        }
        val update = UpdateMangaFromRemote(sourceManager = mockk(), refreshManga = refreshManga)
        shouldThrow<CancellationException> {
            runBlocking { update(source = mockk(), manga = manga) }
        }
    }

    @Test
    fun `a failing refresh still comes back as a failure`() {
        val refreshManga = mockk<RefreshManga> {
            coEvery { this@mockk.invoke(any(), any(), any(), any(), any(), any()) } throws java.io.IOException("offline")
        }
        val result = runBlocking { UpdateMangaFromRemote(mockk(), refreshManga)(source = mockk(), manga = manga) }
        result.exceptionOrNull().shouldBeInstanceOf<java.io.IOException>()
    }
}
