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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.security.Incognito
import zoukan.desktop.ui.common.CoverImage
import zoukan.desktop.ui.common.EmptyScreen
import zoukan.desktop.ui.common.LazyColumn
import zoukan.desktop.ui.common.ScreenHeader
import zoukan.desktop.ui.common.Toasts
import zoukan.desktop.ui.common.TooltipIconButton
import zoukan.desktop.ui.settings.SourceSettingsScreen
import zoukan.extension.DesktopExtensionManager

/**
 * Android's extension details (ExtensionDetailsScreenModel): the extension's version, language and
 * age rating, uninstall, incognito, and its sources, each with an on/off switch and its settings.
 */
class ExtensionDetailsScreenModel(
    val pkgName: String,
    private val extensionManager: DesktopExtensionManager = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val preferences: SourcePreferences = Injekt.get(),
    private val incognito: Incognito = Injekt.get(),
) : StateScreenModel<ExtensionDetailsScreenModel.State>(State()) {

    data class SourceItem(val source: Source, val enabled: Boolean, val configurable: Boolean)

    data class State(
        val extension: Extension.Installed? = null,
        val iconUrl: String? = null,
        val sources: List<SourceItem> = emptyList(),
        val incognito: Boolean = false,
        val uninstalled: Boolean = false,
    )

    init {
        screenModelScope.launch {
            combine(
                extensionManager.installedExtensionsFlow,
                extensionManager.availableExtensionsFlow,
                preferences.disabledSources().changes(),
                preferences.incognitoExtensions().changes(),
            ) { installed, available, disabled, incognitoPkgs ->
                val extension = installed[pkgName]
                State(
                    extension = extension,
                    iconUrl = available[pkgName]?.iconUrl,
                    // As Android: enabled sources first, then by name (or language for a multi-language extension)
                    sources = extension?.sources.orEmpty()
                        .map { source ->
                            // The loaded source can be the delegated one (MangaDex and others), with its own settings
                            val loaded = sourceManager.get(source.id) ?: source
                            SourceItem(loaded, source.id.toString() !in disabled, loaded is ConfigurableSource)
                        }
                        .sortedWith(compareBy({ !it.enabled }, { languageName(it.source.lang).lowercase() }, { it.source.name.lowercase() })),
                    incognito = pkgName in incognitoPkgs,
                    uninstalled = extension == null && state.value.extension != null,
                )
            }.collect { new -> mutableState.update { new } }
        }
    }

    fun toggleSource(id: Long) {
        val key = id.toString()
        val disabled = preferences.disabledSources().get()
        preferences.disabledSources().set(if (key in disabled) disabled - key else disabled + key)
    }

    fun toggleSources(enable: Boolean) {
        val keys = state.value.extension?.sources.orEmpty().map { it.id.toString() }.toSet()
        val disabled = preferences.disabledSources().get()
        preferences.disabledSources().set(if (enable) disabled - keys else disabled + keys)
    }

    fun setIncognito(enabled: Boolean) = incognito.setExtension(pkgName, enabled)

    /** Android's "Clear cookies": the cookies of each source's site */
    fun clearCookies() {
        val urls = state.value.extension?.sources.orEmpty()
            .filterIsInstance<HttpSource>()
            .map { it.baseUrl }
            .filter { it.isNotBlank() }
            .distinct()
        screenModelScope.launch(Dispatchers.IO) {
            val jar = Injekt.get<NetworkHelper>().cookieJar
            val cleared = urls.sumOf { url -> runCatching { url.toHttpUrlOrNull()?.let(jar::remove) ?: 0 }.getOrDefault(0) }
            Toasts.show(if (cleared == 1) "Cleared 1 cookie" else "Cleared $cleared cookies")
        }
    }

    fun uninstall() {
        val extension = state.value.extension ?: return
        extensionManager.uninstallExtension(extension.pkgName)
        Toasts.show("Uninstalled ${extension.name}")
    }
}

data class ExtensionDetailsScreen(val pkgName: String) : Screen {
    // Ends with / because Voyager disposes every screen model whose key starts with a disposed screen's key
    override val key: ScreenKey = "extension-details-$pkgName/"

    @Composable
    override fun Content() {
        val model = rememberScreenModel { ExtensionDetailsScreenModel(pkgName) }
        val state by model.state.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        var menu by remember { mutableStateOf(false) }
        var confirmUninstall by remember { mutableStateOf(false) }
        LaunchedEffect(state.uninstalled) { if (state.uninstalled) navigator.pop() }
        val extension = state.extension

        Column(Modifier.fillMaxSize().testTag("extension-details")) {
            ScreenHeader("Extension info", subtitle = extension?.name) {
                if (extension != null) {
                    Box {
                        TooltipIconButton(Icons.Default.MoreVert, "More", onClick = { menu = true })
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Enable all") }, onClick = {
                                menu = false
                                model.toggleSources(true)
                            })
                            DropdownMenuItem(text = { Text("Disable all") }, onClick = {
                                menu = false
                                model.toggleSources(false)
                            })
                            DropdownMenuItem(text = { Text("Clear cookies") }, onClick = {
                                menu = false
                                model.clearCookies()
                            })
                        }
                    }
                }
            }
            if (extension == null) {
                EmptyScreen("This extension is not installed.")
                return@Column
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                item(key = "header") {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CoverImage(state.iconUrl, Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)))
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(extension.name, style = MaterialTheme.typography.headlineSmall)
                            Text(extension.pkgName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item(key = "info") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        InfoText(extension.versionName, "Version")
                        InfoText(languageName(extension.lang), "Language")
                        if (extension.isNsfw) InfoText("18+", "Age rating", error = true)
                        extension.storeName?.let { InfoText(it, "Store") }
                    }
                }
                item(key = "actions") {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { confirmUninstall = true }, modifier = Modifier.weight(1f)) { Text("Uninstall") }
                    }
                }
                item(key = "incognito") {
                    Row(
                        Modifier.fillMaxWidth().clickable { model.setIncognito(!state.incognito) }.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Incognito mode", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Pauses reading history and tracking for this extension's sources",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(state.incognito, onCheckedChange = model::setIncognito)
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                }
                items(state.sources, key = { "source-${it.source.id}" }) { item ->
                    Row(
                        Modifier.fillMaxWidth().clickable { model.toggleSource(item.source.id) }.padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            val multi = state.sources.map { it.source.lang }.distinct().size > 1
                            Text(if (multi) languageName(item.source.lang) else item.source.name, style = MaterialTheme.typography.bodyLarge)
                            if (multi) {
                                Text(item.source.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (item.configurable) {
                            TooltipIconButton(Icons.Default.Settings, "Source settings", onClick = { navigator.push(SourceSettingsScreen(item.source.id)) })
                        }
                        Switch(item.enabled, onCheckedChange = { model.toggleSource(item.source.id) })
                    }
                }
            }
        }
        if (confirmUninstall) {
            AlertDialog(
                onDismissRequest = { confirmUninstall = false },
                title = { Text("Uninstall ${extension?.name}?") },
                confirmButton = {
                    Button(onClick = {
                        confirmUninstall = false
                        model.uninstall()
                    }) { Text("Uninstall") }
                },
                dismissButton = { TextButton(onClick = { confirmUninstall = false }) { Text("Cancel") } },
            )
        }
    }
}

@Composable
private fun InfoText(primary: String, secondary: String, error: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            primary,
            style = MaterialTheme.typography.titleMedium,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Text(secondary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
