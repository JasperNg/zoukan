package zoukan.desktop.ui

import androidx.compose.foundation.focusable
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.testTag
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
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.ZoukanRuntime
import zoukan.desktop.app.AppScope
import zoukan.desktop.app.CrashReporter
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.app.ServiceScope
import zoukan.desktop.app.SingleInstance
import zoukan.desktop.app.Toasts
import zoukan.desktop.browser.BrowserWindow
import zoukan.desktop.browser.JcefRuntime
import zoukan.desktop.compat.localized
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.library.BackgroundJobs
import zoukan.desktop.library.LibraryUpdater
import zoukan.desktop.tray.AppIcon
import zoukan.desktop.tray.Notifications
import zoukan.desktop.ui.browse.BrowseScreenModel
import zoukan.desktop.ui.browse.BrowseTab
import zoukan.desktop.ui.common.AppMessages
import zoukan.desktop.ui.common.Images
import zoukan.desktop.ui.common.LocalAppMessages
import zoukan.desktop.ui.common.TooltipIf
import zoukan.desktop.ui.common.WindowBounds
import zoukan.desktop.ui.common.ZoukanTheme
import zoukan.desktop.ui.common.collectAsState
import zoukan.desktop.ui.common.showMessages
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
import zoukan.i18n.desktop.DMR
import java.awt.SystemTray

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
        is SingleInstance.Result.CommandRunning -> {
            showCommandRunning(instance.command)
            return
        }
    }
    ZoukanRuntime.start(dirs)
    // English only, before anything looks up a string
    zoukan.desktop.ui.common.AppLanguage.applyEnglish()
    val preferences = Injekt.get<DesktopPreferences>()
    // ZOUKAN -->
    // The app lock engages before anything runs in the background: a Cloudflare challenge that the
    // library update or the extension loading meets must not open a browser window over a locked app
    Injekt.get<zoukan.desktop.security.AppLock>().onStart()
    // Each lock forgets the download password kept for covers and the decoded images in memory
    Images.followAppLock(Injekt.get<zoukan.desktop.security.AppLock>().locked, ServiceScope)
    zoukan.desktop.security.AppActivity.install()
    // ZOUKAN <--
    startUi(ZoukanRuntime.dirs)
    // ZOUKAN -->
    // Secure screen follows its setting and incognito mode, for the windows that attach to it
    zoukan.desktop.security.SecureScreen.start(
        ServiceScope,
        Injekt.get<eu.kanade.tachiyomi.core.security.SecurityPreferences>(),
        Injekt.get<zoukan.desktop.security.Incognito>().mode(),
    )
    // The storage folder's downloads, local source and automatic backups stay out of Time Machine
    // on macOS while the setting is on. Here, not in startUi, so UI tests do not mark temp folders
    // (Settings > Data and storage reads the same instance to show when applying it failed)
    Injekt.get<zoukan.desktop.storage.StorageFolderPrivacy>().start(ServiceScope)

    // Data and storage > "Clear chapter cache on app launch", as Android's MainActivity on launch
    if (Injekt.get<tachiyomi.domain.library.service.LibraryPreferences>().autoClearChapterCache().get()) {
        AppScope.launch { runCatching { zoukan.desktop.local.Archives.clearCache() } }
    }
    // ZOUKAN <--

    application {
        // Quitting (the tray, or the window's X without close-to-tray): the built-in browser
        // windows (a Cloudflare challenge, a tracker login) must be gone before Chromium shuts
        // down at exit, which happens once these Compose windows have closed and main() exits
        fun quit() = AppShell.quit(disposeBrowsers = BrowserWindow::disposeAllForQuit, exit = { exitApplication() })
        // Cmd+Q on macOS, once Chromium has taken it over, quits the same way
        DisposableEffect(Unit) {
            val quitting = AppShell.installQuitRequest(::quit)
            onDispose { quitting() }
        }
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
        var visible by remember { mutableStateOf(true) }
        val trayState = rememberTrayState()
        val trayAvailable = remember { runCatching { SystemTray.isSupported() }.getOrDefault(false) }
        val icon = remember { AppIcon.painter() }
        val updater = remember { Injekt.get<LibraryUpdater>() }
        val downloads = remember { Injekt.get<DownloadManager>() }
        val downloading by downloads.running.collectAsState()
        // ZOUKAN -->
        // The app lock: away (minimized, in the tray, or another app in front) and back, as
        // Android's background and foreground; "Lock when idle" counts from leaving the app
        // The decisions are in AppLifecycle; the window only reports its events
        val lifecycle = remember {
            val appLock = Injekt.get<zoukan.desktop.security.AppLock>()
            val sync = Injekt.get<eu.kanade.tachiyomi.data.sync.SyncManager>()
            zoukan.desktop.app.AppLifecycle(sync::syncOn, appLock::onBackground) { appLock.onForeground() }
        }
        val hidden = AppShell.hidden(visible, windowState.isMinimized)
        val appActive by zoukan.desktop.security.AppActivity.active.collectAsState()
        val away = AppShell.away(hidden, appActive)
        LaunchedEffect(away) { lifecycle.onAwayChanged(away) }
        // Data sync on start and when the window comes back, as Android's sync triggers
        LaunchedEffect(Unit) { lifecycle.onStart() }
        LaunchedEffect(hidden) { lifecycle.onHiddenChanged(hidden) }
        // ZOUKAN <--

        if (trayAvailable) {
            Tray(
                icon = icon,
                state = trayState,
                tooltip = "Zoukan",
                onAction = { visible = true },
                menu = {
                    trayMenu(
                        downloading = downloading,
                        open = { visible = true },
                        updateLibrary = { updater.start() },
                        pauseDownloads = { downloads.pause() },
                        startDownloads = { downloads.start() },
                        quit = ::quit,
                    ).forEach { entry ->
                        when (entry) {
                            is TrayEntry.Item -> Item(localized(entry.label), onClick = entry.onClick)
                            TrayEntry.Separator -> Separator()
                        }
                    }
                },
            )
            LaunchedEffect(Unit) {
                Notifications.flow.collect { message ->
                    val type = AppShell.notificationType(message.kind)
                    // ZOUKAN -->
                    // "Hide notification content", as on Android, and incognito mode
                    val shown = Notifications.shown(message, Notifications.hidesContentNow(), localized(DMR.strings.open_zoukan_to_see_the_details))
                    trayState.sendNotification(Notification(shown.title, shown.text, type))
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
                    onWindowClose(
                        trayAvailable = trayAvailable,
                        preferences = preferences,
                        hide = { visible = false },
                        notifyStillRunning = {
                            trayState.sendNotification(
                                Notification(localized(DMR.strings.zoukan_is_still_running), localized(DMR.strings.library_updates_and_downloads_go_on)),
                            )
                        },
                        quit = ::quit,
                    )
                },
                visible = visible,
                icon = icon,
                title = "Zoukan",
                state = windowState,
            ) {
                // Below this size the two-pane screens and the toolbars no longer fit
                LaunchedEffect(Unit) {
                    window.minimumSize = WindowBounds.minimumSize(WindowBounds.usableArea(window.graphicsConfiguration))
                    // The title bar and taskbar icons: one bitmap per size, so Windows picks a sharp
                    // one at every display scale instead of shrinking or stretching a single size.
                    // Window(icon) only shows the vector icon until then: Compose sets it again only
                    // when the painter changes, and it is remembered
                    window.iconImages = AppIcon.windowImages()
                    // ZOUKAN -->
                    // Settings > Security and privacy > Secure screen
                    zoukan.desktop.security.SecureScreen.attach(window)
                    // ZOUKAN <--
                }
                CompositionLocalProvider(LocalWindow provides this, LocalWindowState provides windowState) {
                    ZoukanContent(preferences, firstRun = true)
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
                        Toasts.show(localized(DMR.strings.zoukan_closed_unexpectedly_last_time_the))
                    }
                }
            }
        }
    }
}

