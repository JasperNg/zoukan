package zoukan.desktop.ui.history

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.history.service.HistoryPreferences

class HistoryFiltersTest {
    @Test
    fun `filters read Komikku's history preferences`() = runBlocking {
        val preferences = HistoryPreferences(InMemoryPreferenceStore())
        historyFilters(preferences).first().active shouldBe false

        preferences.filterUnfinishedChapter().set(TriState.ENABLED_IS)
        preferences.filterNonLibraryManga().set(TriState.ENABLED_NOT)
        val filters = historyFilters(preferences).first()
        filters.active shouldBe true
        filters.unfinishedManga.toBooleanOrNull() shouldBe null
        filters.unfinishedChapter.toBooleanOrNull() shouldBe true
        filters.nonLibrary.toBooleanOrNull() shouldBe false
    }

    @Test
    fun `selection toggles, selects ranges and inverts`() {
        val ids = listOf(10L, 11L, 12L, 13L, 14L)
        var selected = HistorySelection.toggle(emptySet(), 11L)
        selected shouldBe setOf(11L)
        // Shift + click below the last selected row takes the rows in between
        selected = HistorySelection.range(ids, selected, 13L)
        selected shouldBe setOf(11L, 12L, 13L)
        // and above it, from the last selected row up
        HistorySelection.range(ids, setOf(13L), 10L) shouldBe setOf(10L, 11L, 12L, 13L)
        // with nothing selected, it takes the clicked row alone
        HistorySelection.range(ids, emptySet(), 12L) shouldBe setOf(12L)
        HistorySelection.invert(ids, selected) shouldBe setOf(10L, 14L)
        HistorySelection.toggle(selected, 12L) shouldBe setOf(11L, 13L)
    }
}
