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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.i18n.MR
import tachiyomi.i18n.action_close
import tachiyomi.i18n.action_retry
import zoukan.desktop.app.ReadingModes
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.LocalWindowState
import zoukan.desktop.ui.common.Corners
import zoukan.desktop.ui.common.HoldMessages
import zoukan.desktop.ui.common.LocalReducedMotion
import zoukan.desktop.ui.common.OverlayColors
import zoukan.desktop.ui.common.Spacing
import zoukan.desktop.ui.common.reducedMotion
import zoukan.i18n.desktop.DMR
import zoukan.i18n.desktop.bookmark_the_chapter_or_remove_its
import zoukan.i18n.desktop.chapter_downloaded
import zoukan.i18n.desktop.chapter_list
import zoukan.i18n.desktop.chapter_local_file
import zoukan.i18n.desktop.click_left_right_third
import zoukan.i18n.desktop.click_the_middle_m_or_enter
import zoukan.i18n.desktop.close_the_menu_leave_full_screen
import zoukan.i18n.desktop.double_click
import zoukan.i18n.desktop.escape
import zoukan.i18n.desktop.first_last_page
import zoukan.i18n.desktop.full_screen
import zoukan.i18n.desktop.next_previous_chapter
import zoukan.i18n.desktop.next_previous_page
import zoukan.i18n.desktop.next_previous_page_flipped_in_right
import zoukan.i18n.desktop.previous_next_page
import zoukan.i18n.desktop.reader_key_auto_scroll
import zoukan.i18n.desktop.reader_key_bookmark
import zoukan.i18n.desktop.reader_key_chapter
import zoukan.i18n.desktop.reader_key_chapters
import zoukan.i18n.desktop.reader_key_down_up
import zoukan.i18n.desktop.reader_key_full_screen
import zoukan.i18n.desktop.reader_key_home_end
import zoukan.i18n.desktop.reader_key_retry
import zoukan.i18n.desktop.reader_key_right_left
import zoukan.i18n.desktop.reader_key_settings
import zoukan.i18n.desktop.reader_key_shift_pages
import zoukan.i18n.desktop.reader_key_space_page
import zoukan.i18n.desktop.reader_key_zoom
import zoukan.i18n.desktop.reader_key_zoom_reset
import zoukan.i18n.desktop.reader_page_description
import zoukan.i18n.desktop.reader_settings
import zoukan.i18n.desktop.reader_shortcuts
import zoukan.i18n.desktop.reset_zoom
import zoukan.i18n.desktop.retry_failed_pages_load_the_shown
import zoukan.i18n.desktop.right_click_or_long_press_a
import zoukan.i18n.desktop.scroll_a_tall_or_zoomed_page
import zoukan.i18n.desktop.set_it_as_the_cover_copy
import zoukan.i18n.desktop.shift_double_pages_by_one
import zoukan.i18n.desktop.show_or_hide_the_menu
import zoukan.i18n.desktop.start_or_stop_auto_scroll
import zoukan.i18n.desktop.zoom_in_out
import zoukan.i18n.desktop.zoom_to_2x_and_back

/** What a viewer draws: everything but the current page, which turns on every page change. */
@Immutable
data class ViewerContent(
    val pages: List<ReaderPage>,
    val chapter: Chapter?,
    val previousChapter: Chapter?,
    val nextChapter: Chapter?,
    val readingMode: Int,
    val startPage: Int,
    val widePages: Set<Int>,
    val shiftDoublePages: Boolean,
    val generation: Int,
)

data class ReaderScreen(val mangaId: Long, val chapterId: Long, val page: Int? = null) : Screen {
    // Ends with / because Voyager disposes every screen model whose key starts with a
    // disposed screen's key: without it, leaving manga-1 cancelled manga-12's work
    override val key: ScreenKey = "reader-$mangaId-$chapterId/"

