package zoukan.desktop.ui.browse

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.service.SourceManager
import xyz.nulldev.ts.api.http.serializer.FilterSerializer
import java.util.concurrent.Executors

/** A search sends the filters as they were when it started, not the ones the filter sheet edits afterwards. */
class SourceFiltersSnapshotTest {

    private val asked = CompletableDeferred<Unit>()
    private val release = CompletableDeferred<Unit>()

    @Volatile
    private var received: FilterList? = null

    private val fake: CatalogueSource = mockk(relaxed = true) {
        every { id } returns 42L
        every { getFilterList() } answers { FilterList(object : Filter.CheckBox("Completed", false) {}) }
        coEvery { getSearchManga(any(), any(), any()) } coAnswers {
            received = thirdArg()
            asked.complete(Unit)
            release.await()
            MangasPage(emptyList(), false)
        }
    }

    // The model's scope runs on Main; a thread of its own stands in for the UI thread
    private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    init {
        Dispatchers.setMain(main)
    }

    private val model = SourceScreenModel(
        sourceId = 42L,
        latest = false,
        initialQuery = "query",
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
        release.complete(Unit)
        model.onDispose()
        Dispatchers.resetMain()
        main.close()
    }

    @Test
    fun `changing a filter while a search runs does not change the filters that search sent`() = runBlocking<Unit> {
        withTimeout(5_000) { asked.await() }
        // What the filter sheet does: change the filter in place, then signal it
        (model.filters.first() as Filter.CheckBox).state = true
        model.filtersChanged()
        release.complete(Unit)
        (received!!.first() as Filter.CheckBox).state shouldBe false
    }
}
