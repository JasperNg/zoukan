package zoukan.desktop.ui.more

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.GetApp
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.ui.UiPreferences
import tachiyomi.core.common.Constants
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.app.ExternalLinks
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.security.Incognito
import zoukan.desktop.tray.AppIcon
import zoukan.desktop.ui.category.CategoryScreen
import zoukan.desktop.ui.common.DesktopPreferences
import zoukan.desktop.ui.common.LazyColumn
import zoukan.desktop.ui.common.Toasts
import zoukan.desktop.ui.downloads.DownloadsScreen
import zoukan.desktop.ui.history.HistoryScreenModel
import zoukan.desktop.ui.history.HistoryTab
import zoukan.desktop.ui.library.UpdateErrorsScreen
import zoukan.desktop.ui.settings.SettingsCategory
import zoukan.desktop.ui.settings.SettingsTab
import zoukan.desktop.ui.settings.openSettingsCategory
import zoukan.desktop.ui.stats.StatsScreen
import zoukan.desktop.ui.updates.UpdatesScreenModel
import zoukan.desktop.ui.updates.UpdatesTab

/**
 * Android's More tab (MoreScreen): the app-wide switches, then the screens that have no tab of their
 * own, then Settings, About and Help.
 */
@Composable
fun MoreTab() {
    val navigator = LocalNavigator.currentOrThrow
    val preferences = remember { Injekt.get<DesktopPreferences>() }
    val incognito = remember { Injekt.get<Incognito>() }
    val ui = remember { Injekt.get<UiPreferences>() }
    val downloads = remember { Injekt.get<DownloadManager>() }
    val downloadedOnly by preferences.downloadedOnly().changes().collectAsState(preferences.downloadedOnly().get())
    val incognitoMode by incognito.mode().changes().collectAsState(incognito.mode().get())
    val showUpdates by ui.showNavUpdates().changes().collectAsState(ui.showNavUpdates().get())
    val showHistory by ui.showNavHistory().changes().collectAsState(ui.showNavHistory().get())
    val queue by downloads.queue.collectAsState()
    val running by downloads.running.collectAsState()

    LazyColumn(Modifier.fillMaxSize()) {
        item { LogoHeader() }
        item {
            MoreSwitch(
                icon = Icons.Outlined.CloudOff,
                title = "Downloaded only",
                subtitle = "Filters all entries in your library",
                checked = downloadedOnly,
                onCheckedChange = { preferences.downloadedOnly().set(it) },
            )
        }
        item {
            MoreSwitch(
                icon = Icons.Outlined.VisibilityOff,
                title = "Incognito mode",
                subtitle = "Pauses reading history",
                checked = incognitoMode,
                onCheckedChange = { incognito.mode().set(it) },
            )
        }
        item { HorizontalDivider() }
        // Updates and History, when they are hidden from the rail, as SY's More tab
        if (!showUpdates) {
            item { MoreRow(Icons.Outlined.NewReleases, "Updates") { navigator.push(UpdatesScreen) } }
        }
        if (!showHistory) {
            item { MoreRow(Icons.Outlined.History, "History") { navigator.push(HistoryScreen) } }
        }
        item {
            val subtitle = when {
                queue.isEmpty() -> null
                running -> "${queue.size} remaining"
                else -> "Paused · ${queue.size} remaining"
            }
            MoreRow(Icons.Outlined.GetApp, "Download queue", subtitle) { navigator.push(DownloadsScreen) }
        }
        item { MoreRow(Icons.AutoMirrored.Outlined.Label, "Categories") { navigator.push(CategoryScreen) } }
        item { MoreRow(Icons.Outlined.QueryStats, "Statistics") { navigator.push(StatsScreen) } }
        item { MoreRow(Icons.Outlined.ErrorOutline, "Library update errors") { navigator.push(UpdateErrorsScreen) } }
        item {
            MoreRow(Icons.Outlined.Storage, "Data and storage") {
                openSettingsCategory(SettingsCategory.Data)
                navigator.push(SettingsScreen)
            }
        }
        item { HorizontalDivider() }
        item { MoreRow(Icons.Outlined.Settings, "Settings") { navigator.push(SettingsScreen) } }
        item {
            MoreRow(Icons.Outlined.Info, "About") {
                openSettingsCategory(SettingsCategory.About)
                navigator.push(SettingsScreen)
            }
        }
        item {
            MoreRow(Icons.AutoMirrored.Outlined.HelpOutline, "Help") {
                runCatching { ExternalLinks.browse(Constants.URL_HELP) }.onFailure { Toasts.error("Could not open the page", it) }
            }
        }
    }
}

/** Settings as a screen of its own, opened from More or with Ctrl/Cmd + comma. */
object SettingsScreen : Screen {
    private fun readResolve(): Any = SettingsScreen

    override val key: ScreenKey = "settings"

    @Composable
    override fun Content() = SettingsTab()
}

/** Updates opened from More, when the rail hides it. */
private object UpdatesScreen : Screen {
    private fun readResolve(): Any = UpdatesScreen

    override val key: ScreenKey = "more-updates"

    @Composable
    override fun Content() {
        val model = rememberScreenModel { UpdatesScreenModel() }
        UpdatesTab(model)
    }
}

/** History opened from More, when the rail hides it. */
private object HistoryScreen : Screen {
    private fun readResolve(): Any = HistoryScreen

    override val key: ScreenKey = "more-history"

    @Composable
    override fun Content() {
        val model = rememberScreenModel { HistoryScreenModel() }
        HistoryTab(model)
    }
}

/** The app icon and name at the top of More, as Android's LogoHeader. */
@Composable
private fun LogoHeader() {
    val icon = remember { AppIcon.painter(128) }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Image(icon, contentDescription = null, modifier = Modifier.size(64.dp))
        Text("Zoukan", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
    }
    HorizontalDivider()
}

@Composable
private fun MoreRow(icon: ImageVector, title: String, subtitle: String? = null, onClick: () -> Unit) {
    MoreItem(icon, title, subtitle, Modifier.clickable(onClick = onClick)) {}
}

@Composable
private fun MoreSwitch(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    MoreItem(icon, title, subtitle, Modifier.clickable { onCheckedChange(!checked) }) {
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun MoreItem(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    modifier: Modifier,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing()
    }
}

/**
 * The bars Android shows above every screen while downloaded-only or incognito mode is on
 * (AppStateBanners).
 */
@Composable
fun AppStateBanners(modifier: Modifier = Modifier) {
    val preferences = remember { Injekt.get<DesktopPreferences>() }
    val incognito = remember { Injekt.get<Incognito>() }
    val downloadedOnly by preferences.downloadedOnly().changes().collectAsState(preferences.downloadedOnly().get())
    val incognitoMode by incognito.mode().changes().collectAsState(incognito.mode().get())
    Column(modifier.fillMaxWidth()) {
        if (downloadedOnly) {
            Banner("Downloaded only", MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.onTertiary)
        }
        if (incognitoMode) {
            Banner("Incognito mode", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
        }
    }
}

@Composable
private fun Banner(text: String, color: Color, contentColor: Color) {
    Surface(color = color, contentColor = contentColor, modifier = Modifier.fillMaxWidth()) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(vertical = 4.dp).fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
}
