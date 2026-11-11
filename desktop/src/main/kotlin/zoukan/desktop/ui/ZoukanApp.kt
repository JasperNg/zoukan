package zoukan.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.transitions.FadeTransition
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.ZoukanRuntime
import zoukan.desktop.app.CrashReporter
import zoukan.desktop.app.SingleInstance
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.library.BackgroundJobs
import zoukan.desktop.library.LibraryUpdater
import zoukan.desktop.tray.AppIcon
import zoukan.desktop.tray.Notifications
import zoukan.desktop.ui.browse.BrowseScreenModel
import zoukan.desktop.ui.browse.BrowseTab
import zoukan.desktop.ui.common.DesktopPreferences
import zoukan.desktop.ui.common.Images
import zoukan.desktop.ui.common.Toasts
import zoukan.desktop.ui.common.WindowBounds
import zoukan.desktop.ui.common.ZoukanTheme
import zoukan.desktop.ui.history.HistoryScreenModel
import zoukan.desktop.ui.history.HistoryTab
import zoukan.desktop.ui.library.LibraryScreenModel
import zoukan.desktop.ui.library.LibraryTab
import zoukan.desktop.ui.more.AppStateBanners
import zoukan.desktop.ui.more.MoreTab
import zoukan.desktop.ui.more.SettingsScreen
import zoukan.desktop.ui.updates.UpdatesScreenModel
import zoukan.desktop.ui.updates.UpdatesTab
import zoukan.desktop.update.Updates
import zoukan.extension.DesktopExtensionManager
import java.awt.SystemTray

/** The app's long-lived background work (extension loading, library refreshes). */
val AppScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** The main window, for file dialogs; null in tests. */
val LocalWindow = staticCompositionLocalOf<FrameWindowScope?> { null }
val LocalWindowState = staticCompositionLocalOf { WindowState() }