private fun showAlreadyRunning() = showStartupMessage(localized(DMR.strings.zoukan_is_already_running_open_it))

private fun showCommandRunning(command: String) =
    showStartupMessage(localized(DMR.strings.a_zoukan_command_is_using_the_data_folder, command))

private fun showStartupMessage(message: String) {
    if (java.awt.GraphicsEnvironment.isHeadless()) {
        System.err.println(message)
    } else {
        javax.swing.JOptionPane.showMessageDialog(null, message, "Zoukan", javax.swing.JOptionPane.INFORMATION_MESSAGE)
    }
}

/** What the app needs besides the runtime: the image loader and the extensions. */
fun startUi(dirs: ZoukanDirs) {
    Images.init(dirs.covers, dirs.images)
    // NetworkHelper first, so the cookie store and Chromium hooks are in place
    Injekt.get<NetworkHelper>()
    // Injekt's singleton factories are not thread-safe: create the database and the managers here,
    // before the UI and the extension loading below ask for them from other threads (two threads
    // creating the database at once would each make a key)
    Injekt.get<DatabaseHandler>()
    Injekt.get<SourceManager>()
    val manager = Injekt.get<DesktopExtensionManager>()
    AppScope.launch {
        zoukan.desktop.app.runCatchingCancellable { manager.init() }
            .onFailure { logcat(LogPriority.ERROR, it) { "Loading extensions failed" } }
        zoukan.desktop.app.runCatchingCancellable { manager.findAvailableExtensions() }
    }
    // Only the kind: the title and text can name what is read, and the log's end goes into crash
    // reports, even with "Hide notification content" on
    ServiceScope.launch { Notifications.flow.collect { logcat { "Notification: ${it.kind}" } } }
    // The saved download queue, then the scheduled library update and automatic backups
    val downloads = Injekt.get<DownloadManager>()
    AppScope.launch {
        Injekt.get<SourceManager>().isInitialized.first { it }
        zoukan.desktop.app.runCatchingCancellable { downloads.restore() }.onFailure { logcat(LogPriority.ERROR, it) { "Restoring the download queue failed" } }
    }
    Injekt.get<BackgroundJobs>().start(ServiceScope)
    // The daily update check (Phase 7)
    Updates.start(ServiceScope)
}

