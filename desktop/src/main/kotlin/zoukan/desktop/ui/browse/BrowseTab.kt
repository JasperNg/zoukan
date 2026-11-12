package zoukan.desktop.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.ConfigurableSource
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.common.Corners
import zoukan.desktop.ui.common.CoverImage
import zoukan.desktop.ui.common.DropdownMenu
import zoukan.desktop.ui.common.EmptyScreen
import zoukan.desktop.ui.common.LazyColumn
import zoukan.desktop.ui.common.LoadingScreen
import zoukan.desktop.ui.common.ScreenHeader
import zoukan.desktop.ui.common.SearchField
import zoukan.desktop.ui.common.TooltipIconButton
import zoukan.desktop.ui.common.onRightClick
import zoukan.desktop.ui.settings.SourceSettingsScreen
import zoukan.i18n.desktop.DMR
import java.util.Locale

@Composable
fun BrowseTab(model: BrowseScreenModel, feed: FeedScreenModel) {
    val state by model.state.collectAsState()
    var tab by rememberSaveable { mutableStateOf(BrowsePage.SOURCES) }
    val navigator = LocalNavigator.currentOrThrow
    var globalQuery by rememberSaveable { mutableStateOf("") }
    // ZOUKAN -->
    var openUrl by remember { mutableStateOf(false) }
    // ZOUKAN <--
    // ZOUKAN -->
    val uiPreferences = remember { Injekt.get<UiPreferences>() }
    val hideFeed by uiPreferences.hideFeedTab().changes().collectAsState(uiPreferences.hideFeedTab().get())
    val feedInFront by uiPreferences.feedTabInFront().changes().collectAsState(uiPreferences.feedTabInFront().get())
    // Android's order: Feed first when "Feed tab position" is on, else after Sources; none when hidden
    val pages = when {
        hideFeed -> listOf(BrowsePage.SOURCES, BrowsePage.EXTENSIONS, BrowsePage.MIGRATE, BrowsePage.STORES)
        feedInFront -> listOf(BrowsePage.FEED, BrowsePage.SOURCES, BrowsePage.EXTENSIONS, BrowsePage.MIGRATE, BrowsePage.STORES)
        else -> listOf(BrowsePage.SOURCES, BrowsePage.FEED, BrowsePage.EXTENSIONS, BrowsePage.MIGRATE, BrowsePage.STORES)
    }
    // The first tab opens first, as on Android, when the feed settings change it; coming back to
    // Browse keeps the tab that was open
    var lastFirst by rememberSaveable { mutableStateOf(pages.first()) }
    if (pages.first() != lastFirst) {
        lastFirst = pages.first()
        tab = pages.first()
    }
    val current = tab.takeIf { it in pages } ?: pages.first()
    // ZOUKAN <--

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(localized(MR.strings.browse), showBack = false) {
            SearchField(
                globalQuery,
                { globalQuery = it },
                localized(DMR.strings.search_all_sources),
                Modifier.width(320.dp),
                onSearch = {
                    if (globalQuery.isNotBlank()) {
                        // ZOUKAN -->
                        // An entry's address opens the entry, as a shared link does on Android
                        val query = globalQuery.trim()
                        navigator.push(if (OpenUrlScreen.isUrl(query)) OpenUrlScreen(query) else GlobalSearchScreen(query))
                        // ZOUKAN <--
                    }
                },
            )
            // ZOUKAN -->
            if (current == BrowsePage.SOURCES) {
                TooltipIconButton(Icons.Outlined.FilterList, localized(DMR.strings.filter_sources), onClick = { navigator.push(SourcesFilterScreen) })
            }
            TooltipIconButton(Icons.AutoMirrored.Outlined.PlaylistAdd, localized(SYMR.strings.batch_add), onClick = { navigator.push(BatchAddScreen) })
            TooltipIconButton(Icons.Outlined.Link, localized(DMR.strings.open_from_a_url), onClick = { openUrl = true })
            // ZOUKAN <--
        }
        val updates = state.installed.count { it.hasUpdate }
        // ZOUKAN -->
        if (openUrl) OpenUrlDialog(onDismiss = { openUrl = false })
        // ZOUKAN <--
        PrimaryTabRow(selectedTabIndex = pages.indexOf(current)) {
            pages.forEach { page ->
                Tab(
                    selected = current == page,
                    onClick = { tab = page },
                    text = {
                        Text(
                            when (page) {
                                BrowsePage.SOURCES -> localized(MR.strings.label_sources)
                                BrowsePage.EXTENSIONS -> if (updates > 0) localized(DMR.strings.extensions, updates) else localized(MR.strings.label_extensions)
                                BrowsePage.STORES -> localized(DMR.strings.stores)
                                // ZOUKAN -->
                                BrowsePage.MIGRATE -> localized(MR.strings.action_migrate)
                                // ZOUKAN <--
                                BrowsePage.FEED -> localized(SYMR.strings.feed)
                            },
                        )
                    },
                )
            }
        }
        when (current) {
            BrowsePage.SOURCES -> SourcesList(state, model, onExtensions = { tab = BrowsePage.EXTENSIONS })
            BrowsePage.EXTENSIONS -> ExtensionsList(state, model, onStores = { tab = BrowsePage.STORES })
            BrowsePage.STORES -> StoresList(state, model)
            BrowsePage.FEED -> FeedTab(feed)
            // ZOUKAN -->
            BrowsePage.MIGRATE -> MigrateSourcesList()
            // ZOUKAN <--
        }
    }
}

