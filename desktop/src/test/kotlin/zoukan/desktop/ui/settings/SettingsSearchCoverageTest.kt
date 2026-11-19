package zoukan.desktop.ui.settings

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import org.junit.jupiter.api.Test
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import zoukan.desktop.compat.localized
import zoukan.i18n.desktop.DMR

/** What the declarative settings search index can find: choice labels and settings behind toggles. */
class SettingsSearchCoverageTest {

    @Test
    fun `a choice setting is found by any of its choices`() {
        val index = SettingsSearch.index()
        val nord = SettingsSearch.search(localized(MR.strings.theme_nord), index)
        nord.shouldNotBeEmpty()
        nord.map { it.title } shouldContain localized(MR.strings.pref_app_theme)

        val webDav = SettingsSearch.search(localized(KMR.strings.web_dav), index)
        webDav.shouldNotBeEmpty()
        webDav.map { it.title } shouldContain localized(DMR.strings.sync_service)
    }

    @Test
    fun `settings behind conditional toggles remain searchable in the declarative index`() {
        val index = SettingsSearch.index()
        val japaneseTitles = SettingsSearch.search(localized(DMR.strings.use_japanese_titles), index)
        japaneseTitles.map { it.category } shouldContain SettingsCategory.EHentai

        val lockDays = SettingsSearch.search(localized(DMR.strings.lock_on_these_days), index)
        lockDays.map { it.category } shouldContain SettingsCategory.Security
    }
}