/**
 * The window's content: theme, navigation and messages. [firstRun] shows the first-run setup and
 * the What's new dialog after an update; tests leave it off.
 */
@Composable
fun ZoukanContent(preferences: DesktopPreferences, firstRun: Boolean = false, onNavigator: (Navigator) -> Unit = {}) {
    var navigatorRef by remember { mutableStateOf<Navigator?>(null) }
    // ZOUKAN -->
    val appLock = remember { Injekt.get<zoukan.desktop.security.AppLock>() }
    val locked by appLock.locked.collectAsState()
    // ZOUKAN <--
    // ZOUKAN -->
    // The app's keyboard shortcuts are handled here, on the content, so the app and the tests share
    // one handler. The root takes the focus when nothing else has it, so keys reach the handler
    // with nothing clicked yet; a focused child (a text field, the reader) gets a key first
    val rootFocus = remember { FocusRequester() }
    // True while the root or something inside it has the focus. A focused field that leaves the
    // composition (its screen was closed) drops the focus; the root then takes it back
    var rootHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(locked, rootHasFocus) { if (AppShell.rootTakesFocus(locked, rootHasFocus)) runCatching { rootFocus.requestFocus() } }
    // ZOUKAN <--
    val snackbar = remember { SnackbarHostState() }
    val messages = remember { AppMessages() }
    val home = rememberSaveable(saver = HomeController.Saver) { HomeController() }
    val shortcuts = remember(home) {
        ShortcutController(
            selectTab = home::selectTab,
            openSettings = home::openSettings,
        )
    }
    DisposableEffect(home) {
        HomeScreen.register(home)
        onDispose { HomeScreen.unregister(home) }
    }
    ZoukanTheme(remember { Injekt.get<eu.kanade.domain.ui.UiPreferences>() }) {
        Surface(
            Modifier.fillMaxSize().onPointerEvent(PointerEventType.Press) { event ->
                // The mouse's back button
                // ZOUKAN -->
                // Not while the app lock covers the window
                shortcuts.mouseBack(event.button, appLock.locked.value, navigatorRef)
                // ZOUKAN <--
            }
                // ZOUKAN -->
                .onKeyEvent { shortcuts.handle(it, navigatorRef) }
                .onFocusChanged { rootHasFocus = it.hasFocus }
                .focusRequester(rootFocus)
                .focusable(),
            // ZOUKAN <--
            color = MaterialTheme.colorScheme.background,
        ) {
            LaunchedEffect(Unit) {
                // ZOUKAN -->
                // Messages wait for the unlock: the lock shows nothing of the app. A screen can hold
                // them back too (the reader); the newest shows when it lets go
                showMessages(Toasts.flow, snackbar) {
                    appLock.locked.first { !it }
                    messages.held.first { it == 0 }
                }
                // ZOUKAN <--
            }
            // ZOUKAN -->
            LaunchedEffect(locked) { if (locked) snackbar.currentSnackbarData?.dismiss() }
            // A message already shown when a screen holds them back goes too
            val held by messages.held.collectAsState()
            LaunchedEffect(held > 0) { if (held > 0) snackbar.currentSnackbarData?.dismiss() }
            // ZOUKAN <--
            CompositionLocalProvider(
                LocalAppMessages provides messages,
                LocalHomeController provides home,
                LocalShortcuts provides shortcuts,
            ) {
                Box(Modifier.fillMaxSize()) {
                    // ZOUKAN -->
                    // The downloaded-only and incognito bars above every screen, as Android's AppStateBanners
                    Column(Modifier.fillMaxSize()) {
                        if (!locked) AppStateBanners()
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            Navigator(HomeScreen) { navigator ->
                                navigatorRef = navigator
                                onNavigator(navigator)
                                DisposableEffect(navigator, home) {
                                    HomeScreen.bindNavigator(navigator, home)
                                    onDispose { HomeScreen.unbindNavigator(navigator, home) }
                                }
                                // While locked, the screens leave the composition, so none of their
                                // controls, dialogs or menus can be clicked or reached with Tab (dialogs
                                // are layers above the lock). The navigator stays: the back stack, the
                                // screen models and the screens' saved state (tab, scroll) are kept, and
                                // the unlock returns to the same screen. Plain `remember` state is not:
                                // open dialogs, unsaved text, selection mode and coroutines in a
                                // composable's own scope are lost, where Android keeps the whole activity
                                // under its unlock screen (see PARITY.md, App lock)
                                if (!locked) FadeTransition(navigator)
                            }
                        }
                    }
                    // ZOUKAN <--
                    // The first-run setup covers the app, but not its messages
                    if (!locked) {
                        if (firstRun) zoukan.desktop.ui.onboarding.FirstRun(preferences) { navigatorRef }
                        zoukan.desktop.ui.onboarding.ReopenedOnboarding { navigatorRef }
                    }
                    SnackbarHost(
                        snackbar,
                        Modifier.align(Alignment.BottomCenter)
                            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp + messages.bottomInset)
                            .testTag("app-snackbar"),
                    )
                    // ZOUKAN -->
                    zoukan.desktop.ui.security.LockOverlay()
                    // ZOUKAN <--
                }
            }
        }
    }
}