    @Composable
    override fun Content() {
        // The app's messages wait until the reader closes; only the newest shows then
        HoldMessages()
        val model = rememberScreenModel { ReaderScreenModel(mangaId, chapterId, initialPage = page) }
        val state by model.state.collectAsState()
        val overlay by model.overlay.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        val windowState = LocalWindowState.current
        val prefs = model.preferences
        val config = ReaderConfig.collect(prefs)
        // ZOUKAN -->
        // The Automatic theme follows the app's light or dark theme, as Android's follows night mode
        val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
        val background = readerBackground(config.theme, dark)
        val foreground = readerForeground(config.theme, dark)
        val tapZones = remember { TapZonesOverlayState(prefs) }
        // ZOUKAN <--
        val actions = remember { ReaderActions() }
        val focus = remember { FocusRequester() }
        // ZOUKAN -->
        var autoScroll by remember { mutableStateOf(false) }
        val latestSmoothAutoScroll by rememberUpdatedState(config.smoothAutoScroll)
        val cropKey = remember { mutableStateOf<Triple<Boolean, Boolean, Boolean>?>(null) }
        LaunchedEffect(config.cropPaged, config.cropWebtoon, config.cropContinuousVertical) {
            val key = config.cropKey
            if (cropKey.value != null && cropKey.value != key) model.redecodePages()
            cropKey.value = key
        }
        LaunchedEffect(autoScroll, config.autoScrollInterval, state.generation) {
            while (autoScroll) {
                val millis = (config.autoScrollInterval.coerceAtLeast(0.5f) * 1000).toLong()
                // SY's "Smooth auto scroll" glides in webtoon modes; the paged modes turn pages
                val smooth = actions.smoothScroll.takeIf { latestSmoothAutoScroll }
                if (smooth != null && model.overlay.value == ReaderOverlay.Hidden) smooth(millis)
                kotlinx.coroutines.delay(millis)
                if (smooth == null && model.overlay.value == ReaderOverlay.Hidden) actions.next()
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
            val invert = ReaderNavigation.Invert.of(config.navInvert(webtoon))
            val flipped = ReaderNavigation.flipped(
                rtl = state.readingMode == ReadingModes.RIGHT_TO_LEFT,
                invert = invert,
            )
            // The Up and Down keys follow the vertical taps too; J and K stay physical
            val verticalFlipped = ReaderNavigation.verticalFlipped(invert)
            val ctrl = event.isCtrlPressed || event.isMetaPressed
            val action = ReaderKeys.actionFor(event.key, ctrl, event.isShiftPressed, event.isAltPressed, flipped, verticalFlipped) ?: return false
            when (action) {
                ReaderKeys.Action.NEXT_PAGE -> actions.next()
                ReaderKeys.Action.PREVIOUS_PAGE -> actions.previous()
                ReaderKeys.Action.SCROLL_DOWN -> actions.down()
                ReaderKeys.Action.SCROLL_UP -> actions.up()
                ReaderKeys.Action.FIRST_PAGE -> actions.first()
                ReaderKeys.Action.LAST_PAGE -> actions.last()
                ReaderKeys.Action.NEXT_CHAPTER -> model.nextChapter()
                ReaderKeys.Action.PREVIOUS_CHAPTER -> model.previousChapter()
                ReaderKeys.Action.ZOOM_IN -> actions.zoomIn()
                ReaderKeys.Action.ZOOM_OUT -> actions.zoomOut()
                ReaderKeys.Action.ZOOM_RESET -> actions.zoomReset()
                ReaderKeys.Action.TOGGLE_MENU -> model.toggleMenu()
                ReaderKeys.Action.TOGGLE_FULLSCREEN -> toggleFullscreen()
                ReaderKeys.Action.TOGGLE_HELP -> model.toggleHelp()
                // ZOUKAN -->
                ReaderKeys.Action.TOGGLE_AUTO_SCROLL -> autoScroll = !autoScroll
                ReaderKeys.Action.TOGGLE_SETTINGS -> model.toggleSettings()
                ReaderKeys.Action.TOGGLE_BOOKMARK -> model.toggleBookmark()
                ReaderKeys.Action.TOGGLE_CHAPTERS -> model.toggleChapters()
                ReaderKeys.Action.RETRY_PAGES -> model.retryAllPages()
                ReaderKeys.Action.BOOST_PAGE -> model.boostPage()
                ReaderKeys.Action.SHIFT_DOUBLE_PAGES -> model.toggleShiftDoublePages()
                // ZOUKAN <--
                ReaderKeys.Action.ESCAPE -> when {
                    model.overlay.value != ReaderOverlay.Hidden -> model.dismissOverlay()
                    windowState.placement == WindowPlacement.Fullscreen -> windowState.placement = WindowPlacement.Floating
                    else -> navigator.pop()
                }
            }
            return true
        }

        LaunchedEffect(Unit) { focus.requestFocus() }
        // While an overlay is open the focus stays where the user put it, so its buttons and slider work
        LaunchedEffect(overlay, state.generation) { if (overlay == ReaderOverlay.Hidden) focus.requestFocus() }

        // ZOUKAN -->
        // The viewers get only what they draw, not the current page, so a page turn leaves them alone
        val viewerContent = remember(
            state.pages, state.chapter, state.previousChapter, state.nextChapter, state.readingMode,
            state.startPage, state.widePages, state.shiftDoublePages, state.generation,
        ) {
            ViewerContent(
                pages = state.pages,
                chapter = state.chapter,
                previousChapter = state.previousChapter,
                nextChapter = state.nextChapter,
                readingMode = state.readingMode,
                startPage = state.startPage,
                widePages = state.widePages,
                shiftDoublePages = state.shiftDoublePages,
                generation = state.generation,
            )
        }
        val menuActions = remember(model, navigator, windowState, prefs, actions) {
            ReaderMenuActions(
                onBack = { navigator.pop() },
                onToggleBookmark = { model.toggleBookmark() },
                onAutoScroll = { autoScroll = !autoScroll },
                onKeys = model::openHelp,
                onFullscreen = { toggleFullscreen() },
                onChapters = model::openChapters,
                onWebView = model::openChapterInWebView,
                onBrowser = model::openChapterInBrowser,
                onReadingMode = model::setReadingMode,
                onFitMode = { prefs.imageScaleType().set(it) },
                onToggleCrop = {
                    val pref = prefs.cropBordersFor(model.state.value.readingMode)
                    pref.set(!pref.get())
                },
                onPageLayout = { prefs.pageLayout().set(it) },
                onSettings = model::openSettings,
                onPreviousChapter = { model.previousChapter() },
                onNextChapter = { model.nextChapter() },
                onPage = { actions.goTo(it) },
                onRetryAll = model::retryAllPages,
                onBoostPage = model::boostPage,
            )
        }
        // Read once: every reader composable below follows the system's "reduce motion" setting
        val reduceMotion by reducedMotion()
        CompositionLocalProvider(LocalReducedMotion provides reduceMotion) {
            // ZOUKAN <--
            Box(
                Modifier
                    .fillMaxSize()
                    .background(background)
                    // ZOUKAN -->
                    // Any click hides the tap zones overlay, and still reaches the reader
                    .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { tapZones.hide() }
                    // ZOUKAN <--
                    // Bubbling pass: a focused button, slider or menu gets its keys first
                    .onKeyEvent(::onKey)
                    .focusRequester(focus)
                    .focusable()
                    // ZOUKAN -->
                    .semantics {
                        with(ReaderKeys) {
                            readerState = {
                                val currentOverlay = model.overlay.value
                                ReaderKeys.State(
                                    page = model.state.value.currentPage,
                                    menu = currentOverlay is ReaderOverlay.Menu,
                                    zoom = actions.zoom(),
                                    fullscreen = windowState.placement == WindowPlacement.Fullscreen,
                                    help = currentOverlay is ReaderOverlay.Help,
                                    autoScroll = autoScroll,
                                    settings = currentOverlay is ReaderOverlay.Settings,
                                    chapters = currentOverlay is ReaderOverlay.Chapters,
                                    hasNext = model.state.value.nextChapter != null,
                                    hasPrevious = model.state.value.previousChapter != null,
                                    ready = model.state.value.let { !it.loading && it.error == null && it.pages.isNotEmpty() },
                                )
                            }
                        }
                    },
                // ZOUKAN <--
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
                            val pageDescription = localized(DMR.strings.reader_page_description, state.pageNumber)
                            Box(Modifier.fillMaxSize().readerFilter(config.filter).semantics { contentDescription = pageDescription }) {
                                // ZOUKAN <--
                                if (webtoon) {
                                    WebtoonViewer(model, viewerContent, actions, config.webtoonSidePadding, background, foreground, tapZones, config = config.webtoon)
                                } else {
                                    PagedViewer(model, viewerContent, actions, config.fitMode, background, foreground, tapZones, autoBackground = config.theme == 3, config = config.paged)
                                }
                            }
                        }
                    }
                }

                // ZOUKAN -->
                ReaderEffects(model, state)
                // ZOUKAN <--

                if (config.showPageNumber && overlay == ReaderOverlay.Hidden && state.pages.isNotEmpty()) {
                    Text(
                        state.pageNumber,
                        color = OverlayColors.OnScrim,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = Spacing.Small)
                            .background(OverlayColors.Scrim, Corners.Large)
                            .padding(horizontal = 10.dp, vertical = 3.dp),
                    )
                }

