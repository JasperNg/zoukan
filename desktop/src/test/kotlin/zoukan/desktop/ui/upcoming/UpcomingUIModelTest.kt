package zoukan.desktop.ui.upcoming

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga
import zoukan.desktop.ui.upcoming.UpcomingScreenModel.Companion.headerIndexes
import zoukan.desktop.ui.upcoming.UpcomingScreenModel.Companion.toEvents
import zoukan.desktop.ui.upcoming.UpcomingScreenModel.Companion.toUpcomingUIModels
import java.time.LocalDate
import java.time.ZoneId

class UpcomingUIModelTest {

    private val today = LocalDate.of(2026, 11, 10)

    private fun manga(id: Long, day: LocalDate) = Manga.create().copy(
        id = id,
        ogTitle = "M$id",
        nextUpdate = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() + 3_600_000,
    )

    @Test
    fun `groups entries under a header per day with its count, as Android does`() {
        val a = manga(1, today)
        val b = manga(2, today.plusDays(1))
        val c = manga(3, today.plusDays(1))
        val d = manga(4, today.plusDays(5))
        val models = listOf(a, b, c, d).toUpcomingUIModels()

        models shouldBe listOf(
            UpcomingUIModel.Header(today, 1),
            UpcomingUIModel.Item(a),
            UpcomingUIModel.Header(today.plusDays(1), 2),
            UpcomingUIModel.Item(b),
            UpcomingUIModel.Item(c),
            UpcomingUIModel.Header(today.plusDays(5), 1),
            UpcomingUIModel.Item(d),
        )
        models.toEvents() shouldBe mapOf(today to 1, today.plusDays(1) to 2, today.plusDays(5) to 1)
        models.headerIndexes() shouldBe mapOf(today to 0, today.plusDays(1) to 2, today.plusDays(5) to 5)
    }

    @Test
    fun `list keys stay unique where header hash codes collide`() {
        // Same month, counts 31 apart per day of gap: the data class hash codes are equal, which
        // as LazyColumn keys crashed the window with "Key was already used"
        val first = UpcomingUIModel.Header(today, 32)
        val second = UpcomingUIModel.Header(today.plusDays(1), 1)
        first.hashCode() shouldBe second.hashCode()
        (first.listKey() == second.listKey()) shouldBe false

        val models = (1L..32L).map { manga(it, today) }.plus(manga(33, today.plusDays(1))).toUpcomingUIModels()
        models.filterIsInstance<UpcomingUIModel.Header>() shouldBe listOf(first, second)
        models.map { it.listKey() }.toSet().size shouldBe models.size
    }

    @Test
    fun `relative dates follow Android's wording`() {
        relativeDateText(today, today) shouldBe "Today"
        relativeDateText(today.plusDays(1), today) shouldBe "Tomorrow"
        relativeDateText(today.plusDays(3), today) shouldBe "In 3 days"
        relativeDateText(today.minusDays(1), today) shouldBe "Yesterday"
        relativeDateText(today.minusDays(4), today) shouldBe "4 days ago"
    }
}