enum class HomeTab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    Library(localized(MR.strings.label_library), Icons.Outlined.CollectionsBookmark, Icons.Filled.CollectionsBookmark),
    Updates(localized(MR.strings.label_recent_updates), Icons.Outlined.NewReleases, Icons.Filled.NewReleases),
    History(localized(MR.strings.history), Icons.Outlined.History, Icons.Filled.History),
    Browse(localized(MR.strings.browse), Icons.Outlined.Explore, Icons.Filled.Explore),
    More(localized(MR.strings.label_more), Icons.Outlined.MoreHoriz, Icons.Filled.MoreHoriz),
}

/** The main screen: a navigation rail with the five tabs. */
object HomeScreen : cafe.adriel.voyager.core.screen.Screen {
    private fun readResolve(): Any = HomeScreen

    private val activeControllers = mutableListOf<HomeController>()
    private val navigatorControllers = java.util.WeakHashMap<Navigator, HomeController>()
    private var current: HomeTabState? = null
    private var pending: HomeTab? = null

    /** Lets other screens switch the tab, for example "Browse" from an empty library. Kept until the home screen shows. */
    fun selectTab(tab: HomeTab) {
        val controller = activeControllers.lastOrNull()
        if (controller != null) {
            controller.selectTab(tab)
        } else {
            current?.select(tab) ?: run { pending = tab }
        }
    }

    /** The tab the home screen shows; null before it shows. For the tests. */
    internal val selectedTab: HomeTab?
        get() = activeControllers.lastOrNull()?.tabs?.selected ?: current?.selected

