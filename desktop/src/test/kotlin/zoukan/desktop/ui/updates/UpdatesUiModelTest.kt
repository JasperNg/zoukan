package zoukan.desktop.ui.updates

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import tachiyomi.domain.updates.model.UpdatesWithRelations
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class UpdatesUiModelTest {

    private val today = LocalDate.of(2026, 11, 11)

    private fun at(date: LocalDate, hour: Int) = date.atTime(LocalTime.of(hour, 0)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    // UpdatesWithRelations reads custom titles through Injekt, so a mock
    private fun update(mangaId: Long, chapterId: Long, dateFetch: Long) = mockk<UpdatesWithRelations> {
        every { this@mockk.mangaId } returns mangaId
        every { this@mockk.chapterId } returns chapterId
        every { this@mockk.dateFetch } returns dateFetch
    }

    private fun List<UpdatesUiModel>.describe() = map {
        when (it) {
            is UpdatesUiModel.Header -> "H ${it.date} ${it.mangaCount}"
            is UpdatesUiModel.Leader -> "L ${it.item.chapterId}${if (it.isExpandable) "+" else ""}"
            is UpdatesUiModel.Item -> "I ${it.item.chapterId}"
        }
    }

    @Test
    fun `a day's chapters of one entry fold under the first one, as KMK`() {
        val yesterday = today.minusDays(1)
        val items = listOf(
            update(mangaId = 1, chapterId = 13, dateFetch = at(today, 20)),
            update(mangaId = 2, chapterId = 21, dateFetch = at(today, 19)),
            update(mangaId = 1, chapterId = 12, dateFetch = at(today, 18)),
            update(mangaId = 1, chapterId = 11, dateFetch = at(today, 10)),
            update(mangaId = 1, chapterId = 10, dateFetch = at(yesterday, 10)),
        )
        val model = UpdatesScreenModel.State(items = items).getUiModel()

        // The header counts the day's chapters; an entry's chapters move up under its leader
        model.describe() shouldBe listOf(
            "H $today 4",
            "L 13+",
            "I 12",
            "I 11",
            "L 21",
            "H $yesterday 1",
            "L 10",
        )
        model[1].shouldBeInstanceOf<UpdatesUiModel.Leader>()
        (model[2] as UpdatesUiModel.Item).isExpandable shouldBe true
    }

    @Test
    fun `the group key is the day and the entry`() {
        val first = update(mangaId = 7, chapterId = 1, dateFetch = at(today, 8))
        val second = update(mangaId = 7, chapterId = 2, dateFetch = at(today, 22))
        val nextDay = update(mangaId = 7, chapterId = 3, dateFetch = at(today.plusDays(1), 8))

        first.groupByDateAndManga() shouldBe "${today.toEpochDay()}-7"
        second.groupByDateAndManga() shouldBe first.groupByDateAndManga()
        (nextDay.groupByDateAndManga() == first.groupByDateAndManga()) shouldBe false
    }
}
