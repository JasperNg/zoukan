package zoukan.desktop.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.compat.localized
import zoukan.desktop.testkit.TestPreferenceStore
import zoukan.i18n.desktop.DMR

class SettingsSearchIndexTest {

    private class RecordedKeys : LazyListScope {
        val keys = mutableSetOf<Any>()

        override fun item(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) {
            if (key != null) keys += key
        }

        override fun items(
            count: Int,
            key: ((index: Int) -> Any)?,
            contentType: (index: Int) -> Any?,
            itemContent: @Composable LazyItemScope.(index: Int) -> Unit,
        ) {
            repeat(count) { i -> key?.invoke(i)?.let { keys += it } }
        }

        @ExperimentalFoundationApi
        override fun stickyHeader(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) {
            if (key != null) keys += key
        }
    }

    private fun keysFor(build: LazyListScope.() -> Unit): Set<Any> = RecordedKeys().apply(build).keys

    @Test
    fun `declarative index covers every category and omits MangaDex only when disabled`() {
        val full = SettingsSearch.index(includeMangaDex = true, trackers = listOf(1L to "MyAnimeList"))
        full.map { it.category }.toSet() shouldContainAll SettingsCategory.entries
        full.all { it.title.isNotBlank() && it.breadcrumb.isNotBlank() && it.itemKey != null } shouldBe true

        val withoutMdex = SettingsSearch.index(includeMangaDex = false)
        withoutMdex.map { it.category }.toSet() shouldNotContain SettingsCategory.MangaDex
    }

    @Test
    fun `every indexed itemKey exists on its target settings page`() {
        val index = SettingsSearch.index(includeMangaDex = true)
        val readerKeys = keysFor { readerBehaviourSettings(DesktopPreferences(TestPreferenceStore())) } + setOf(
            "reader-default-mode",
            "reader-image-scale-type",
            "reader-theme",
            "reader-webtoon-side-padding",
            "reader-show-page-number",
        )
        val downloadsKeys = keysFor { downloadBehaviorSettings(emptyList()) } + setOf(
            "downloads-cbz",
            "downloads-split-tall",
            "downloads-concurrent-sources",
            "downloads-concurrent-pages",
            "downloads-password-protect",
            "downloads-set-password",
            "downloads-encryption",
        )
        val advancedKeys = keysFor {
            shortcutSettings()
            advancedSettings()
        } + "advanced-tray"
        val pageKeys = mapOf(
            SettingsCategory.Appearance to keysFor { appearanceSettings() },
            SettingsCategory.Library to (keysFor { librarySettings(emptyList()) } + "library-edit-categories"),
            SettingsCategory.Reader to readerKeys,
            SettingsCategory.Downloads to downloadsKeys,
            SettingsCategory.Browse to keysFor { browseSettings() },
            SettingsCategory.Data to keysFor {
                dataSettings()
                syncSettings()
                dataSaverSettings()
            },
            SettingsCategory.Security to keysFor { securitySettings() },
            SettingsCategory.EHentai to keysFor { ehSettings() },
            SettingsCategory.Advanced to advancedKeys,
            SettingsCategory.About to keysFor { aboutSettings() },
        )

        pageKeys.forEach { (category, keys) ->
            val indexedKeys = index.filter { it.category == category }.mapNotNull { it.itemKey }
            indexedKeys.shouldNotBeEmpty()
            indexedKeys.filterNot { it in keys } shouldBe emptyList()
        }
    }