    internal fun register(controller: HomeController) {
        if (controller !in activeControllers) activeControllers += controller
        pending?.let { controller.selectTab(it) }
        pending = null
    }

    internal fun unregister(controller: HomeController) {
        activeControllers -= controller
        if (activeControllers.isEmpty() && current == null) {
            pending = null
        }
    }

    internal fun bindNavigator(navigator: Navigator, controller: HomeController) {
        navigatorControllers[navigator] = controller
    }

    internal fun unbindNavigator(navigator: Navigator, controller: HomeController) {
        if (navigatorControllers[navigator] === controller) {
            navigatorControllers.remove(navigator)
        }
    }

    internal fun controllerFor(navigator: Navigator): HomeController? =
        navigatorControllers[navigator] ?: activeControllers.lastOrNull()

    /** For the home screen's own effect and tests. */
    internal fun register(state: HomeTabState) {
        current = state
        pending?.let { state.select(it) }
        pending = null
    }

    /** For the home screen's own effect and tests. */
    internal fun unregister(state: HomeTabState) {
        if (current === state) current = null
        if (activeControllers.isEmpty() && current == null) {
            pending = null
        }
    }

    // ZOUKAN -->
    /** Opens the library with [query] in its search, as Android's HomeScreen.search (Library search). */
    fun searchLibrary(query: String) {
        activeControllers.lastOrNull()?.searchLibrary(query)
    }
    // ZOUKAN <--

    /** Opens Settings (More > Settings) from anywhere: Ctrl/Cmd + comma, and tests. */
    fun openSettings(navigator: Navigator) {
        val controller = navigatorControllers[navigator] ?: activeControllers.lastOrNull()
        if (controller != null) {
            controller.openSettings(navigator)
        } else {
            navigator.popUntilRoot()
            selectTab(HomeTab.More)
            navigator.push(SettingsScreen)
        }
    }

