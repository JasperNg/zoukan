package zoukan.desktop.ui.history

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.history.service.HistoryPreferences
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

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

    private fun row(id: Long, readAt: Long): HistoryWithRelations = mockk {
        every { this@mockk.id } returns id
        every { chapterId } returns id
        every { this@mockk.readAt } returns Date(readAt)
    }

    @Test
    fun `the grouped history is built once per state`() {
        val noon = LocalDate.of(2026, 3, 15).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val dayBefore = noon - 24 * 60 * 60 * 1000L
        val rows = (0L until 2000L).map { row(it, if (it < 1000) noon + it else dayBefore + it) }
        val state = HistoryScreenModel.State(loading = false, items = rows)

        (state.groups === state.groups) shouldBe true
        state.groups.map { it.first } shouldBe listOf(LocalDate.of(2026, 3, 15), LocalDate.of(2026, 3, 14))
        state.groups.map { it.second.size } shouldBe listOf(1000, 1000)
    }
}
