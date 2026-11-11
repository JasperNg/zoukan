package zoukan.desktop.ui.settings

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SettingsSearchTest {
    @Test
    fun `every word of the search is in the title or summary, ignoring case`() {
        val texts = listOf("Create backup", "Backs up the library, categories and settings")
        SettingsSearch.matches("backup", texts) shouldBe true
        SettingsSearch.matches("CREATE library", texts) shouldBe true
        SettingsSearch.matches("  library   create ", texts) shouldBe true
        SettingsSearch.matches("restore", texts) shouldBe false
        SettingsSearch.matches("create restore", texts) shouldBe false
    }

    @Test
    fun `a blank search matches nothing`() {
        SettingsSearch.matches("  ", listOf("Anything")) shouldBe false
        SettingsSearch.matches("theme", emptyList()) shouldBe false
    }

    @Test
    fun `items without a key are found by their place, the same in search and on the page`() {
        SettingsSearch.itemId("data-export", 3) shouldBe "data-export"
        SettingsSearch.itemId(null, 3) shouldBe "#3"
    }
}
