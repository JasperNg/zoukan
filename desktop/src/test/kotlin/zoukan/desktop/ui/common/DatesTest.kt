package zoukan.desktop.ui.common

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.category.model.Category
import zoukan.desktop.ui.settings.categoriesLabel
import java.time.LocalDate

class DatesTest {
    private val today = LocalDate.of(2026, 11, 10)

    @Test
    fun `relative dates read as Android's within a week`() {
        Dates.relative(today, relative = true, today = today) shouldBe "Today"
        Dates.relative(today.minusDays(1), relative = true, today = today) shouldBe "Yesterday"
        Dates.relative(today.minusDays(3), relative = true, today = today) shouldBe "3 days ago"
        Dates.relative(today.plusDays(1), relative = true, today = today) shouldBe "Tomorrow"
        Dates.relative(today.plusDays(4), relative = true, today = today) shouldBe "In 4 days"
    }

    @Test
    fun `dates outside a week and with relative off use the date format`() {
        Dates.format(today, "yyyy-MM-dd") shouldBe "2026-11-10"
        Dates.format(today.minusDays(30), "dd/MM/yy") shouldBe "11/10/26"
    }

    @Test
    fun `category labels read as Android's`() {
        val categories = listOf(category(0, "Default", 0), category(1, "Reading", 1), category(2, "Done", 2))
        categoriesLabel(categories, emptySet(), emptySet()) shouldBe "Include: All\nExclude: None"
        categoriesLabel(categories, setOf("1"), setOf("2")) shouldBe "Include: Reading\nExclude: Done"
        categoriesLabel(categories, setOf("0", "1", "2"), emptySet()) shouldBe "Include: All\nExclude: None"
        categoriesLabel(categories, emptySet(), setOf("0", "1", "2")) shouldBe "Include: None\nExclude: All"
    }

    private fun category(id: Long, name: String, order: Long) = Category(id = id, name = name, order = order, flags = 0, hidden = false)
}
