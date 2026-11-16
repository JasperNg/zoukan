package zoukan.desktop.ui.browse

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.source.service.SourcePreferences.MigrateSortingDirection
import eu.kanade.domain.source.service.SourcePreferences.MigrateSortingMode
import eu.kanade.tachiyomi.source.Source
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import zoukan.desktop.local.LocalSource
import java.util.concurrent.Executors

class MigrateSourcesModelTest {

    // Sources 1 and 2 are installed; every other id is a stub with a name
    private val installed: Map<Long, Source> = mapOf(1L to StubSource(1, "en", "Alpha"), 2L to StubSource(2, "en", "Zulu"))
    private val sourceManager = mockk<SourceManager>().also { manager ->
        every { manager.get(any<Long>()) } answers { installed[firstArg()] }
        every { manager.getOrStub(any()) } answers { installed[firstArg()] ?: StubSource(firstArg(), "en", "Missing ${firstArg<Long>()}") }
    }

    private fun items(
        counts: Map<Long, Int>,
        query: String = "",
        mode: MigrateSortingMode = MigrateSortingMode.ALPHABETICAL,
        direction: MigrateSortingDirection = MigrateSortingDirection.ASCENDING,
    ) = migrateSourceItems(counts, sourceManager, query, mode, direction)

    @Test
    fun `sources that are not installed come first in either direction`() {
        val counts = mapOf(1L to 3, 2L to 5, 9L to 1)

        items(counts, direction = MigrateSortingDirection.ASCENDING).map { it.id } shouldBe listOf(9L, 1L, 2L)
        items(counts, direction = MigrateSortingDirection.DESCENDING).map { it.id } shouldBe listOf(9L, 2L, 1L)
        items(counts, mode = MigrateSortingMode.TOTAL, direction = MigrateSortingDirection.DESCENDING).map { it.id } shouldBe listOf(9L, 2L, 1L)
    }

    @Test
    fun `a comma search matches names or ids`() {
        val counts = mapOf(1L to 3, 2L to 5, 42L to 1, 7L to 2)

        items(counts, query = "alpha, 42").map { it.id }.toSet() shouldBe setOf(1L, 42L)
    }

    @Test
    fun `the local source is left out`() {
        items(mapOf(LocalSource.ID to 4, 1L to 3)).map { it.id } shouldBe listOf(1L)
    }

    private fun entry(id: Long, source: Long) = LibraryManga(
        manga = Manga.create().copy(id = id, source = source),
        categories = emptyList(),
        totalChapters = 0,
        readCount = 0,
        bookmarkCount = 0,
        bookmarkReadCount = 0,
        chapterFlags = 0,
        latestUpload = 0,
        chapterFetchedAt = 0,
        lastRead = 0,
    )

    @Test
    fun `the model lists the library's sources and follows the search`() {
        // The model's scope runs on Main; a thread of its own stands in for the UI thread
        val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(main)
        val model = MigrateSourcesModel(
            getLibraryManga = mockk { every { subscribe() } returns flowOf(listOf(entry(1, 1), entry(2, 1), entry(3, 2))) },
            sourceManager = sourceManager,
            preferences = SourcePreferences(InMemoryPreferenceStore()),
        )
        try {
            fun waitUntil(condition: () -> Boolean) {
                val end = System.currentTimeMillis() + 5_000
                while (!condition()) {
                    check(System.currentTimeMillis() < end) { "Timed out; state ${model.state.value}" }
                    Thread.sleep(10)
                }
            }

            waitUntil { model.state.value.items != null }
            model.state.value.items!!.map { it.name to it.count } shouldBe listOf("Alpha" to 2, "Zulu" to 1)

            model.search("zul")

            model.state.value.query shouldBe "zul"
            waitUntil { model.state.value.items?.size == 1 }
            model.state.value.items!!.map { it.name } shouldBe listOf("Zulu")

            model.search("")
            waitUntil { model.state.value.items?.size == 2 }
        } finally {
            model.onDispose()
            Dispatchers.resetMain()
            main.close()
        }
    }
}