    @Composable
    override fun Content() {
        val providedHome = LocalHomeController.current
        val home = if (providedHome != null) {
            providedHome
        } else {
            val fallbackTabs = rememberSaveable(saver = HomeTabState.Saver) { HomeTabState() }
            val fallback = remember(fallbackTabs) { HomeController(fallbackTabs) }
            DisposableEffect(fallback) {
                register(fallback)
                onDispose { unregister(fallback) }
            }
            fallback
        }
        val tabs = home.tabs
        val tab = tabs.selected
        val library = rememberScreenModel { LibraryScreenModel() }
        // ZOUKAN -->
        DisposableEffect(home, library) {
            val unbind = home.bindLibrarySearch { query ->
                tabs.select(HomeTab.Library)
                library.search(query)
            }
            onDispose(unbind)
        }
        // ZOUKAN <--
        val updates = rememberScreenModel { UpdatesScreenModel() }
        val history = rememberScreenModel { HistoryScreenModel() }
        val browse = rememberScreenModel { BrowseScreenModel() }
        val feed = rememberScreenModel { zoukan.desktop.ui.browse.FeedScreenModel() }
        val migrate = rememberScreenModel { zoukan.desktop.ui.browse.MigrateSourcesModel() }
        // ZOUKAN -->
        // Settings > Appearance > Navigation, and the new chapters badge, as on Android
        val ui = remember { Injekt.get<eu.kanade.domain.ui.UiPreferences>() }
        val libraryPreferences = remember { Injekt.get<tachiyomi.domain.library.service.LibraryPreferences>() }
        val showUpdates by remember { ui.showNavUpdates() }.collectAsState()
        val showHistory by remember { ui.showNavHistory() }.collectAsState()
        val showLabels by remember { ui.bottomBarLabels() }.collectAsState()
        val showBadge by remember { libraryPreferences.newShowUpdatesCount() }.collectAsState()
        val newUpdates by remember { libraryPreferences.newUpdatesCount() }.collectAsState()
        // Browse shows how many extensions have an update, as Android's extensionUpdatesCount
        val extensions = remember { Injekt.get<DesktopExtensionManager>() }
        val installedExtensions by extensions.installedExtensionsFlow.collectAsState()
        val extensionUpdates = installedExtensions.values.count { it.hasUpdate }
        val shownTabs = AppShell.shownTabs(showUpdates, showHistory)
        LaunchedEffect(shownTabs) { tabs.showOnly(shownTabs) }
        // Opening Updates clears the badge, as Android's Updates tab does
        LaunchedEffect(tab, newUpdates) {
            if (AppShell.clearsUpdatesBadge(tab, newUpdates)) libraryPreferences.newUpdatesCount().set(0)
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
                        val badge = AppShell.railBadge(item, showBadge, newUpdates, extensionUpdates)
                        // With labels turned off, a tooltip names the tab under the mouse
                        TooltipIf(!showLabels, item.label) {
                            NavigationRailItem(
                                selected = tab == item,
                                onClick = { tabs.select(item) },
                                icon = {
                                    androidx.compose.material3.BadgedBox(
                                        badge = { if (badge > 0) androidx.compose.material3.Badge { Text(badge.toString()) } },
                                    ) {
                                        // Without labels the icon is all there is, so it carries the tab's name
                                        Icon(
                                            if (tab == item) item.selectedIcon else item.icon,
                                            contentDescription = if (showLabels) null else item.label,
                                        )
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
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (tab) {
                    HomeTab.Library -> LibraryTab(library)
                    HomeTab.Updates -> UpdatesTab(updates)
                    HomeTab.History -> HistoryTab(history)
                    HomeTab.Browse -> BrowseTab(browse, feed, migrate)
                    HomeTab.More -> MoreTab()
                }
            }
        }
    }
}

/** The main window's decisions, apart from Compose so tests can call them. */
internal object AppShell {
    /**
     * Quitting (the tray, the window's X without close to tray, or Cmd+Q): the built-in browser
     * windows go first, so they are gone before Chromium shuts down at exit, then the app exits.
     */
    fun quit(disposeBrowsers: () -> Unit, exit: () -> Unit) {
        disposeBrowsers()
        exit()
    }

    /**
     * Hands Cmd+Q on macOS, once Chromium has taken it over, to [quit]. Returns what the window
     * runs when it leaves: the app is quitting then, and a further Cmd+Q must not let JCEF shut
     * Chromium down under the exit.
     */
    fun installQuitRequest(quit: () -> Unit): () -> Unit {
        JcefRuntime.quitRequest = quit
        return { JcefRuntime.quitRequest = JcefRuntime.QUIT_UNDERWAY }
    }

    /** The window is hidden when closed to the tray or minimized. */
    fun hidden(visible: Boolean, minimized: Boolean): Boolean = !visible || minimized

    /** The app is away (Android's background) when hidden or another app is in front. */
    fun away(hidden: Boolean, appActive: Boolean): Boolean = hidden || !appActive

    /** The tray notification's type for a message's kind. */
    fun notificationType(kind: Notifications.Kind): Notification.Type = when (kind) {
        Notifications.Kind.INFO -> Notification.Type.Info
        Notifications.Kind.WARNING -> Notification.Type.Warning
        Notifications.Kind.ERROR -> Notification.Type.Error
    }

    /** The content's root takes the focus, so the shortcuts work, when nothing in it has it and the app lock is off. */
    fun rootTakesFocus(locked: Boolean, rootHasFocus: Boolean): Boolean = !locked && !rootHasFocus

    /** The rail's tabs: Updates and History only while their Appearance > Navigation settings are on. */
    fun shownTabs(showUpdates: Boolean, showHistory: Boolean): List<HomeTab> = HomeTab.entries.filter {
        (it != HomeTab.Updates || showUpdates) && (it != HomeTab.History || showHistory)
    }

    /** The count on a rail tab's badge, 0 for none: new chapters on Updates (when shown), extension updates on Browse. */
    fun railBadge(tab: HomeTab, showBadge: Boolean, newUpdates: Int, extensionUpdates: Int): Int = when {
        tab == HomeTab.Updates && showBadge && newUpdates > 0 -> newUpdates
        tab == HomeTab.Browse && extensionUpdates > 0 -> extensionUpdates
        else -> 0
    }

    /** Opening Updates clears its badge, as Android's Updates tab does. */
    fun clearsUpdatesBadge(tab: HomeTab, newUpdates: Int): Boolean = tab == HomeTab.Updates && newUpdates != 0
}