// ZOUKAN -->
enum class BrowsePage { SOURCES, FEED, EXTENSIONS, MIGRATE, STORES }
// ZOUKAN <--

@Composable
private fun SourcesList(state: BrowseScreenModel.State, model: BrowseScreenModel, onExtensions: () -> Unit) {
    val navigator = LocalNavigator.currentOrThrow
    when {
        !state.sourcesLoaded && state.sources.isEmpty() -> LoadingScreen()
        state.sources.isEmpty() -> EmptyScreen(
            if (state.allSources.isEmpty()) localized(DMR.strings.no_sources_yet_install_an_extension) else localized(DMR.strings.no_sources_turn_some_on_in),
            actionLabel = if (state.allSources.isEmpty()) localized(MR.strings.label_extensions) else localized(MR.strings.action_filter),
            onAction = if (state.allSources.isEmpty()) onExtensions else ({ navigator.push(SourcesFilterScreen) }),
        )
        // ZOUKAN -->
        // KMK's search box over the list
        else -> Column(Modifier.fillMaxSize()) {
            SearchField(
                state.sourceQuery,
                model::searchSources,
                localized(KMR.strings.action_search_for_source),
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp).testTag("source-search"),
            )
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                state.visibleSections.forEachIndexed { index, (title, sources) ->
                    item(key = "section-$index-$title") {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                        )
                    }
                    items(sources, key = { "section-$index-${it.id}" }) { SourceRow(it, state, model, navigator::push) }
                }
            }
            // ZOUKAN <--
        }
    }
}

@Composable
private fun SourceRow(
    source: CatalogueSource,
    state: BrowseScreenModel.State,
    model: BrowseScreenModel,
    push: (cafe.adriel.voyager.core.screen.Screen) -> Unit,
) {
    val pinned = source.id.toString() in state.pinned
    // ZOUKAN -->
    var menu by remember { mutableStateOf(false) }
    var categoriesDialog by remember { mutableStateOf(false) }
    // ZOUKAN <--
    Row(
        Modifier.fillMaxWidth()
            // ZOUKAN -->
            // Android's "Replace latest button": the source opens its feed, with latest and browse
            .clickable { push(if (state.showLatest) SourceScreen(source.id) else SourceFeedScreen(source.id)) }
            .onRightClick { menu = true }
            // ZOUKAN <--
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(source.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                languageName(source.lang),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // ZOUKAN -->
        if (source.supportsLatest && state.showLatest) {
            // ZOUKAN <--
            TextButton(onClick = { push(SourceScreen(source.id, latest = true)) }) { Text(localized(MR.strings.latest)) }
        }
        if (source is ConfigurableSource) {
            TooltipIconButton(Icons.Outlined.Settings, localized(MR.strings.source_settings), onClick = { push(SourceSettingsScreen(source.id)) })
        }
        TooltipIconButton(
            if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
            if (pinned) localized(MR.strings.action_unpin) else localized(MR.strings.action_pin),
            onClick = { model.togglePin(source) },
        )
        // ZOUKAN -->
        // Android's long-press dialog: pin, disable and set categories
        Box {
            TooltipIconButton(Icons.Outlined.MoreVert, localized(MR.strings.label_more), onClick = { menu = true })
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(if (pinned) localized(MR.strings.action_unpin) else localized(MR.strings.action_pin)) }, onClick = {
                    menu = false
                    model.togglePin(source)
                })
                if (source !is zoukan.desktop.local.LocalSource) {
                    DropdownMenuItem(text = { Text(localized(MR.strings.action_disable)) }, onClick = {
                        menu = false
                        model.toggleHidden(source)
                    })
                }
                DropdownMenuItem(text = { Text(localized(MR.strings.action_move_category)) }, onClick = {
                    menu = false
                    categoriesDialog = true
                })
            }
        }
        // ZOUKAN <--
    }
    // ZOUKAN -->
    if (categoriesDialog) {
        SourceCategoriesDialog(
            source = source,
            categories = state.categories,
            selected = state.sourceCategories[source.id].orEmpty(),
            onConfirm = { model.setCategories(source, it) },
            onDismiss = { categoriesDialog = false },
            onEditCategories = {
                categoriesDialog = false
                push(SourceCategoryScreen)
            },
        )
    }
    // ZOUKAN <--
}

