package zoukan.desktop.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import tachiyomi.i18n.MR
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.LocalWindowState
import zoukan.desktop.ui.common.Corners
import zoukan.desktop.ui.common.ReadingModes
import zoukan.i18n.desktop.DMR

data class ReaderScreen(val mangaId: Long, val chapterId: Long, val page: Int? = null) : Screen {
    // Ends with / because Voyager disposes every screen model whose key starts with a
    // disposed screen's key: without it, leaving manga-1 cancelled manga-12's work
    override val key: ScreenKey = "reader-$mangaId-$chapterId/"

    @Composable
    override fun Content() {
        val model = rememberScreenModel { ReaderScreenModel(mangaId, chapterId, initialPage = page) }
        val state by model.state.collectAsState()
        val menuVisible by model.menuVisible.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        val windowState = LocalWindowState.current
        val prefs = model.preferences
        val theme by prefs.readerTheme().changes().collectAsState(prefs.readerTheme().get())
        val fitMode by prefs.imageScaleType().changes().collectAsState(prefs.imageScaleType().get())
        val sidePadding by prefs.webtoonSidePadding().changes().collectAsState(prefs.webtoonSidePadding().get())
        val showPageNumber by prefs.showPageNumber().changes().collectAsState(prefs.showPageNumber().get())
        // ZOUKAN -->
        // The Automatic theme follows the app's light or dark theme, as Android's follows night mode
        val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
        val background = readerBackground(theme, dark)
        val foreground = readerForeground(theme, dark)
        val tapZones = remember { TapZonesOverlayState(prefs) }
        val pagerNavInvert by prefs.pagerNavInverted().changes().collectAsState(prefs.pagerNavInverted().get())
        val webtoonNavInvert by prefs.webtoonNavInverted().changes().collectAsState(prefs.webtoonNavInverted().get())
        // ZOUKAN <--
        val actions = remember { ReaderActions() }
        val focus = remember { FocusRequester() }
        var showKeys by remember { mutableStateOf(false) }
        // ZOUKAN -->
        var showSettings by remember { mutableStateOf(false) }
        var showChapters by remember { mutableStateOf(false) }
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
            // ZOUKAN -->
            tapZones.hide()
            // ZOUKAN <--
            // The Left and Right keys follow the taps: flipped in right to left mode or by inverted tap zones
            val invert = ReaderNavigation.Invert.of(if (webtoon) webtoonNavInvert else pagerNavInvert)
            val flipped = ReaderNavigation.flipped(
                rtl = state.readingMode == ReadingModes.RIGHT_TO_LEFT,
                invert = invert,
            )
            // The Up and Down keys follow the vertical taps too; J and K stay physical
            val verticalFlipped = ReaderNavigation.verticalFlipped(invert)
            val ctrl = event.isCtrlPressed || event.isMetaPressed
            when (event.key) {
                Key.DirectionRight -> if (event.isAltPressed) {
                    return false
                } else if (flipped) {
                    actions.previous()
                } else {
                    actions.next()
                }
                Key.DirectionLeft -> if (event.isAltPressed) {
                    return false
                } else if (flipped) {
                    actions.next()
                } else {
                    actions.previous()
                }
                Key.DirectionDown -> if (verticalFlipped) actions.up() else actions.down()
                Key.DirectionUp -> if (verticalFlipped) actions.down() else actions.up()
                Key.J -> actions.down()
                Key.K -> actions.up()
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
                Key.B -> model.toggleBookmark()
                Key.C -> showChapters = !showChapters
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
                // ZOUKAN -->
                // Any click hides the tap zones overlay, and still reaches the reader
                .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { tapZones.hide() }
                // ZOUKAN <--
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
                            TextButton(onClick = model::retryChapter) { Text(localized(MR.strings.action_retry)) }
                            TextButton(onClick = { navigator.pop() }) { Text(localized(MR.strings.action_close)) }
                        }
                    }
                }
                else -> key(state.generation) {
                    // ZOUKAN -->
                    with(ReaderFilter) {
                        Box(Modifier.fillMaxSize().readerFilter(filter)) {
                            // ZOUKAN <--
                            if (webtoon) {
                                WebtoonViewer(model, state, actions, sidePadding, background, foreground, tapZones)
                            } else {
                                PagedViewer(model, state, actions, fitMode, background, foreground, tapZones, autoBackground = theme == 3)
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
                        .background(Color.Black.copy(alpha = 0.5f), Corners.Large)
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }

            // ZOUKAN -->
            val buttons by prefs.readerBottomButtons().changes().collectAsState(prefs.readerBottomButtons().get())
            val forceHorizontal by prefs.forceHorizontalSeekbar().changes().collectAsState(prefs.forceHorizontalSeekbar().get())
            val verticalInLandscape by prefs.landscapeVerticalSeekbar().changes().collectAsState(prefs.landscapeVerticalSeekbar().get())
            val leftHanded by prefs.leftVerticalSeekbar().changes().collectAsState(prefs.leftVerticalSeekbar().get())
            val pageLayout by prefs.pageLayout().changes().collectAsState(prefs.pageLayout().get())
            ReaderAppBars(
                visible = menuVisible,
                menu = ReaderMenuState(
                    mangaTitle = state.manga?.title.orEmpty(),
                    chapterTitle = state.chapter?.name.orEmpty().let {
                        when {
                            !state.fromDisk || state.loading -> it
                            state.manga?.source == zoukan.desktop.local.LocalSource.ID -> localized(DMR.strings.chapter_local_file, it)
                            else -> localized(DMR.strings.chapter_downloaded, it)
                        }
                    },
                    bookmarked = state.chapter?.bookmark == true,
                    autoScroll = autoScroll,
                    fullscreen = windowState.placement == WindowPlacement.Fullscreen,
                    readingMode = state.readingMode,
                    fitMode = fitMode,
                    cropEnabled = if (webtoon) cropWebtoon else crop,
                    pageLayout = pageLayout,
                    buttons = buttons,
                    forceHorizontalSlider = forceHorizontal,
                    verticalSliderInLandscape = verticalInLandscape,
                    leftHandedSlider = leftHanded,
                    currentPage = state.currentPage,
                    currentPageText = currentPageText(state),
                    totalPages = state.pages.size,
                    hasPrevious = state.previousChapter != null,
                    hasNext = state.nextChapter != null,
                    generation = state.generation,
                ),
                actions = ReaderMenuActions(
                    onBack = { navigator.pop() },
                    onToggleBookmark = { model.toggleBookmark() },
                    onAutoScroll = { autoScroll = !autoScroll },
                    onKeys = { showKeys = true },
                    onFullscreen = ::toggleFullscreen,
                    onChapters = { showChapters = true },
                    onWebView = model::openChapterInWebView,
                    onBrowser = model::openChapterInBrowser,
                    onReadingMode = model::setReadingMode,
                    onFitMode = { prefs.imageScaleType().set(it) },
                    onToggleCrop = {
                        val pref = if (webtoon) prefs.cropBordersWebtoon() else prefs.cropBorders()
                        pref.set(!pref.get())
                    },
                    onPageLayout = { prefs.pageLayout().set(it) },
                    onSettings = { showSettings = true },
                    onPreviousChapter = { model.previousChapter() },
                    onNextChapter = { model.nextChapter() },
                    onPage = { actions.goTo(it) },
                    onRetryAll = model::retryAllPages,
                    onBoostPage = model::boostPage,
                ),
            )
            // ZOUKAN <--
        }

        if (showKeys) KeyboardHelp { showKeys = false }
        if (showSettings) ReaderSettingsDialog(model) { showSettings = false }
        // ZOUKAN -->
        if (showChapters) {
            ReaderChapterListDialog(
                chapters = state.navigable,
                current = state.chapter?.id,
                onOpen = {
                    showChapters = false
                    model.openChapter(it)
                },
                onBookmark = model::toggleBookmark,
                onDismiss = { showChapters = false },
            )
        }
        LaunchedEffect(showChapters, showSettings) { if (!showChapters && !showSettings) focus.requestFocus() }
        // ZOUKAN <--
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
private fun KeyboardHelp(onDismiss: () -> Unit) {
    val keys = listOf(
        "Right / Left" to localized(DMR.strings.next_previous_page_flipped_in_right),
        "Space, Page Down / Shift+Space, Page Up" to localized(DMR.strings.next_previous_page),
        "Down / Up" to localized(DMR.strings.scroll_a_tall_or_zoomed_page),
        "Home / End" to localized(DMR.strings.first_last_page),
        "N or ] / P or [" to localized(DMR.strings.next_previous_chapter),
        "Ctrl + mouse wheel, + / -" to localized(DMR.strings.zoom_in_out),
        "0" to localized(DMR.strings.reset_zoom),
        localized(DMR.strings.double_click) to localized(DMR.strings.zoom_to_2x_and_back),
        localized(DMR.strings.click_left_right_third) to localized(DMR.strings.previous_next_page),
        localized(DMR.strings.click_the_middle_m_or_enter) to localized(DMR.strings.show_or_hide_the_menu),
        "F or F11" to localized(DMR.strings.full_screen),
        localized(DMR.strings.escape) to localized(DMR.strings.close_the_menu_leave_full_screen),
        "A" to localized(DMR.strings.start_or_stop_auto_scroll),
        "S" to localized(DMR.strings.reader_settings),
        "B" to localized(DMR.strings.bookmark_the_chapter_or_remove_its),
        "C" to localized(DMR.strings.chapter_list),
        "R / Shift+R" to localized(DMR.strings.retry_failed_pages_load_the_shown),
        "D" to localized(DMR.strings.shift_double_pages_by_one),
        localized(DMR.strings.right_click_or_long_press_a) to localized(DMR.strings.set_it_as_the_cover_copy),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized(DMR.strings.reader_shortcuts)) },
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
        confirmButton = { TextButton(onClick = onDismiss) { Text(localized(MR.strings.action_close)) } },
    )
}
