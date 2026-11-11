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
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.ui.browse.BrowseScreenModel
import zoukan.desktop.ui.browse.SourceCategoryScreen
import zoukan.desktop.ui.browse.StoresList
import zoukan.desktop.ui.common.ScreenHeader

/**
 * Settings > Browse, as the Android app's SettingsBrowseScreen: sources (suggestions, source
 * categories, the latest button, local hidden folders), the feed, hiding library entries,
 * extension stores and NSFW sources.
 *
 * Android's "Expand related titles", "Related titles in the overflow menu" and "Show home on
 * related titles" lay out a related-titles row inside the entry screen; the desktop shows related
 * titles from the entry's More > Similar titles, so they are not shown (see PARITY.md).
 */
fun LazyListScope.browseSettings() {
    settingsGroup("Sources")
    item(key = "browse-suggestions") {
        val preferences = remember { Injekt.get<SourcePreferences>() }
        SwitchPreference("Suggestions", preferences.relatedMangas(), "Show source-website's Suggestions while viewing entry")
    }
    // ZOUKAN -->
    item(key = "browse-related-expand") {
        val preferences = remember { Injekt.get<eu.kanade.domain.ui.UiPreferences>() }
        SwitchPreference("Expand related titles", preferences.expandRelatedMangas(), "Show the related titles row open on the entry page")
    }
    item(key = "browse-related-overflow") {
        val preferences = remember { Injekt.get<eu.kanade.domain.ui.UiPreferences>() }
        SwitchPreference("Related titles in overflow menu", preferences.relatedMangasInOverflow(), "Only show related titles from the entry's More menu")
    }
    // ZOUKAN <--
    item(key = "browse-categories") {
        val preferences = remember { Injekt.get<SourcePreferences>() }
        val navigator = LocalNavigator.currentOrThrow
        val categories by preferences.sourcesTabCategories().changes().collectAsState(preferences.sourcesTabCategories().get())
        SettingRow(
            "Edit categories",
            if (categories.size == 1) "1 category" else "${categories.size} categories",
            onClick = { navigator.push(SourceCategoryScreen) },
        )
    }
    item(key = "browse-categories-filter") {
        val preferences = remember { Injekt.get<SourcePreferences>() }
        SwitchPreference(
            "Filter sources in categories",
            preferences.sourcesTabCategoriesFilter(),
            "Filter the sources that are in categories, making the sources not get put under the language if they are in a category",
        )
    }
    item(key = "browse-navigation") {
        val preferences = remember { Injekt.get<UiPreferences>() }
        SwitchPreference(
            "Replace latest button",
            preferences.useNewSourceNavigation(),
            "Replace latest button with a custom browse view that includes both latest and browse",
        )
    }
    item(key = "browse-local-hidden") {
        val preferences = remember { Injekt.get<SourcePreferences>() }
        SwitchPreference(
            "Local source hidden folders",
            preferences.allowLocalSourceHiddenFolders(),
            "Allow local source to read hidden folders",
        )
    }

    settingsGroup("Feed")
    item(key = "browse-hide-feed") {
        val preferences = remember { Injekt.get<UiPreferences>() }
        SwitchPreference("Hide Feed tab", preferences.hideFeedTab())
    }
    item(key = "browse-feed-position") {
        val preferences = remember { Injekt.get<UiPreferences>() }
        val hidden by preferences.hideFeedTab().changes().collectAsState(preferences.hideFeedTab().get())
        val value by preferences.feedTabInFront().changes().collectAsState(preferences.feedTabInFront().get())
        SwitchSetting(
            "Feed tab position",
            "Do you want the feed tab to be the first tab in browse? This will make it the default tab when opening browse, " +
                "not recommended if you're on data or a metered network",
            checked = value,
            enabled = !hidden,
        ) { preferences.feedTabInFront().set(it) }
    }
    item(key = "browse-feed-hide-library") {
        val preferences = remember { Injekt.get<SourcePreferences>() }
        SwitchPreference("Hide entries already in library", preferences.hideInLibraryFeedItems())
    }

    // Android has a second "Sources" group here; the space keeps the list keys apart
    settingsGroup("Sources ")
    item(key = "browse-hide-library") {
        val preferences = remember { Injekt.get<SourcePreferences>() }
        SwitchPreference("Hide entries already in library", preferences.hideInLibraryItems())
    }
    item(key = "browse-stores") {
        val navigator = LocalNavigator.currentOrThrow
        val stores by remember { Injekt.get<GetExtensionStores>().subscribe() }.collectAsState(emptyList())
        SettingRow(
            "Extension stores",
            if (stores.size == 1) "1 store" else "${stores.size} stores",
            onClick = { navigator.push(ExtensionStoresScreen) },
        )
    }

    settingsGroup("NSFW (18+) sources")
    item(key = "browse-nsfw") {
        val preferences = remember { Injekt.get<SourcePreferences>() }
        SwitchPreference("Show in sources and extensions lists", preferences.showNsfwSource())
    }
    item(key = "browse-nsfw-info") {
        InfoText(
            "This does not prevent unofficial or potentially incorrectly flagged extensions from surfacing NSFW (18+) content within the app.",
        )
    }
}

/** Android's InfoPreference: a note under a group's settings. */
@Composable
fun InfoText(text: String) {
    // Notes are left out of settings search results
    if (LocalSearchQuery.current != null) return
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
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
            ScreenHeader("Extension stores")
            StoresList(state, model)
        }
    }
}