@Composable
private fun ExtensionsList(state: BrowseScreenModel.State, model: BrowseScreenModel, onStores: () -> Unit) {
    // ZOUKAN -->
    val navigator = LocalNavigator.currentOrThrow
    // ZOUKAN <--
    var languageMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            SearchField(
                state.extensionQuery,
                model::searchExtensions,
                localized(DMR.strings.search_extensions),
                Modifier.weight(1f).testTag("extension-search"),
            )
            Spacer(Modifier.width(8.dp))
            Box {
                TooltipIconButton(Icons.Outlined.Translate, localized(DMR.strings.languages), onClick = { languageMenu = true })
                DropdownMenu(languageMenu, onDismissRequest = { languageMenu = false }) {
                    state.allLanguages.forEach { lang ->
                        DropdownMenuItem(
                            text = { Text(languageName(lang)) },
                            leadingIcon = { Checkbox(checked = lang in state.languages, onCheckedChange = null) },
                            onClick = { model.toggleLanguage(lang) },
                        )
                    }
                }
            }
            if (state.refreshing) {
                CircularProgressIndicator(Modifier.size(24.dp).padding(2.dp))
            } else {
                TooltipIconButton(Icons.Outlined.Refresh, localized(DMR.strings.check_the_stores_again), onClick = model::refreshAvailable)
            }
        }
        if (state.stores.isEmpty()) {
            EmptyScreen(localized(DMR.strings.add_an_extension_store_to_find), actionLabel = localized(DMR.strings.stores), onAction = onStores)
            return
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (state.visibleUntrusted.isNotEmpty()) {
                sectionHeader(localized(MR.strings.ext_untrusted))
                items(state.visibleUntrusted, key = { "u-${it.pkgName}" }) { ext ->
                    // ZOUKAN -->
                    // Android's long press on an untrusted extension uninstalls it
                    ExtensionRow(ext, null, subtitle = localized(DMR.strings.signed_with_an_unknown_key_trust), busy = ext.pkgName in state.busy, onSecondaryClick = { model.uninstall(ext) }) {
                        // ZOUKAN <--
                        OutlinedButton(onClick = { model.uninstall(ext) }) { Text(localized(MR.strings.ext_uninstall)) }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { model.trust(ext) }) { Text(localized(MR.strings.ext_trust)) }
                    }
                }
            }
            if (state.loadErrors.isNotEmpty()) {
                sectionHeader(localized(DMR.strings.failed_to_load_2))
                items(state.loadErrors.entries.toList(), key = { "e-${it.key}" }) { (pkg, error) ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(pkg, style = MaterialTheme.typography.bodyLarge)
                        Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            // ZOUKAN -->
            // Extensions with updates come first under "Updates pending", with Update all, as on Android
            val (pending, upToDate) = state.visibleInstalled.partition { it.hasUpdate }
            if (pending.isNotEmpty()) {
                item(key = "header-updates-pending") {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            localized(MR.strings.ext_updates_pending),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        Button(onClick = model::updateAll, modifier = Modifier.testTag("extension-update-all")) {
                            Text(localized(MR.strings.ext_update_all))
                        }
                    }
                }
            }
            listOf(pending to null, upToDate to localized(MR.strings.ext_installed)).forEach { (extensions, header) ->
                if (extensions.isEmpty()) return@forEach
                header?.let { sectionHeader(it) }
                // ZOUKAN <--
                items(extensions, key = { "i-${it.pkgName}" }) { ext ->
                    val iconUrl = state.available.firstOrNull { it.pkgName == ext.pkgName }?.iconUrl
                    // ZOUKAN -->
                    // Android's long press on an installed extension uninstalls it
                    ExtensionRow(ext, iconUrl, busy = ext.pkgName in state.busy, onClick = { navigator.push(ExtensionDetailsScreen(ext.pkgName)) }, onSecondaryClick = { model.uninstall(ext) }) {
                        // ZOUKAN <--
                        if (ext.hasUpdate) {
                            Button(onClick = { model.update(ext) }) { Text(localized(MR.strings.ext_update)) }
                            Spacer(Modifier.width(8.dp))
                        }
                        TooltipIconButton(Icons.Outlined.Delete, localized(MR.strings.ext_uninstall), onClick = { model.uninstall(ext) })
                    }
                }
            }
            val available = state.visibleAvailable
            sectionHeader(if (state.available.isEmpty() && state.refreshing) localized(DMR.strings.available_loading) else localized(DMR.strings.available, available.size))
            items(available, key = { "a-${it.pkgName}" }) { ext ->
                // ZOUKAN -->
                // Android's long press on an available extension installs it
                ExtensionRow(ext, ext.iconUrl, busy = ext.pkgName in state.busy, onSecondaryClick = { model.install(ext) }) {
                    // ZOUKAN <--
                    FilledTonalButton(onClick = { model.install(ext) }) { Text(localized(MR.strings.action_install)) }
                }
            }
        }
    }
}

