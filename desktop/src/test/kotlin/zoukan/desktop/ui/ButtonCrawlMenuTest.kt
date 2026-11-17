package zoukan.desktop.ui

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** How the button crawl tells that a click opened a menu ([ButtonCrawl.openedMenu]). */
class ButtonCrawlMenuTest {
    private val entryMenu = setOf("\"Open\"", "\"Select\"", "\"Mark as read\"", "\"Download\"", "\"Migrate\"")
    private val downloadChoices = setOf("\"Next chapter\"", "\"Next 5 chapters\"", "\"Unread\"")

    @Test
    fun `a new popup over the window is a menu`() {
        ButtonCrawl.openedMenu(before = emptyList(), after = listOf(entryMenu)) shouldBe true
    }

    @Test
    fun `a submenu shown in place of its menu is a menu`() {
        ButtonCrawl.openedMenu(before = listOf(entryMenu), after = listOf(downloadChoices)) shouldBe true
    }

    @Test
    fun `a new popup inside a menu is a menu`() {
        ButtonCrawl.openedMenu(before = listOf(entryMenu), after = listOf(entryMenu, downloadChoices)) shouldBe true
    }

    @Test
    fun `a menu that closed opens nothing`() {
        ButtonCrawl.openedMenu(before = listOf(entryMenu), after = emptyList()) shouldBe false
    }

    @Test
    fun `an item that changes only its own state opens nothing`() {
        ButtonCrawl.openedMenu(before = listOf(entryMenu), after = listOf(entryMenu)) shouldBe false
        // A checkbox item whose neighbour changed its label: most items stay
        val relabelled = entryMenu - "\"Select\"" + "\"Deselect\""
        ButtonCrawl.openedMenu(before = listOf(entryMenu), after = listOf(relabelled)) shouldBe false
    }

    @Test
    fun `an empty menu is not a new one`() {
        ButtonCrawl.openedMenu(before = listOf(entryMenu), after = listOf(emptySet())) shouldBe false
        ButtonCrawl.openedMenu(before = emptyList(), after = emptyList()) shouldBe false
    }
}