/** Starts the runtime and opens the main window. Returns when the window closes. */
fun runZoukanApp() {
    // One window per data folder: opening Zoukan again shows the running one (Phase 7). Taken
    // before anything else touches the data folder.
    val dirs = ZoukanDirs.default()
    val showRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    when (val instance = SingleInstance.acquire(dirs.root) { showRequests.tryEmit(Unit) }) {
        is SingleInstance.Result.Acquired -> Unit
        is SingleInstance.Result.AlreadyRunning -> {
            if (!instance.shown) showAlreadyRunning()
            return
        }
    }
    ZoukanRuntime.start(dirs)
    startUi(ZoukanRuntime.dirs)
    val preferences = Injekt.get<DesktopPreferences>()
    // ZOUKAN -->
    // Data and storage > "Clear chapter cache on app launch", as Android's MainActivity on launch
    if (Injekt.get<tachiyomi.domain.library.service.LibraryPreferences>().autoClearChapterCache().get()) {
        AppScope.launch { runCatching { zoukan.desktop.local.Archives.clearCache() } }
    }
    // ZOUKAN <--

    application {
        // The window opens where it was closed, at the size it had
        val windowState = remember { WindowBounds.initialState(preferences.windowBounds().get()) }
        LaunchedEffect(windowState) {
            var floating = WindowBounds.decode(preferences.windowBounds().get())?.copy(maximized = false)
            snapshotFlow { WindowBounds.of(windowState, floating) }
                .filterNotNull()
                .collectLatest { bounds ->
                    if (!bounds.maximized) floating = bounds
                    // Dragging and resizing change the bounds many times a second: save once they rest
                    delay(500)
                    preferences.windowBounds().set(bounds.encode())
                }
        }
        var navigatorRef by remember { mutableStateOf<Navigator?>(null) }
        var visible by remember { mutableStateOf(true) }
        val trayState = rememberTrayState()
        val trayAvailable = remember { runCatching { SystemTray.isSupported() }.getOrDefault(false) }
        val icon = remember { AppIcon.painter() }
        val updater = remember { Injekt.get<LibraryUpdater>() }
        val downloads = remember { Injekt.get<DownloadManager>() }
        val downloading by downloads.running.collectAsState()
        // ZOUKAN -->
        // The app lock: away (minimized or in the tray) and back, as Android's background and foreground
        val appLock = remember { Injekt.get<zoukan.desktop.security.AppLock>().apply { onStart() } }
        val away = !visible || windowState.isMinimized
        // Data sync on start and when the window comes back, as Android's sync triggers
        val sync = remember { Injekt.get<eu.kanade.tachiyomi.data.sync.SyncManager>() }
        var wasAway by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { sync.syncOn { it.syncOnAppStart } }
        remember { Injekt.get<zoukan.desktop.discord.DiscordPresence>().apply { start() } }
        LaunchedEffect(away) {
            if (away) {
                appLock.onBackground()
                wasAway = true
            } else {
                appLock.onForeground()
                if (wasAway) sync.syncOn { it.syncOnAppResume }
            }
        }
        // ZOUKAN <--

        if (trayAvailable) {
            Tray(
                icon = icon,
                state = trayState,
                tooltip = "Zoukan",
                onAction = { visible = true },
                menu = {
                    Item("Open Zoukan", onClick = { visible = true })
                    Item("Update library", onClick = { updater.start() })
                    if (downloading) {
                        Item("Pause downloads", onClick = { downloads.pause() })
                    } else {
                        Item("Start downloads", onClick = { downloads.start() })
                    }
                    Separator()
                    Item("Quit", onClick = ::exitApplication)
                },
            )
            LaunchedEffect(Unit) {
                Notifications.flow.collect { message ->
                    val type = when (message.kind) {
                        Notifications.Kind.INFO -> Notification.Type.Info
                        Notifications.Kind.WARNING -> Notification.Type.Warning
                        Notifications.Kind.ERROR -> Notification.Type.Error
                    }
                    // ZOUKAN -->
                    // "Hide notification content", as on Android
                    val hide = Injekt.get<eu.kanade.tachiyomi.core.security.SecurityPreferences>().hideNotificationContent().get()
                    trayState.sendNotification(
                        if (hide) Notification("Zoukan", "Open Zoukan to see the details", type) else Notification(message.title, message.text, type),
                    )
                    // ZOUKAN <--
                }
            }
        }

        // A crash in the UI writes a crash log, says where it is and closes the app (Phase 7)
        CompositionLocalProvider(
            LocalWindowExceptionHandlerFactory provides WindowExceptionHandlerFactory {
                WindowExceptionHandler { e -> CrashReporter.fatal(e) }
            },
        ) {
            Window(
                onCloseRequest = {
                    if (trayAvailable && preferences.closeToTray().get()) {
                        visible = false
                        if (!preferences.toldAboutTray().get()) {
                            preferences.toldAboutTray().set(true)
                            trayState.sendNotification(
                                Notification("Zoukan is still running", "Library updates and downloads go on. Quit from the tray icon."),
                            )
                        }
                    } else {
                        exitApplication()
                    }
                },
                visible = visible,
                icon = icon,
                title = "Zoukan",
                state = windowState,
                onKeyEvent = { event -> Shortcuts.handle(event, navigatorRef) },
            ) {
                // Below this size the two-pane screens and the toolbars no longer fit
                LaunchedEffect(Unit) {
                    window.minimumSize = java.awt.Dimension(WindowBounds.MIN_WIDTH, WindowBounds.MIN_HEIGHT)
                }
                CompositionLocalProvider(LocalWindow provides this, LocalWindowState provides windowState) {
                    ZoukanContent(preferences, onNavigator = { navigatorRef = it }, firstRun = true)
                }
                LaunchedEffect(Unit) {
                    // Zoukan was opened again: bring this window back
                    showRequests.collect {
                        visible = true
                        windowState.isMinimized = false
                        window.toFront()
                        window.requestFocus()
                    }
                }
                LaunchedEffect(Unit) {
                    CrashReporter.previousRunCrashed?.let {
                        delay(1500)
                        Toasts.show("Zoukan closed unexpectedly last time. The crash log is in More > Settings > About.")
                    }
                }
            }
        }
    }
}

private fun showAlreadyRunning() {
    val message = "Zoukan is already running. Open it from its icon in the tray or menu bar."
    if (java.awt.GraphicsEnvironment.isHeadless()) {
        System.err.println(message)
    } else {
        javax.swing.JOptionPane.showMessageDialog(null, message, "Zoukan", javax.swing.JOptionPane.INFORMATION_MESSAGE)
    }
}