@Composable
private fun ExtensionRow(
    extension: Extension,
    iconUrl: String?,
    subtitle: String? = null,
    busy: Boolean,
    // ZOUKAN -->
    onClick: (() -> Unit)? = null,
    /** Android's long press on the row: install it when available, uninstall it otherwise */
    onSecondaryClick: (() -> Unit)? = null,
    // ZOUKAN <--
    actions: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            // ZOUKAN -->
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .then(if (onSecondaryClick != null) Modifier.onRightClick(onSecondaryClick) else Modifier)
            // ZOUKAN <--
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverImage(iconUrl, Modifier.size(40.dp).clip(Corners.Medium))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(extension.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val details = listOfNotNull(
                extension.lang?.let(::languageName),
                extension.versionName,
                if (extension.isNsfw) "18+" else null,
                extension.storeName,
            )
            Text(
                subtitle ?: details.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (busy) {
            CircularProgressIndicator(Modifier.size(24.dp))
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) { actions() }
        }
    }
}

@Composable
internal fun StoresList(state: BrowseScreenModel.State, model: BrowseScreenModel) {
    var url by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(
                localized(DMR.strings.extension_stores_list_the_extensions_you),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    placeholder = { Text("Store URL (repo.json or index.min.json)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    model.addStore(url)
                    url = ""
                }, enabled = url.isNotBlank()) { Text(localized(MR.strings.action_add)) }
            }
        }
        if (state.stores.none { it.indexUrl == BrowseScreenModel.KEIYOUSHI }) {
            item {
                TextButton(onClick = { model.addStore(BrowseScreenModel.KEIYOUSHI) }) { Text(localized(DMR.strings.add_keiyoushi)) }
            }
        }
        items(state.stores, key = { it.indexUrl }) { store ->
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = Corners.Medium) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(store.name, style = MaterialTheme.typography.titleMedium)
                        Text(store.indexUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            localized(DMR.strings.signing_key, store.signingKey.take(16)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TooltipIconButton(Icons.Outlined.Delete, localized(MR.strings.action_remove), onClick = { model.removeStore(store) })
                }
            }
        }
    }
}

fun LazyListScope.sectionHeader(title: String) {
    item(key = "header-$title") {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        )
    }
}

fun languageName(lang: String): String = when (lang) {
    "all" -> localized(MR.strings.multi_lang)
    "other" -> localized(MR.strings.other_source)
    "" -> localized(MR.strings.unknown)
    else -> Locale.forLanguageTag(lang).let { locale ->
        locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }.ifBlank { lang }
    }
}
