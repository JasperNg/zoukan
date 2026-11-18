package zoukan.desktop.ui.browse

import eu.kanade.tachiyomi.source.CatalogueSource
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.service.SourceManager
import xyz.nulldev.ts.api.http.serializer.FilterSerializer
import zoukan.desktop.testing.Await
import zoukan.desktop.testing.UncaughtCoroutineErrors
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Running out of memory while a source's page loads goes on up to the crash report, after the
 * listing has stopped loading: a listing left loading spins for good and loads no more pages.
 */
class SourceFatalErrorTest {

    private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
    private val uncaught = CopyOnWriteArrayList<Throwable>()

    init {
        Dispatchers.setMain(main)
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
    }

    private val fake: CatalogueSource = mockk(relaxed = true) {
        every { id } returns 42L
        coEvery { getPopularManga(any()) } throws OutOfMemoryError("test")
    }

    private val model = SourceScreenModel(
        sourceId = 42L,
        latest = false,
        initialQuery = null,
        sourceManager = mockk<SourceManager> { every { get(42L) } returns fake },
        getFavorites = mockk { every { subscribe(42L) } returns flowOf(emptyList()) },
        getExhSavedSearch = mockk { every { subscribe(42L, any()) } returns flowOf(emptyList()) },
        filterSerializer = FilterSerializer(),
        preferences = mockk(relaxed = true),
        networkToLocalManga = mockk(relaxed = true),
        insertSavedSearch = mockk(),
        deleteSavedSearchById = mockk(),
        getFlatMetadataById = mockk(relaxed = true),
    )

    @AfterEach
    fun tearDown() {
        model.onDispose()
        Dispatchers.resetMain()
        main.close()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        UncaughtCoroutineErrors.drain()
    }

    @Test
    fun `running out of memory on a page ends the loading and still reaches the crash report`() {
        Await.until("the listing's error", 10_000, state = { model.state.value }) {
            model.state.value.error != null && uncaught.isNotEmpty()
        }
        model.state.value.loading shouldBe false
        uncaught.single().shouldBeInstanceOf<OutOfMemoryError>()
    }
}