                // ZOUKAN -->
                ReaderAppBars(
                    visible = overlay is ReaderOverlay.Menu,
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
                        fitMode = config.fitMode,
                        cropEnabled = config.cropEnabled(state.readingMode),
                        pageLayout = config.paged.pageLayout,
                        buttons = config.bottomButtons,
                        forceHorizontalSlider = config.forceHorizontalSeekbar,
                        verticalSliderInLandscape = config.landscapeVerticalSeekbar,
                        leftHandedSlider = config.leftVerticalSeekbar,
                        currentPage = state.currentPage,
                        currentPageText = currentPageText(state),
                        totalPages = state.pages.size,
                        hasPrevious = state.previousChapter != null,
                        hasNext = state.nextChapter != null,
                        generation = state.generation,
                    ),
                    actions = menuActions,
                )
                // ZOUKAN <--
            }
            // ZOUKAN -->
        }
        // ZOUKAN <--

        // ZOUKAN -->
        when (val currentOverlay = overlay) {
            ReaderOverlay.Hidden, ReaderOverlay.Menu -> Unit
            ReaderOverlay.Help -> KeyboardHelp(model::dismissOverlay)
            ReaderOverlay.Settings -> ReaderSettingsDialog(model, model::dismissOverlay)
            ReaderOverlay.Chapters -> ReaderChapterListDialog(
                chapters = state.navigable,
                current = state.chapter?.id,
                onOpen = {
                    model.dismissOverlay()
                    model.openChapter(it)
                },
                onBookmark = model::toggleBookmark,
                onDismiss = model::dismissOverlay,
            )
            is ReaderOverlay.PageActions -> PageActionsDialog(
                target = currentOverlay.target,
                onDismiss = model::closePageActions,
                onSetAsCover = model::setPageAsCover,
                onCopy = model::copyPage,
                onSave = model::savePage,
                onCopyCombined = { model.copyCombined(currentOverlay.target) },
                onSaveCombined = { model.saveCombined(currentOverlay.target) },
            )
        }
        // ZOUKAN <--
    }
}

