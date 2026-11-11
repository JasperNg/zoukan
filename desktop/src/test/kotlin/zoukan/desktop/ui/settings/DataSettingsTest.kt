package zoukan.desktop.ui.settings

import eu.kanade.domain.source.service.SourcePreferences
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.domain.manga.model.Manga
import xyz.nulldev.androidcompat.io.sharedprefs.JavaSharedPreferences
import zoukan.desktop.ui.browse.deleteSourceCategory
import zoukan.desktop.ui.browse.renameSourceCategory

class DataSettingsTest {

    private fun manga(title: String, author: String?, artist: String?) =
        Manga.create().copy(ogTitle = title, ogAuthor = author, ogArtist = artist)

    @Test
    fun `library list is the Android exporter's CSV`() {
        val favorites = listOf(
            manga("Plain", "Ann", "Bo"),
            manga("Comma, title", "Quote \"A\"", null),
            manga("Two\nlines", "", "Cy"),
        )
        LibraryCsv.generate(favorites, LibraryCsv.Options(title = true, author = true, artist = true)) shouldBe
            "Plain,Ann,Bo\r\n\"Comma, title\",\"Quote \"\"A\"\"\",\r\n\"Two\nlines\",,Cy"
        LibraryCsv.generate(favorites.take(1), LibraryCsv.Options(title = true, author = false, artist = true)) shouldBe "Plain,Bo"
    }

    @Test
    fun `sizes read as Android's formatter`() {
        formatSize(999) shouldBe "999 B"
        formatSize(1_500) shouldBe "%.2f kB".format(1.5)
        formatSize(250_000_000) shouldBe "250 MB"
    }

    @Test
    fun `renaming and deleting a source category updates its sources`() {
        val preferences = SourcePreferences(DesktopPreferenceStore(JavaSharedPreferences("source_categories_test_${System.nanoTime()}")))
        preferences.sourcesTabCategories().set(setOf("A", "B"))
        preferences.sourcesTabSourcesInCategories().set(setOf("1|A", "2|A", "2|B"))

        renameSourceCategory(preferences, "A", "C")
        preferences.sourcesTabCategories().get() shouldBe setOf("B", "C")
        preferences.sourcesTabSourcesInCategories().get() shouldBe setOf("1|C", "2|C", "2|B")

        deleteSourceCategory(preferences, "C")
        preferences.sourcesTabCategories().get() shouldBe setOf("B")
        preferences.sourcesTabSourcesInCategories().get() shouldBe setOf("2|B")
    }
}