/** What the app needs besides the runtime: the image loader and the extensions. */
fun startUi(dirs: ZoukanDirs) {
    Images.init(dirs.covers)
    // NetworkHelper first, so the cookie store and Chromium hooks are in place
    Injekt.get<NetworkHelper>()
    // Injekt's singleton factories are not thread-safe: create the database and the managers here,
    // before the UI and the extension loading below ask for them from other threads (two threads
    // creating the database at once would each make a key)
    Injekt.get<DatabaseHandler>()
    Injekt.get<SourceManager>()
    val manager = Injekt.get<DesktopExtensionManager>()
    AppScope.launch {
        runCatching { manager.init() }
            .onFailure { logcat(LogPriority.ERROR, it) { "Loading extensions failed" } }
        runCatching { manager.findAvailableExtensions() }
    }
    AppScope.launch { Notifications.flow.collect { logcat { "Notification: ${it.title}: ${it.text}" } } }
    // The saved download queue, then the scheduled library update and automatic backups
    val downloads = Injekt.get<DownloadManager>()
    AppScope.launch {
        Injekt.get<SourceManager>().isInitialized.first { it }
        runCatching { downloads.restore() }.onFailure { logcat(LogPriority.ERROR, it) { "Restoring the download queue failed" } }
    }
    Injekt.get<BackgroundJobs>().start(AppScope)
    // The daily update check (Phase 7)
    Updates.start(AppScope)
}

/**
 * The window's content: theme, navigation and messages. [firstRun] shows the first-run setup and
 * the What's new dialog after an update; tests leave it off.
 */
@Composable
fun ZoukanContent(preferences: DesktopPreferences, firstRun: Boolean = false, onNavigator: (Navigator) -> Unit = {}) {
    var navigatorRef by remember { mutableStateOf<Navigator?>(null) }
    ZoukanTheme(remember { Injekt.get<eu.kanade.domain.ui.UiPreferences>() }) {
        Surface(
            Modifier.fillMaxSize().onPointerEvent(PointerEventType.Press) { event ->
                // The mouse's back button
                // ZOUKAN -->
                // Not while the app lock covers the window
                val locked = runCatching { Injekt.get<zoukan.desktop.security.AppLock>().locked.value }.getOrDefault(false)
                // ZOUKAN <--
                if (event.button == PointerButton.Back && !locked) navigatorRef?.takeIf { it.canPop }?.pop()
            },
            color = MaterialTheme.colorScheme.background,
        ) {
            val snackbar = remember { SnackbarHostState() }
            LaunchedEffect(Unit) {
                Toasts.flow.collect { snackbar.showSnackbar(it) }
            }
            Box(Modifier.fillMaxSize()) {
                // ZOUKAN -->
                // The downloaded-only and incognito bars above every screen, as Android's AppStateBanners
                Column(Modifier.fillMaxSize()) {
                    AppStateBanners()
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        Navigator(HomeScreen) { navigator ->
                            navigatorRef = navigator
                            onNavigator(navigator)
                            FadeTransition(navigator)
                        }
                    }
                }
                // ZOUKAN <--
                // The first-run setup covers the app, but not its messages
                if (firstRun) zoukan.desktop.ui.onboarding.FirstRun(preferences) { navigatorRef }
                zoukan.desktop.ui.onboarding.ReopenedOnboarding { navigatorRef }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
                // ZOUKAN -->
                zoukan.desktop.ui.security.LockOverlay()
                // ZOUKAN <--
            }
        }
    }
}

enum class HomeTab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    Library("Library", Icons.Outlined.CollectionsBookmark, Icons.Filled.CollectionsBookmark),
    Updates("Updates", Icons.Outlined.NewReleases, Icons.Filled.NewReleases),
    History("History", Icons.Outlined.History, Icons.Filled.History),
    Browse("Browse", Icons.Outlined.Explore, Icons.Filled.Explore),
    More("More", Icons.Outlined.MoreHoriz, Icons.Filled.MoreHoriz),
}

/** The main screen: a navigation rail with the five tabs. */
object HomeScreen : cafe.adriel.voyager.core.screen.Screen {
    private fun readResolve(): Any = HomeScreen

    /** Lets other screens switch the tab, for example "Browse" from an empty library. */
    var selectTab: (HomeTab) -> Unit = {}

    /** Opens Settings (More > Settings) from anywhere: Ctrl/Cmd + comma, and tests. */
    fun openSettings(navigator: Navigator) {
        navigator.popUntilRoot()
        selectTab(HomeTab.More)
        navigator.push(SettingsScreen)
    }

