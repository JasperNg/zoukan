package zoukan.desktop.ui.recs

import eu.kanade.domain.source.service.SourcePreferences
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.model.Manga
import zoukan.desktop.compat.localized
import zoukan.i18n.desktop.DMR
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A recommendation search that fails ends with an error, as every other path into a source does:
 * a LinkageError from an outdated extension is the search's error, and only the VM's own failures
 * go on to the crash report.
 */
class RecommendationSearchErrorTest {

    private fun search(error: Throwable): Pair<RecommendationSearchHelper, List<Throwable>> {
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val helper = RecommendationSearchHelper(
            preferences = lazyOf(SourcePreferences(InMemoryPreferenceStore())),
            getLibraryManga = lazyOf(mockk<GetLibraryManga> { coEvery { await() } throws error }),
        )
        val scope = CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, e -> uncaught += e })
        val job = helper.runSearch(scope, listOf(Manga.create().copy(id = 1, ogTitle = "Entry")))!!
        runBlocking { withTimeout(5_000) { job.join() } }
        return helper to uncaught
    }

    @Test
    fun `an outdated extension's LinkageError ends the search with an error and goes no further`() {
        val (helper, uncaught) = search(NoSuchMethodError("'java.util.List eu.kanade.Removed.method(int)'"))

        val status = helper.status.value.shouldBeInstanceOf<SearchStatus.Error>()
        // The user reads that the extension is outdated, not the JVM's method signature
        status.message shouldBe localized(DMR.strings.source_extension_outdated)
        uncaught.shouldBeEmpty()
    }

    @Test
    fun `running out of memory ends the search with an error and still reaches the crash report`() {
        val (helper, uncaught) = search(OutOfMemoryError("test"))

        helper.status.value.shouldBeInstanceOf<SearchStatus.Error>()
        uncaught.single().shouldBeInstanceOf<OutOfMemoryError>()
        uncaught.size shouldBe 1
    }
}
