package zoukan.desktop.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ChromeReaderMode
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import zoukan.desktop.ui.LocalWindowState
import zoukan.desktop.ui.common.FitModes
import zoukan.desktop.ui.common.ReadingModes
import kotlin.math.roundToInt

data class ReaderScreen(val mangaId: Long, val chapterId: Long) : Screen {
    // Ends with / because Voyager disposes every screen model whose key starts with a
    // disposed screen's key: without it, leaving manga-1 cancelled manga-12's work
    override val key: ScreenKey = "reader-$mangaId-$chapterId/"

    @Composable
    override fun Content() {
        val model = rememberScreenModel { ReaderScreenModel(mangaId, chapterId) }
        val state by model.state.collectAsState()
        val menuVisible by model.menuVisible.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        val windowState = LocalWindowState.current
        val prefs = model.preferences
        val theme by prefs.readerTheme().changes().collectAsState(prefs.readerTheme().get())
        val fitMode by prefs.readerFitMode().changes().collectAsState(prefs.readerFitMode().get())
        val sidePadding by prefs.webtoonSidePadding().changes().collectAsState(prefs.webtoonSidePadding().get())
        val showPageNumber by prefs.showPageNumber().changes().collectAsState(prefs.showPageNumber().get())
        val background = readerBackground(theme)
        val foreground = readerForeground(theme)
        val actions = remember { ReaderActions() }
        val focus = remember { FocusRequester() }
        var showKeys by remember { mutableStateOf(false) }
        // ZOUKAN -->
        var showSettings by remember { mutableStateOf(false) }
        var autoScroll by remember { mutableStateOf(false) }
        val autoScrollInterval by prefs.autoscrollInterval().changes().collectAsState(prefs.autoscrollInterval().get())
        val filter = ReaderFilter.collect(prefs)
        val crop by prefs.cropBorders().changes().collectAsState(prefs.cropBorders().get())
        val cropWebtoon by prefs.cropBordersWebtoon().changes().collectAsState(prefs.cropBordersWebtoon().get())
        val cropKey = remember { mutableStateOf<Pair<Boolean, Boolean>?>(null) }
        LaunchedEffect(crop, cropWebtoon) {
            if (cropKey.value != null && cropKey.value != (crop to cropWebtoon)) model.redecodePages()
            cropKey.value = crop to cropWebtoon
        }
        LaunchedEffect(autoScroll, autoScrollInterval, state.generation) {
            while (autoScroll) {
                val millis = (autoScrollInterval.coerceAtLeast(0.5f) * 1000).toLong()
                // SY's "Smooth auto scroll" glides in webtoon modes; the paged modes turn pages
                val smooth = actions.smoothScroll.takeIf { prefs.smoothAutoScroll().get() }
                if (smooth != null && !model.menuVisible.value) smooth(millis)
                kotlinx.coroutines.delay(millis)
                if (smooth == null && !model.menuVisible.value) actions.next()
            }
        }
        // ZOUKAN <--
        val webtoon = state.readingMode == ReadingModes.WEBTOON || state.readingMode == ReadingModes.CONTINUOUS_VERTICAL

        fun toggleFullscreen() {
            windowState.placement = if (windowState.placement == WindowPlacement.Fullscreen) {
                WindowPlacement.Floating
            } else {
                WindowPlacement.Fullscreen
            }
        }

        fun onKey(event: KeyEvent): Boolean {
            if (event.type != KeyEventType.KeyDown) return false
            val rtl = state.readingMode == ReadingModes.RIGHT_TO_LEFT
            val ctrl = event.isCtrlPressed || event.isMetaPressed
            when (event.key) {
                Key.DirectionRight -> if (event.isAltPressed) {
                    return false
                } else if (rtl) {
                    actions.previous()
                } else {
                    actions.next()
                }
                Key.DirectionLeft -> if (event.isAltPressed) {
                    return false
                } else if (rtl) {
                    actions.next()
                } else {
                    actions.previous()
                }
                Key.DirectionDown, Key.J -> actions.down()
                Key.DirectionUp, Key.K -> actions.up()
                Key.Spacebar -> if (event.isShiftPressed) actions.previous() else actions.next()
                Key.PageDown -> actions.next()
                Key.PageUp -> actions.previous()
                Key.MoveHome -> actions.first()
                Key.MoveEnd -> actions.last()
                Key.N, Key.RightBracket -> model.nextChapter()
                Key.P, Key.LeftBracket -> model.previousChapter()
                Key.Equals, Key.Plus, Key.NumPadAdd -> actions.zoomIn()
                Key.Minus, Key.NumPadSubtract -> actions.zoomOut()
                Key.Zero, Key.NumPad0 -> actions.zoomReset()
                Key.M, Key.Enter -> model.toggleMenu()
                Key.F, Key.F11 -> if (ctrl) return false else toggleFullscreen()
                Key.Slash -> showKeys = !showKeys
                // ZOUKAN -->
                Key.A -> autoScroll = !autoScroll
                Key.S -> showSettings = !showSettings
                Key.R -> if (event.isShiftPressed) model.boostPage() else model.retryAllPages()
                Key.D -> model.toggleShiftDoublePages()
                // ZOUKAN <--
                Key.Escape -> when {
                    showKeys -> showKeys = false
                    menuVisible -> model.setMenu(false)
                    windowState.placement == WindowPlacement.Fullscreen -> windowState.placement = WindowPlacement.Floating
                    else -> navigator.pop()
                }
                else -> return false
            }
            return true
        }

        LaunchedEffect(Unit) { focus.requestFocus() }
        LaunchedEffect(menuVisible, state.generation) { focus.requestFocus() }

        Box(
            Modifier
                .fillMaxSize()
                .background(background)
                .onPreviewKeyEvent(::onKey)
                .focusRequester(focus)
                .focusable(),
        ) {
            when {
                state.loading -> PageLoading(state.currentPage + 1, foreground, Modifier.fillMaxSize())
                state.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.error!!, color = foreground)
                        Row {
                            TextButton(onClick = model::retryChapter) { Text("Retry") }
                            TextButton(onClick = { navigator.pop() }) { Text("Close") }
                        }
                    }
                }
                else -> key(state.generation) {
                    // ZOUKAN -->
                    with(ReaderFilter) {
                        Box(Modifier.fillMaxSize().readerFilter(filter)) {
                            // ZOUKAN <--
                            if (webtoon) {
                                WebtoonViewer(model, state, actions, sidePadding, background, foreground)
                            } else {
                                PagedViewer(model, state, actions, fitMode, background, foreground)
                            }
                        }
                    }
                }
            }

            // ZOUKAN -->
            ReaderEffects(model, state)
            // ZOUKAN <--

            if (showPageNumber && !menuVisible && state.pages.isNotEmpty()) {
                Text(
                    state.pageNumber,
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }

            AnimatedVisibility(menuVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
                TopBar(state, model, fitMode, webtoon, windowState.placement == WindowPlacement.Fullscreen, ::toggleFullscreen, { showKeys = true }, { showSettings = true }, autoScroll, { autoScroll = !autoScroll }) {
                    navigator.pop()
                }
            }
            AnimatedVisibility(menuVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
                BottomBar(state, model, actions)
            }
        }

        if (showKeys) KeyboardHelp { showKeys = false }
        if (showSettings) ReaderSettingsDialog(model) { showSettings = false }
        // ZOUKAN -->
        val pageActions by model.pageActions.collectAsState()
        pageActions?.let { target ->
            PageActionsDialog(
                target = target,
                onDismiss = model::closePageActions,
                onSetAsCover = model::setPageAsCover,
                onCopy = model::copyPage,
                onSave = model::savePage,
                onCopyCombined = { model.copyCombined(target) },
                onSaveCombined = { model.saveCombined(target) },
            )
        }
        LaunchedEffect(pageActions) { if (pageActions == null) focus.requestFocus() }
        // ZOUKAN <--
    }
}

