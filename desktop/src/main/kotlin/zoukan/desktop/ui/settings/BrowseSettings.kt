package zoukan.desktop.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.ui.UiPreferences
import mihon.domain.extension.interactor.GetExtensionStores
import tachiyomi.i18n.MR
import tachiyomi.i18n.action_edit_categories
import tachiyomi.i18n.extensionStores
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.kmk.pref_source_related_mangas
import tachiyomi.i18n.kmk.pref_source_related_mangas_summary
import tachiyomi.i18n.label_sources
import tachiyomi.i18n.parental_controls_info
import tachiyomi.i18n.pref_category_nsfw_content
import tachiyomi.i18n.pref_hide_in_library_items
import tachiyomi.i18n.pref_show_nsfw_source
import tachiyomi.i18n.requires_app_restart
import tachiyomi.i18n.sy.SYMR
import tachiyomi.i18n.sy.feed
import tachiyomi.i18n.sy.pref_feed_position
import tachiyomi.i18n.sy.pref_feed_position_summery
import tachiyomi.i18n.sy.pref_hide_feed
import tachiyomi.i18n.sy.pref_local_source_hidden_folders
import tachiyomi.i18n.sy.pref_local_source_hidden_folders_summery
import tachiyomi.i18n.sy.pref_source_navigation
import tachiyomi.i18n.sy.pref_source_navigation_summery
import tachiyomi.i18n.sy.pref_source_source_filtering
import tachiyomi.i18n.sy.pref_source_source_filtering_summery
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.compat.localized
import zoukan.desktop.compat.localizedPlural
import zoukan.desktop.ui.browse.BrowseScreenModel
import zoukan.desktop.ui.browse.SourceCategoryScreen
import zoukan.desktop.ui.browse.StoresList
import zoukan.desktop.ui.common.ScreenHeader
import zoukan.desktop.ui.common.Spacing
import zoukan.desktop.ui.common.collectAsState
import zoukan.i18n.desktop.DMR
import zoukan.i18n.desktop.categories
import zoukan.i18n.desktop.expand_related_titles
import zoukan.i18n.desktop.n_1_store
import zoukan.i18n.desktop.only_show_related_titles_from_the
import zoukan.i18n.desktop.related_titles_in_overflow_menu
import zoukan.i18n.desktop.show_the_related_titles_row_open
import zoukan.i18n.desktop.stores_2

/**
 * Settings > Browse, as the Android app's SettingsBrowseScreen: sources (suggestions, source
 * categories, the latest button, local hidden folders), the feed, hiding library entries,
 * extension stores and NSFW sources.
 *
 * Android's related-titles options lay out a related-titles row inside the entry screen. The
 * desktop shows "Expand related titles" and "Related titles in the overflow menu" for that row.
 * "Show home on related titles" is not shown: the desktop lists related titles from the entry's
 * More > Similar titles instead (see PARITY.md).
 */
