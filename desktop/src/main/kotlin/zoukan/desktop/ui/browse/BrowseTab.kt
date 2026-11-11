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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.ConfigurableSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.ui.common.CoverImage
import zoukan.desktop.ui.common.EmptyScreen
import zoukan.desktop.ui.common.LoadingScreen
import zoukan.desktop.ui.common.ScreenHeader
import zoukan.desktop.ui.common.TooltipIconButton
import zoukan.desktop.ui.common.onRightClick
import zoukan.desktop.ui.settings.SourceSettingsScreen
import java.util.Locale

@Composable
fun BrowseTab(model: BrowseScreenModel, feed: FeedScreenModel) {
    val state by model.state.collectAsState()
    var tab by rememberSaveable { mutableStateOf(BrowsePage.SOURCES) }
    val navigator = LocalNavigator.currentOrThrow
    var globalQuery by rememberSaveable { mutableStateOf("") }
    // ZOUKAN -->
    val uiPreferences = remember { Injekt.get<UiPreferences>() }
    val hideFeed by uiPreferences.hideFeedTab().changes().collectAsState(uiPreferences.hideFeedTab().get())
    val feedInFront by uiPreferences.feedTabInFront().changes().collectAsState(uiPreferences.feedTabInFront().get())
    // Android's order: Feed first when "Feed tab position" is on, else after Sources; none when hidden
    val pages = when {
        hideFeed -> listOf(BrowsePage.SOURCES, BrowsePage.EXTENSIONS, BrowsePage.STORES)
        feedInFront -> listOf(BrowsePage.FEED, BrowsePage.SOURCES, BrowsePage.EXTENSIONS, BrowsePage.STORES)
        else -> listOf(BrowsePage.SOURCES, BrowsePage.FEED, BrowsePage.EXTENSIONS, BrowsePage.STORES)
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
        ScreenHeader("Browse", showBack = false) {
            OutlinedTextField(
                value = globalQuery,
                onValueChange = { globalQuery = it },
                placeholder = { Text("Search all sources") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.width(320.dp).onKeyEvent {
                    if (it.key == Key.Enter && globalQuery.isNotBlank()) {
                        navigator.push(GlobalSearchScreen(globalQuery.trim()))
                        true
                    } else {
                        false
                    }
                },
            )
            // ZOUKAN -->
            if (current == BrowsePage.SOURCES) {
                TooltipIconButton(Icons.Default.FilterList, "Filter sources", onClick = { navigator.push(SourcesFilterScreen) })
            }
            TooltipIconButton(Icons.AutoMirrored.Filled.PlaylistAdd, "Batch add", onClick = { navigator.push(BatchAddScreen) })
            // ZOUKAN <--
        }
        val updates = state.installed.count { it.hasUpdate }
        PrimaryTabRow(selectedTabIndex = pages.indexOf(current)) {
            pages.forEach { page ->
                Tab(
                    selected = current == page,
                    onClick = { tab = page },
                    text = {
                        Text(
                            when (page) {
                                BrowsePage.SOURCES -> "Sources"
                                BrowsePage.EXTENSIONS -> if (updates > 0) "Extensions ($updates)" else "Extensions"
                                BrowsePage.STORES -> "Stores"
                                BrowsePage.FEED -> "Feed"
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
        }
    }
}

// ZOUKAN -->
enum class BrowsePage { SOURCES, FEED, EXTENSIONS, STORES }
// ZOUKAN <--

@Composable
private fun SourcesList(state: BrowseScreenModel.State, model: BrowseScreenModel, onExtensions: () -> Unit) {
    val navigator = LocalNavigator.currentOrThrow
    when {
        !state.sourcesLoaded && state.sources.isEmpty() -> LoadingScreen()
        state.sources.isEmpty() -> EmptyScreen(
            if (state.allSources.isEmpty()) "No sources yet. Install an extension to add its sources." else "No sources. Turn some on in the sources filter.",
            actionLabel = if (state.allSources.isEmpty()) "Extensions" else "Filter",
            onAction = if (state.allSources.isEmpty()) onExtensions else ({ navigator.push(SourcesFilterScreen) }),
        )
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
            // ZOUKAN -->
            state.sections.forEachIndexed { index, (title, sources) ->
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
            TextButton(onClick = { push(SourceScreen(source.id, latest = true)) }) { Text("Latest") }
        }
        if (source is ConfigurableSource) {
            TooltipIconButton(Icons.Default.Settings, "Source settings", onClick = { push(SourceSettingsScreen(source.id)) })
        }
        TooltipIconButton(
            if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
            if (pinned) "Unpin" else "Pin",
            onClick = { model.togglePin(source) },
        )
        // ZOUKAN -->
        // Android's long-press dialog: pin, disable and set categories
        Box {
            TooltipIconButton(Icons.Default.MoreVert, "More", onClick = { menu = true })
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(if (pinned) "Unpin" else "Pin") }, onClick = {
                    menu = false
                    model.togglePin(source)
                })
                if (source !is zoukan.desktop.local.LocalSource) {
                    DropdownMenuItem(text = { Text("Disable") }, onClick = {
                        menu = false
                        model.toggleHidden(source)
                    })
                }
                DropdownMenuItem(text = { Text("Set categories") }, onClick = {
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
    var languageMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.extensionQuery,
                onValueChange = model::searchExtensions,
                placeholder = { Text("Search extensions") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).testTag("extension-search"),
            )
            Spacer(Modifier.width(8.dp))
            Box {
                TooltipIconButton(Icons.Default.Translate, "Languages", onClick = { languageMenu = true })
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
                TooltipIconButton(Icons.Default.Refresh, "Check the stores again", onClick = model::refreshAvailable)
            }
        }
        if (state.stores.isEmpty()) {
            EmptyScreen("Add an extension store to find extensions.", actionLabel = "Stores", onAction = onStores)
            return
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (state.visibleUntrusted.isNotEmpty()) {
                sectionHeader("Untrusted")
                items(state.visibleUntrusted, key = { "u-${it.pkgName}" }) { ext ->
                    ExtensionRow(ext, null, subtitle = "Signed with an unknown key. Trust it only if you know where it comes from.", busy = ext.pkgName in state.busy) {
                        OutlinedButton(onClick = { model.uninstall(ext) }) { Text("Uninstall") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { model.trust(ext) }) { Text("Trust") }
                    }
                }
            }
            if (state.loadErrors.isNotEmpty()) {
                sectionHeader("Failed to load")
                items(state.loadErrors.entries.toList(), key = { "e-${it.key}" }) { (pkg, error) ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(pkg, style = MaterialTheme.typography.bodyLarge)
                        Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (state.visibleInstalled.isNotEmpty()) {
                sectionHeader("Installed")
                items(state.visibleInstalled, key = { "i-${it.pkgName}" }) { ext ->
                    val iconUrl = state.available.firstOrNull { it.pkgName == ext.pkgName }?.iconUrl
                    ExtensionRow(ext, iconUrl, busy = ext.pkgName in state.busy) {
                        if (ext.hasUpdate) {
                            Button(onClick = { model.update(ext) }) { Text("Update") }
                            Spacer(Modifier.width(8.dp))
                        }
                        TooltipIconButton(Icons.Default.Delete, "Uninstall", onClick = { model.uninstall(ext) })
                    }
                }
            }
            val available = state.visibleAvailable
            sectionHeader(if (state.available.isEmpty() && state.refreshing) "Available (loading…)" else "Available (${available.size})")
            items(available, key = { "a-${it.pkgName}" }) { ext ->
                ExtensionRow(ext, ext.iconUrl, busy = ext.pkgName in state.busy) {
                    FilledTonalButton(onClick = { model.install(ext) }) { Text("Install") }
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
    actions: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverImage(iconUrl, Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)))
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
                "Extension stores list the extensions you can install. Zoukan installs the store's signed jars and only loads extensions signed with the store's key.",
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
                }, enabled = url.isNotBlank()) { Text("Add") }
            }
        }
        if (state.stores.none { it.indexUrl == BrowseScreenModel.KEIYOUSHI }) {
            item {
                TextButton(onClick = { model.addStore(BrowseScreenModel.KEIYOUSHI) }) { Text("Add Keiyoushi") }
            }
        }
        items(state.stores, key = { it.indexUrl }) { store ->
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(8.dp)) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(store.name, style = MaterialTheme.typography.titleMedium)
                        Text(store.indexUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "Signing key ${store.signingKey.take(16)}…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TooltipIconButton(Icons.Default.Delete, "Remove", onClick = { model.removeStore(store) })
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
    "all" -> "Multi"
    "other" -> "Other"
    "" -> "Unknown"
    else -> Locale.forLanguageTag(lang).let { locale ->
        locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }.ifBlank { lang }
    }
}