@Composable
private fun TopBar(
    state: ReaderScreenModel.State,
    model: ReaderScreenModel,
    fitMode: Int,
    webtoon: Boolean,
    fullscreen: Boolean,
    onFullscreen: () -> Unit,
    onKeys: () -> Unit,
    onSettings: () -> Unit,
    autoScroll: Boolean,
    onAutoScroll: () -> Unit,
    onBack: () -> Unit,
) {
    var modeMenu by remember { mutableStateOf(false) }
    var fitMenu by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(state.manga?.title.orEmpty(), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    state.chapter?.name.orEmpty() + when {
                        !state.fromDisk || state.loading -> ""
                        state.manga?.source == zoukan.desktop.local.LocalSource.ID -> " · Local file"
                        else -> " · Downloaded"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                TextButton(onClick = { modeMenu = true }) {
                    Icon(Icons.AutoMirrored.Filled.ChromeReaderMode, null)
                    Spacer(Modifier.width(6.dp))
                    Text(ReadingModes.label(state.readingMode))
                }
                DropdownMenu(modeMenu, onDismissRequest = { modeMenu = false }) {
                    ReadingModes.all.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(ReadingModes.label(mode)) },
                            leadingIcon = { RadioButton(selected = state.readingMode == mode, onClick = null) },
                            onClick = {
                                model.setReadingMode(mode)
                                modeMenu = false
                            },
                        )
                    }
                }
            }
            if (!webtoon) {
                Box {
                    TextButton(onClick = { fitMenu = true }) {
                        Icon(Icons.Default.AspectRatio, null)
                        Spacer(Modifier.width(6.dp))
                        Text(FitModes.label(fitMode))
                    }
                    DropdownMenu(fitMenu, onDismissRequest = { fitMenu = false }) {
                        FitModes.all.forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(FitModes.label(mode)) },
                                leadingIcon = { RadioButton(selected = fitMode == mode, onClick = null) },
                                onClick = {
                                    model.preferences.readerFitMode().set(mode)
                                    fitMenu = false
                                },
                            )
                        }
                    }
                }
            }
            // ZOUKAN -->
            IconButton(onClick = onAutoScroll) {
                Icon(if (autoScroll) Icons.Default.PauseCircle else Icons.Default.PlayCircle, if (autoScroll) "Stop auto scroll" else "Auto scroll")
            }
            IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Reader settings") }
            // ZOUKAN <--
            IconButton(onClick = onKeys) { Icon(Icons.Default.Keyboard, "Keyboard shortcuts") }
            IconButton(onClick = onFullscreen) {
                Icon(if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, "Full screen")
            }
        }
    }
}