fun LazyListScope.browseSettings() {
    settingsGroup(localized(MR.strings.label_sources))
    item(key = "browse-suggestions") {
        val preferences = remember { Injekt.get<SourcePreferences>() }
        SwitchPreference(localized(KMR.strings.pref_source_related_mangas), preferences.relatedMangas(), localized(KMR.strings.pref_source_related_mangas_summary))
    }
    // ZOUKAN -->
    item(key = "browse-related-expand") {
        val preferences = remember { Injekt.get<eu.kanade.domain.ui.UiPreferences>() }
        SwitchPreference(localized(DMR.strings.expand_related_titles), preferences.expandRelatedMangas(), localized(DMR.strings.show_the_related_titles_row_open))
    }
    item(key = "browse-related-overflow") {
        val preferences = remember { Injekt.get<eu.kanade.domain.ui.UiPreferences>() }
        SwitchPreference(localized(DMR.strings.related_titles_in_overflow_menu), preferences.relatedMangasInOverflow(), localized(DMR.strings.only_show_related_titles_from_the))
    }
    // ZOUKAN <--
    item(key = "browse-categories") {
        val preferences = remember { Injekt.get<SourcePreferences>() }
        val navigator = LocalNavigator.currentOrThrow
        val categories by preferences.sourcesTabCategories().collectAsState()
        SettingRow(
            localized(MR.strings.action_edit_categories),
            localizedPlural(DMR.plurals.categories, categories.size, categories.size),
            onClick = { navigator.push(SourceCategoryScreen) },
        )
    }
    item(key = "browse-categories-filter") {
        val preferences = remember { Injekt.get<SourcePreferences>() }
        SwitchPreference(
            localized(SYMR.strings.pref_source_source_filtering),
            preferences.sourcesTabCategoriesFilter(),
            localized(SYMR.strings.pref_source_source_filtering_summery),
        )
    }
    item(key = "browse-navigation") {
        val preferences = remember { Injekt.get<UiPreferences>() }
        SwitchPreference(
            localized(SYMR.strings.pref_source_navigation),
            preferences.useNewSourceNavigation(),
            localized(SYMR.strings.pref_source_navigation_summery),
        )
    }
    item(key = "browse-local-hidden") {
        val preferences = remember { Injekt.get<SourcePreferences>() }
        SwitchPreference(
            localized(SYMR.strings.pref_local_source_hidden_folders),
            preferences.allowLocalSourceHiddenFolders(),
            localized(SYMR.strings.pref_local_source_hidden_folders_summery),
        )
    }

    settingsGroup(localized(SYMR.strings.feed))
    item(key = "browse-hide-feed") {
        val preferences = remember { Injekt.get<UiPreferences>() }
        SwitchPreference(localized(SYMR.strings.pref_hide_feed), preferences.hideFeedTab())
    }
    item(key = "browse-feed-position") {
        val preferences = remember { Injekt.get<UiPreferences>() }
        val hidden by preferences.hideFeedTab().collectAsState()
        val value by preferences.feedTabInFront().collectAsState()
        SwitchSetting(
            localized(SYMR.strings.pref_feed_position),
            localized(SYMR.strings.pref_feed_position_summery),
            checked = value,
            enabled = !hidden,
        ) { preferences.feedTabInFront().set(it) }
    }
    item(key = "browse-feed-hide-library") {
        val preferences = remember { Injekt.get<SourcePreferences>() }
        SwitchPreference(localized(MR.strings.pref_hide_in_library_items), preferences.hideInLibraryFeedItems())
    }

    // Android has a second "Sources" group here; its own id keeps the list keys apart
    settingsGroup(localized(MR.strings.label_sources), id = "browse-sources-library")
    item(key = "browse-hide-library") {
        val preferences = remember { Injekt.get<SourcePreferences>() }
        SwitchPreference(localized(MR.strings.pref_hide_in_library_items), preferences.hideInLibraryItems())
    }
    item(key = "browse-stores") {
        val navigator = LocalNavigator.currentOrThrow
        val stores by remember { Injekt.get<GetExtensionStores>().subscribe() }.collectAsState(emptyList())
        SettingRow(
            localized(MR.strings.extensionStores),
            if (stores.size == 1) localized(DMR.strings.n_1_store) else localized(DMR.strings.stores_2, stores.size),
            onClick = { navigator.push(ExtensionStoresScreen) },
        )
    }

    settingsGroup(localized(MR.strings.pref_category_nsfw_content))
    item(key = "browse-nsfw") {
        val preferences = remember { Injekt.get<SourcePreferences>() }
        // Installed NSFW extensions load (or stop loading) on the next start, as in Komikku
        SwitchPreference(
            localized(MR.strings.pref_show_nsfw_source),
            preferences.showNsfwSource(),
            subtitle = localized(MR.strings.requires_app_restart),
        )
    }
    item(key = "browse-nsfw-info") {
        InfoText(
            localized(MR.strings.parental_controls_info),
        )
    }
}

/** Android's InfoPreference: a note under a group's settings. */
@Composable
fun InfoText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.SettingsRowInset, vertical = 8.dp),
    )
}

/** Android's Settings > Browse > Extension stores, the same list as Browse > Stores. */
object ExtensionStoresScreen : Screen {
    private fun readResolve(): Any = ExtensionStoresScreen

    override val key: ScreenKey = "extension-stores"

    @Composable
    override fun Content() {
        val model = rememberScreenModel { BrowseScreenModel() }
        val state by model.state.collectAsState()
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(localized(MR.strings.extensionStores))
            StoresList(state, model)
        }
    }
}
