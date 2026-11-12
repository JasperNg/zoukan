package zoukan.desktop.ui.browse

import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.source.CatalogueSource
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

/** KMK's search box over the Sources list, as SourcesScreenModel's queryFilter. */
class SourceSearchTest {

    private fun source(name: String, id: Long): CatalogueSource = mockk {
        every { this@mockk.name } returns name
        every { this@mockk.id } returns id
    }

    private val alpha = source("Alpha Comics", 11)
    private val bravo = source("Bravo Scans", 22)
    private val charlie = source("Charlie", 33)

    private val extension: Extension.Installed = mockk {
        every { name } returns "Tachiyomi: Delta"
        every { sources } returns listOf(charlie)
    }

    private fun shown(query: String) = BrowseScreenModel.State(
        sections = listOf("Pinned" to listOf(alpha), "English" to listOf(bravo, charlie)),
        installed = listOf(extension),
        sourceQuery = query,
    ).visibleSections.map { (title, list) -> title to list.map { it.name } }

    @Test
    fun `a blank query shows every section`() {
        shown("  ") shouldBe listOf("Pinned" to listOf("Alpha Comics"), "English" to listOf("Bravo Scans", "Charlie"))
    }

    @Test
    fun `matches the name ignoring case and drops empty sections`() {
        shown("SCANS") shouldBe listOf("English" to listOf("Bravo Scans"))
    }

    @Test
    fun `comma-separated terms match any, with the extension name and the id`() {
        shown("delta, 11") shouldBe listOf("Pinned" to listOf("Alpha Comics"), "English" to listOf("Charlie"))
        shown("nothing") shouldBe emptyList()
    }
}