    @Test
    fun `search matches titles, summaries, choice keywords, reader settings, and trackers`() {
        val index = SettingsSearch.index(
            includeMangaDex = true,
            trackers = listOf(2L to "AniList"),
        )

        SettingsSearch.search("", index).shouldBeEmpty()
        SettingsSearch.search("   ", index).shouldBeEmpty()
        SettingsSearch.search("xyznonexistent999", index).shouldBeEmpty()

        // Choice keyword
        val quad9 = SettingsSearch.search("quad9", index)
        quad9.map { it.itemKey } shouldContain "advanced-doh"

        // Multi-word across title + summary, ignoring case
        val cbz = SettingsSearch.search("save cbz", index)
        cbz.map { it.itemKey } shouldContain "downloads-cbz"

        // Reader behavior setting from ReaderSettingRows
        val cropBorders = SettingsSearch.search(localized(MR.strings.pref_crop_borders), index)
        cropBorders.map { it.category } shouldContain SettingsCategory.Reader
        cropBorders.map { it.itemKey } shouldContain "reader-crop_borders"

        // Dynamic tracker
        val anilist = SettingsSearch.search("anilist", index)
        anilist.map { it.category } shouldContain SettingsCategory.Tracking
        anilist.map { it.itemKey } shouldContain "tracker-2"

        // Category filtering
        val disabledMdex = SettingsSearch.search(
            localized(SYMR.strings.mangadex_sync_follows_to_library),
            index,
            enabledCategories = setOf(SettingsCategory.Appearance, SettingsCategory.Library),
        )
        disabledMdex.shouldBeEmpty()
    }

    @Test
    fun `SettingsScreenModel scopes navigation, search, and jump-to-page highlight per instance`() {
        val first = SettingsScreenModel(
            isMangaDexInstalled = { false },
            trackersProvider = { listOf(2L to "AniList") },
        )
        val second = SettingsScreenModel(
            isMangaDexInstalled = { true },
            trackersProvider = { emptyList() },
        )

        first.state.value.selectedCategory shouldBe SettingsCategory.Appearance
        first.state.value.pages shouldNotContain SettingsCategory.MangaDex
        second.state.value.pages shouldContain SettingsCategory.MangaDex

        // Searching and jumping to a page in first does not leak into second
        first.setSearchQuery(localized(KMR.strings.web_dav))
        first.state.value.searchResults.shouldNotBeEmpty()
        second.state.value.searchQuery shouldBe ""
        second.state.value.searchResults.shouldBeEmpty()

        val webDavResult = first.state.value.searchResults.first { it.category == SettingsCategory.Data }
        first.openSearchResult(webDavResult)

        first.state.value.selectedCategory shouldBe SettingsCategory.Data
        first.state.value.searchQuery shouldBe ""
        first.state.value.searchResults.shouldBeEmpty()
        first.state.value.highlightedItem shouldBe (SettingsCategory.Data to webDavResult.itemKey!!)
        second.state.value.selectedCategory shouldBe SettingsCategory.Appearance
        second.state.value.highlightedItem shouldBe null

        first.clearHighlight()
        first.state.value.highlightedItem shouldBe null

        // Selecting a category clears an active search
        first.setSearchQuery(localized(DMR.strings.use_japanese_titles))
        first.state.value.searchResults.shouldNotBeEmpty()
        first.selectCategory(SettingsCategory.About)
        first.state.value.selectedCategory shouldBe SettingsCategory.About
        first.state.value.searchQuery shouldBe ""
        first.state.value.searchResults.shouldBeEmpty()
    }

    @Test
    fun `openSettingsCategory updates an active SettingsScreenModel or queues one-shot pending category`() {
        val pendingTarget = SettingsScreenModel(isMangaDexInstalled = { false })
        openSettingsCategory(SettingsCategory.Security)
        // Consumed by the next model created
        val opened = SettingsScreenModel(isMangaDexInstalled = { false })
        opened.state.value.selectedCategory shouldBe SettingsCategory.Security
        // Not leaked to subsequent models
        val next = SettingsScreenModel(isMangaDexInstalled = { false })
        next.state.value.selectedCategory shouldBe SettingsCategory.Appearance

        // Active model receives openSettingsCategory directly
        SettingsScreenModel.register(pendingTarget)
        try {
            openSettingsCategory(SettingsCategory.Advanced)
            pendingTarget.state.value.selectedCategory shouldBe SettingsCategory.Advanced
        } finally {
            SettingsScreenModel.unregister(pendingTarget)
        }
    }
}