@Composable
private fun KeyboardHelp(onDismiss: () -> Unit) {
    val keys = listOf(
        localized(DMR.strings.reader_key_right_left) to localized(DMR.strings.next_previous_page_flipped_in_right),
        localized(DMR.strings.reader_key_space_page) to localized(DMR.strings.next_previous_page),
        localized(DMR.strings.reader_key_down_up) to localized(DMR.strings.scroll_a_tall_or_zoomed_page),
        localized(DMR.strings.reader_key_home_end) to localized(DMR.strings.first_last_page),
        localized(DMR.strings.reader_key_chapter) to localized(DMR.strings.next_previous_chapter),
        localized(DMR.strings.reader_key_zoom) to localized(DMR.strings.zoom_in_out),
        localized(DMR.strings.reader_key_zoom_reset) to localized(DMR.strings.reset_zoom),
        localized(DMR.strings.double_click) to localized(DMR.strings.zoom_to_2x_and_back),
        localized(DMR.strings.click_left_right_third) to localized(DMR.strings.previous_next_page),
        localized(DMR.strings.click_the_middle_m_or_enter) to localized(DMR.strings.show_or_hide_the_menu),
        localized(DMR.strings.reader_key_full_screen) to localized(DMR.strings.full_screen),
        localized(DMR.strings.escape) to localized(DMR.strings.close_the_menu_leave_full_screen),
        localized(DMR.strings.reader_key_auto_scroll) to localized(DMR.strings.start_or_stop_auto_scroll),
        localized(DMR.strings.reader_key_settings) to localized(DMR.strings.reader_settings),
        localized(DMR.strings.reader_key_bookmark) to localized(DMR.strings.bookmark_the_chapter_or_remove_its),
        localized(DMR.strings.reader_key_chapters) to localized(DMR.strings.chapter_list),
        localized(DMR.strings.reader_key_retry) to localized(DMR.strings.retry_failed_pages_load_the_shown),
        localized(DMR.strings.reader_key_shift_pages) to localized(DMR.strings.shift_double_pages_by_one),
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
