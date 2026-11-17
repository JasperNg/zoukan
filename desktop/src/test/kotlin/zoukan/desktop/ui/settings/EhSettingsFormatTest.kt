package zoukan.desktop.ui.settings

import exh.source.ExhPreferences
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

/**
 * The E-Hentai language and front-page category settings keep Android's format (backups carry
 * them between the apps), and a short or broken value no longer breaks the dialogs.
 */
class EhSettingsFormatTest {
    private val defaults = ExhPreferences(InMemoryPreferenceStore())

    @Test
    fun `Android's defaults read as all off and write back unchanged`() {
        val languages = defaults.exhSettingsLanguages().get()
        EhSettingsFormat.parseLanguages(languages).flatten().any { it } shouldBe false
        EhSettingsFormat.formatLanguages(EhSettingsFormat.parseLanguages(languages)) shouldBe languages

        val categories = defaults.exhEnabledCategories().get()
        EhSettingsFormat.parseShownCategories(categories) shouldBe List(10) { true }
        EhSettingsFormat.formatShownCategories(EhSettingsFormat.parseShownCategories(categories)) shouldBe categories
    }

    @Test
    fun `a language filter is written as Android's original-translated-rewrite lines`() {
        val rows = EhSettingsFormat.parseLanguages(defaults.exhSettingsLanguages().get()).map { it.toMutableList() }
        // English translated, Chinese rewrite
        rows[1][1] = true
        rows[2][2] = true
        val lines = EhSettingsFormat.formatLanguages(rows).split("\n")
        lines.size shouldBe 17
        lines[1] shouldBe "false*true*false"
        lines[2] shouldBe "false*false*true"
        lines.filterIndexed { i, _ -> i != 1 && i != 2 }.all { it == "false*false*false" } shouldBe true
    }

    @Test
    fun `Japanese has no original filter, as on Android`() {
        val rows = EhSettingsFormat.parseLanguages("true*true*false")
        rows[0] shouldBe listOf(false, true, false)
        EhSettingsFormat.formatLanguages(listOf(listOf(true, false, true))).lines().first() shouldBe "false*false*true"
    }

    @Test
    fun `a short or broken languages value reads as off and is written back whole`() {
        val rows = EhSettingsFormat.parseLanguages("false*true\nnonsense")
        rows.size shouldBe 17
        rows.all { it.size == 3 } shouldBe true
        rows[0] shouldBe listOf(false, true, false)
        rows[1] shouldBe listOf(false, false, false)
        EhSettingsFormat.formatLanguages(rows).lines().size shouldBe 17
        EhSettingsFormat.parseLanguages("").size shouldBe 17
    }

    @Test
    fun `front page categories store whether each is hidden`() {
        val shown = MutableList(10) { true }
        // Doujinshi and Misc hidden
        shown[0] = false
        shown[9] = false
        EhSettingsFormat.formatShownCategories(shown) shouldBe "true,false,false,false,false,false,false,false,false,true"
        EhSettingsFormat.parseShownCategories("true,false") shouldBe listOf(false) + List(9) { true }
        EhSettingsFormat.formatShownCategories(EhSettingsFormat.parseShownCategories("true,false")).split(",").size shouldBe 10
    }
}