@Composable
private fun BottomBar(state: ReaderScreenModel.State, model: ReaderScreenModel, actions: ReaderActions) {
    val rtl = state.readingMode == ReadingModes.RIGHT_TO_LEFT
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { model.previousChapter() }, enabled = state.previousChapter != null) {
                Icon(Icons.Default.SkipPrevious, "Previous chapter")
            }
            Text("${state.currentPage + 1}", modifier = Modifier.width(40.dp), style = MaterialTheme.typography.labelLarge)
            val count = state.pages.size
            if (count > 1) {
                var dragging by remember(state.generation) { mutableStateOf(false) }
                var value by remember(state.generation) { mutableFloatStateOf(state.currentPage.toFloat()) }
                if (!dragging) value = state.currentPage.toFloat()
                Slider(
                    value = if (rtl) (count - 1) - value else value,
                    onValueChange = {
                        dragging = true
                        value = if (rtl) (count - 1) - it else it
                    },
                    onValueChangeFinished = {
                        dragging = false
                        actions.goTo(value.roundToInt())
                    },
                    valueRange = 0f..(count - 1).toFloat(),
                    steps = (count - 2).coerceAtLeast(0),
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            Text("$count", modifier = Modifier.width(40.dp).padding(start = 8.dp), style = MaterialTheme.typography.labelLarge)
            IconButton(onClick = { model.nextChapter() }, enabled = state.nextChapter != null) {
                Icon(Icons.Default.SkipNext, "Next chapter")
            }
        }
    }
}

@Composable
private fun KeyboardHelp(onDismiss: () -> Unit) {
    val keys = listOf(
        "Right / Left" to "Next / previous page (flipped in right to left mode)",
        "Space, Page Down / Shift+Space, Page Up" to "Next / previous page",
        "Down / Up" to "Scroll a tall or zoomed page, then turn the page",
        "Home / End" to "First / last page",
        "N or ] / P or [" to "Next / previous chapter",
        "Ctrl + mouse wheel, + / -" to "Zoom in / out",
        "0" to "Reset zoom",
        "Double-click" to "Zoom to 2x and back",
        "Click left / right third" to "Previous / next page",
        "Click the middle, M or Enter" to "Show or hide the menu",
        "F or F11" to "Full screen",
        "Escape" to "Close the menu, leave full screen, or close the reader",
        "A" to "Start or stop auto scroll",
        "S" to "Reader settings",
        "R / Shift+R" to "Retry failed pages / load the shown page first",
        "D" to "Shift double pages by one",
        "Right-click or long-press a page" to "Set it as the cover, copy it or save it",
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reader shortcuts") },
        text = {
            Column {
                keys.forEach { (key, what) ->
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Text(key, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(260.dp))
                        Text(what, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