    @Composable
    override fun Content() {
        var tab by rememberSaveable { mutableStateOf(HomeTab.Library) }
        selectTab = { tab = it }
        // ZOUKAN -->
        // Discord Rich Presence shows the tab in use, as Android's tabs set it
        LaunchedEffect(tab) {
            Injekt.get<zoukan.desktop.discord.DiscordPresence>().setScreen(
                when (tab) {
                    HomeTab.Library -> zoukan.desktop.discord.DiscordPresence.Screen.LIBRARY
                    HomeTab.Updates -> zoukan.desktop.discord.DiscordPresence.Screen.UPDATES
                    HomeTab.History -> zoukan.desktop.discord.DiscordPresence.Screen.HISTORY
                    HomeTab.Browse -> zoukan.desktop.discord.DiscordPresence.Screen.BROWSE
                    HomeTab.More -> zoukan.desktop.discord.DiscordPresence.Screen.MORE
                },
            )
        }
        // ZOUKAN <--
        val library = rememberScreenModel { LibraryScreenModel() }
        val updates = rememberScreenModel { UpdatesScreenModel() }
        val history = rememberScreenModel { HistoryScreenModel() }
        val browse = rememberScreenModel { BrowseScreenModel() }
        val feed = rememberScreenModel { zoukan.desktop.ui.browse.FeedScreenModel() }
        // ZOUKAN -->
        // Settings > Appearance > Navigation, and the new chapters badge, as on Android
        val ui = remember { Injekt.get<eu.kanade.domain.ui.UiPreferences>() }
        val libraryPreferences = remember { Injekt.get<tachiyomi.domain.library.service.LibraryPreferences>() }
        val showUpdates by ui.showNavUpdates().changes().collectAsState(ui.showNavUpdates().get())
        val showHistory by ui.showNavHistory().changes().collectAsState(ui.showNavHistory().get())
        val showLabels by ui.bottomBarLabels().changes().collectAsState(ui.bottomBarLabels().get())
        val showBadge by libraryPreferences.newShowUpdatesCount().changes().collectAsState(libraryPreferences.newShowUpdatesCount().get())
        val newUpdates by libraryPreferences.newUpdatesCount().changes().collectAsState(libraryPreferences.newUpdatesCount().get())
        // Browse shows how many extensions have an update, as Android's extensionUpdatesCount
        val extensions = remember { Injekt.get<DesktopExtensionManager>() }
        val installedExtensions by extensions.installedExtensionsFlow.collectAsState()
        val extensionUpdates = installedExtensions.values.count { it.hasUpdate }
        val shownTabs = HomeTab.entries.filter {
            (it != HomeTab.Updates || showUpdates) && (it != HomeTab.History || showHistory)
        }
        // Opening Updates clears the badge, as Android's Updates tab does
        LaunchedEffect(tab, newUpdates) {
            if (tab == HomeTab.Updates && newUpdates != 0) libraryPreferences.newUpdatesCount().set(0)
        }
        // ZOUKAN <--
        Row(Modifier.fillMaxSize()) {
            // Komikku's tablet rail: tinted with the theme color and its items centered
            Surface(Modifier.fillMaxHeight(), tonalElevation = 3.dp) {
                Column(
                    Modifier.fillMaxHeight().widthIn(min = 80.dp).padding(vertical = 4.dp).selectableGroup(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                ) {
                    shownTabs.forEach { item ->
                        val badge = when {
                            item == HomeTab.Updates && showBadge && newUpdates > 0 -> newUpdates
                            item == HomeTab.Browse && extensionUpdates > 0 -> extensionUpdates
                            else -> 0
                        }
                        NavigationRailItem(
                            selected = tab == item,
                            onClick = { tab = item },
                            icon = {
                                androidx.compose.material3.BadgedBox(
                                    badge = { if (badge > 0) androidx.compose.material3.Badge { Text(badge.toString()) } },
                                ) {
                                    Icon(if (tab == item) item.selectedIcon else item.icon, contentDescription = null)
                                }
                            },
                            label = {
                                Text(
                                    item.label,
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            alwaysShowLabel = showLabels,
                        )
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (tab) {
                    HomeTab.Library -> LibraryTab(library)
                    HomeTab.Updates -> UpdatesTab(updates)
                    HomeTab.History -> HistoryTab(history)
                    HomeTab.Browse -> BrowseTab(browse, feed)
                    HomeTab.More -> MoreTab()
                }
            }
        }
    }
}
