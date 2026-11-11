package zoukan.desktop.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ChromeReaderMode
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.common.Corners
import zoukan.desktop.ui.common.Dates
import zoukan.desktop.ui.common.DropdownMenu
import zoukan.desktop.ui.common.FitModes
import zoukan.desktop.ui.common.LazyColumn
import zoukan.desktop.ui.common.ReaderBottomButtons
import zoukan.desktop.ui.common.ReadingModes
import zoukan.i18n.desktop.DMR
import kotlin.math.roundToInt

// The reader menu, as Komikku's ReaderAppBars: the top bar, the floating chapter navigator (previous
// chapter, page slider, next chapter) and the bottom row of buttons. In vertical modes the navigator
// stands on the side when the window is taller than wide, or always with the landscape setting.

private val slideSpec = tween<IntOffset>(200)
private val fadeSpec = tween<Float>(150)

enum class NavBarType { VerticalRight, VerticalLeft, Bottom }

/** Where the page slider goes, as Komikku's: on the side only in vertical modes */
fun navBarType(
    readingMode: Int,
    windowIsPortrait: Boolean,
    forceHorizontal: Boolean,
    verticalInLandscape: Boolean,
    leftHanded: Boolean,
): NavBarType {
    val verticalMode = readingMode == ReadingModes.VERTICAL ||
        readingMode == ReadingModes.WEBTOON ||
        readingMode == ReadingModes.CONTINUOUS_VERTICAL
    val vertical = !forceHorizontal && verticalMode && (windowIsPortrait || verticalInLandscape)
    return when {
        !vertical -> NavBarType.Bottom
        leftHanded -> NavBarType.VerticalLeft
        else -> NavBarType.VerticalRight
    }
}

/** The page shown, "2-3" for two pages side by side */
fun currentPageText(state: ReaderScreenModel.State): String =
    if (state.firstShownPage in 0 until state.currentPage) {
        "${state.firstShownPage + 1}-${state.currentPage + 1}"
    } else {
        "${state.currentPage + 1}"
    }

/** The menu's background, as Komikku's: the elevated surface, nearly opaque */
@Composable
fun readerBarColor(): Color {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    return scheme.surfaceColorAtElevation(3.dp).copy(alpha = if (dark) 0.9f else 0.95f)
}

/** What the reader menu's buttons do */
class ReaderMenuActions(
    val onBack: () -> Unit,
    val onToggleBookmark: () -> Unit,
    val onAutoScroll: () -> Unit,
    val onKeys: () -> Unit,
    val onFullscreen: () -> Unit,
    val onChapters: () -> Unit,
    val onWebView: () -> Unit,
    val onBrowser: () -> Unit,
    val onReadingMode: (Int) -> Unit,
    val onFitMode: (Int) -> Unit,
    val onToggleCrop: () -> Unit,
    val onPageLayout: (Int) -> Unit,
    val onSettings: () -> Unit,
    val onPreviousChapter: () -> Unit,
    val onNextChapter: () -> Unit,
    val onPage: (Int) -> Unit,
    // ZOUKAN -->
    val onRetryAll: () -> Unit = {},
    val onBoostPage: () -> Unit = {},
    // ZOUKAN <--
)

/** Everything a menu draws, read from the reader's state and settings */
class ReaderMenuState(
    val mangaTitle: String,
    val chapterTitle: String,
    val bookmarked: Boolean,
    val autoScroll: Boolean,
    val fullscreen: Boolean,
    val readingMode: Int,
    val fitMode: Int,
    val cropEnabled: Boolean,
    val pageLayout: Int,
    val buttons: Set<String>,
    val forceHorizontalSlider: Boolean,
    val verticalSliderInLandscape: Boolean,
    val leftHandedSlider: Boolean,
    val currentPage: Int,
    val currentPageText: String,
    val totalPages: Int,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
    val generation: Int,
)

@Composable
fun ReaderAppBars(visible: Boolean, menu: ReaderMenuState, actions: ReaderMenuActions, modifier: Modifier = Modifier) {
    val background = readerBarColor()
    val rtl = menu.readingMode == ReadingModes.RIGHT_TO_LEFT
    BoxWithConstraints(modifier.fillMaxSize()) {
        val type = navBarType(
            readingMode = menu.readingMode,
            windowIsPortrait = maxHeight > maxWidth,
            forceHorizontal = menu.forceHorizontalSlider,
            verticalInLandscape = menu.verticalSliderInLandscape,
            leftHanded = menu.leftHandedSlider,
        )
        Column(Modifier.fillMaxHeight()) {
            AnimatedVisibility(
                visible = visible,
                enter = slideInVertically(slideSpec) { -it } + fadeIn(fadeSpec),
                exit = slideOutVertically(slideSpec) { -it } + fadeOut(fadeSpec),
            ) {
                ReaderTopBar(menu, actions, Modifier.background(background))
            }

            when (type) {
                NavBarType.VerticalLeft, NavBarType.VerticalRight -> {
                    val left = type == NavBarType.VerticalLeft
                    AnimatedVisibility(
                        visible = visible,
                        enter = slideInHorizontally(slideSpec) { if (left) -it else it } + fadeIn(fadeSpec),
                        exit = slideOutHorizontally(slideSpec) { if (left) -it else it } + fadeOut(fadeSpec),
                        modifier = Modifier.weight(1f).align(if (left) Alignment.Start else Alignment.End),
                    ) {
                        ChapterNavigator(rtl = false, vertical = true, menu = menu, actions = actions)
                    }
                }
                NavBarType.Bottom -> Spacer(Modifier.weight(1f))
            }

            AnimatedVisibility(
                visible = visible,
                enter = slideInVertically(slideSpec) { it } + fadeIn(fadeSpec),
                exit = slideOutVertically(slideSpec) { it } + fadeOut(fadeSpec),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (type == NavBarType.Bottom) {
                        ChapterNavigator(rtl = rtl, vertical = false, menu = menu, actions = actions)
                    }
                    ReaderBottomBar(
                        menu,
                        actions,
                        Modifier.fillMaxWidth().background(background).padding(horizontal = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ReaderTopBar(menu: ReaderMenuState, actions: ReaderMenuActions, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().pointerInput(Unit) {}.padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MenuButton(Icons.AutoMirrored.Outlined.ArrowBack, localized(MR.strings.action_webview_back), actions.onBack, tint = LocalContentColor.current)
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(menu.mangaTitle, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (menu.chapterTitle.isNotEmpty()) {
                Text(
                    menu.chapterTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        val tint = LocalContentColor.current
        MenuButton(
            if (menu.bookmarked) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
            if (menu.bookmarked) localized(DMR.strings.remove_bookmark_b) else localized(DMR.strings.bookmark_b),
            actions.onToggleBookmark,
            tint = tint,
        )
        MenuButton(
            if (menu.autoScroll) Icons.Outlined.PauseCircle else Icons.Outlined.PlayCircle,
            if (menu.autoScroll) localized(DMR.strings.stop_auto_scroll_a) else localized(DMR.strings.auto_scroll_a),
            actions.onAutoScroll,
            tint = tint,
        )
        MenuButton(Icons.Outlined.Keyboard, localized(DMR.strings.keyboard_shortcuts), actions.onKeys, tint = tint)
        MenuButton(
            if (menu.fullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
            if (menu.fullscreen) localized(DMR.strings.leave_full_screen_f) else localized(DMR.strings.full_screen_f),
            actions.onFullscreen,
            tint = tint,
        )
    }
}

@Composable
private fun ReaderBottomBar(menu: ReaderMenuState, actions: ReaderMenuActions, modifier: Modifier = Modifier) {
    val buttons = menu.buttons
    val mode = menu.readingMode
    val webtoon = mode == ReadingModes.WEBTOON || mode == ReadingModes.CONTINUOUS_VERTICAL
    Row(
        modifier.pointerInput(Unit) {}.padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (ReaderBottomButtons.VIEW_CHAPTERS in buttons) {
            MenuButton(Icons.Outlined.FormatListNumbered, localized(DMR.strings.chapters_c), actions.onChapters)
        }
        if (ReaderBottomButtons.WEB_VIEW in buttons) {
            MenuButton(Icons.Outlined.Public, localized(MR.strings.action_open_in_web_view), actions.onWebView)
        }
        if (ReaderBottomButtons.BROWSER in buttons) {
            MenuButton(Icons.Outlined.Explore, localized(MR.strings.action_open_in_browser), actions.onBrowser)
        }
        if (ReaderBottomButtons.READING_MODE in buttons) {
            ChoiceButton(
                icon = Icons.AutoMirrored.Outlined.ChromeReaderMode,
                label = localized(DMR.strings.reading_mode, ReadingModes.label(mode)),
                selected = mode,
                choices = ReadingModes.all.map { it to ReadingModes.label(it) },
                onSelect = actions.onReadingMode,
            )
        }
        if (!webtoon && ReaderBottomButtons.SCALE_TYPE in buttons) {
            ChoiceButton(
                icon = Icons.Outlined.AspectRatio,
                label = localized(DMR.strings.scale_type, FitModes.label(menu.fitMode)),
                selected = menu.fitMode,
                choices = FitModes.all.map { it to FitModes.label(it) },
                onSelect = actions.onFitMode,
            )
        }
        val cropButton = when (mode) {
            ReadingModes.WEBTOON -> ReaderBottomButtons.CROP_WEBTOON
            ReadingModes.CONTINUOUS_VERTICAL -> ReaderBottomButtons.CROP_CONTINUOUS_VERTICAL
            else -> ReaderBottomButtons.CROP_PAGER
        }
        if (cropButton in buttons) {
            Box(contentAlignment = Alignment.Center) {
                MenuButton(
                    Icons.Outlined.Crop,
                    if (menu.cropEnabled) localized(DMR.strings.crop_borders_on) else localized(DMR.strings.crop_borders_off),
                    actions.onToggleCrop,
                    modifier = Modifier.alpha(if (menu.cropEnabled) 1f else 0.5f),
                )
            }
        }
        if (!webtoon && mode != ReadingModes.VERTICAL && ReaderBottomButtons.PAGE_LAYOUT in buttons) {
            ChoiceButton(
                icon = Icons.Outlined.AutoStories,
                label = localized(SYMR.strings.page_layout),
                selected = menu.pageLayout,
                choices = PAGE_LAYOUTS,
                onSelect = actions.onPageLayout,
            )
        }
        // ZOUKAN -->
        if (ReaderBottomButtons.RETRY_ALL in buttons) {
            MenuButton(Icons.Outlined.Refresh, localized(DMR.strings.retry_failed_pages_r), actions.onRetryAll)
        }
        if (ReaderBottomButtons.BOOST_PAGE in buttons) {
            MenuButton(Icons.Outlined.FastForward, localized(DMR.strings.load_this_page_first_shift_r), actions.onBoostPage)
        }
        // ZOUKAN <--
        MenuButton(Icons.Outlined.Settings, localized(DMR.strings.settings_s), actions.onSettings)
    }
}

val PAGE_LAYOUTS = listOf(0 to localized(SYMR.strings.single_page), 1 to localized(SYMR.strings.double_pages), 2 to localized(DMR.strings.automatic_double_in_a_wide_window))

/** An icon button with a tooltip, in the menu's accent color as Komikku's */
@Composable
private fun MenuButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick, modifier = modifier) { Icon(icon, contentDescription = label, tint = tint) }
    }
}

/** A menu button that opens a list of choices, in place of Komikku's choice dialogs */
@Composable
private fun <T> ChoiceButton(
    icon: ImageVector,
    label: String,
    selected: T,
    choices: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        MenuButton(icon, label, { open = true })
        DropdownMenu(open, onDismissRequest = { open = false }) {
            choices.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    leadingIcon = { RadioButton(selected = selected == value, onClick = null) },
                    onClick = {
                        open = false
                        onSelect(value)
                    },
                )
            }
        }
    }
}

/**
 * Komikku's chapter navigator: the previous and next chapter buttons either side of a rounded pill
 * that holds the page slider. In right to left mode the slider runs right to left and the buttons
 * swap sides. [vertical] stands it on the side, the first page at the top.
 */
@Composable
private fun ChapterNavigator(rtl: Boolean, vertical: Boolean, menu: ReaderMenuState, actions: ReaderMenuActions) {
    val background = readerBarColor()
    val buttonColors = IconButtonDefaults.filledIconButtonColors(
        containerColor = background,
        disabledContainerColor = background,
        contentColor = MaterialTheme.colorScheme.primary,
    )
    val textColor = MaterialTheme.colorScheme.onSurface

    @Composable
    fun ChapterButton(next: Boolean) {
        val enabled = if (next) menu.hasNext else menu.hasPrevious
        val label = if (next) localized(DMR.strings.next_chapter_n) else localized(DMR.strings.previous_chapter_p)
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
            tooltip = { PlainTooltip { Text(label) } },
            state = rememberTooltipState(),
        ) {
            FilledIconButton(
                onClick = if (next) actions.onNextChapter else actions.onPreviousChapter,
                enabled = enabled,
                colors = buttonColors,
            ) {
                Icon(
                    if (next) Icons.Outlined.SkipNext else Icons.Outlined.SkipPrevious,
                    contentDescription = label,
                    modifier = if (vertical) Modifier.rotate(90f) else Modifier,
                )
            }
        }
    }

    if (vertical) {
        Column(
            Modifier.fillMaxHeight().pointerInput(Unit) {}.padding(vertical = 24.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ChapterButton(next = false)
            if (menu.totalPages > 1) {
                Column(
                    Modifier.weight(1f).clip(Corners.ExtraLarge).background(background).padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(menu.currentPageText, color = textColor, fontWeight = FontWeight.Medium)
                    PageSlider(menu, actions, Modifier.padding(vertical = 8.dp).verticalSlider().weight(1f))
                    Text("${menu.totalPages}", color = textColor)
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
            ChapterButton(next = true)
        }
    } else {
        // The direction is the reading mode's, not the system's
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                Modifier.fillMaxWidth().pointerInput(Unit) {}.padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChapterButton(next = rtl)
                if (menu.totalPages > 1) {
                    CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                        Row(
                            Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp)
                                .clip(Corners.ExtraLarge)
                                .background(background)
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(contentAlignment = Alignment.CenterEnd) {
                                Text(menu.currentPageText, color = textColor, fontWeight = FontWeight.Medium)
                                // Takes the widest page text's room, so the slider does not move as it changes
                                Text("${menu.totalPages}-${menu.totalPages}", color = Color.Transparent)
                            }
                            PageSlider(menu, actions, Modifier.weight(1f).padding(horizontal = 8.dp))
                            Text("${menu.totalPages}", color = textColor)
                        }
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }
                ChapterButton(next = !rtl)
            }
        }
    }
}

/** The page slider: it turns pages as it moves, as Komikku's */
@Composable
private fun PageSlider(menu: ReaderMenuState, actions: ReaderMenuActions, modifier: Modifier = Modifier) {
    val count = menu.totalPages
    val interaction = remember { MutableInteractionSource() }
    val dragged by interaction.collectIsDraggedAsState()
    val pressed by interaction.collectIsPressedAsState()
    var value by remember(menu.generation) { mutableFloatStateOf(menu.currentPage.toFloat()) }
    // While the thumb is held it stays under the pointer; the viewer may land on the spread's other page
    if (!dragged && !pressed) value = menu.currentPage.toFloat()
    Slider(
        value = value,
        onValueChange = {
            val page = it.roundToInt().coerceIn(0, count - 1)
            value = page.toFloat()
            if (page != menu.currentPage) actions.onPage(page)
        },
        valueRange = 0f..(count - 1).toFloat(),
        steps = (count - 2).coerceAtLeast(0),
        interactionSource = interaction,
        modifier = modifier,
    )
}

/** Turns a horizontal slider a quarter clockwise and swaps its width and height, as Komikku's */
private fun Modifier.verticalSlider(): Modifier = this
    .graphicsLayer {
        rotationZ = 90f
        transformOrigin = TransformOrigin(0f, 0f)
    }
    .layout { measurable, constraints ->
        val placeable = measurable.measure(
            Constraints(
                minWidth = constraints.minHeight,
                maxWidth = constraints.maxHeight,
                minHeight = constraints.minWidth,
                maxHeight = constraints.maxWidth,
            ),
        )
        layout(placeable.height, placeable.width) {
            placeable.place(0, -placeable.height)
        }
    }

/**
 * The chapter list, as Komikku's reader chapter list: the chapters the reader goes through, newest
 * first, the open one marked. Clicking one opens it; the bookmark button bookmarks it.
 */
@Composable
fun ReaderChapterListDialog(
    chapters: List<Chapter>,
    current: Long?,
    onOpen: (Chapter) -> Unit,
    onBookmark: (Chapter) -> Unit,
    onDismiss: () -> Unit,
) {
    val shown = remember(chapters) { chapters.asReversed() }
    val listState = rememberLazyListState((shown.indexOfFirst { it.id == current } - 2).coerceAtLeast(0))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized(MR.strings.chapters)) },
        text = {
            LazyColumn(state = listState, modifier = Modifier.width(520.dp).heightIn(min = 200.dp, max = 520.dp)) {
                items(shown, key = { it.id }) { chapter ->
                    ReaderChapterRow(
                        chapter = chapter,
                        current = chapter.id == current,
                        onClick = { onOpen(chapter) },
                        onBookmark = { onBookmark(chapter) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(localized(MR.strings.action_close)) } },
    )
}

@Composable
private fun ReaderChapterRow(chapter: Chapter, current: Boolean, onClick: () -> Unit, onBookmark: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Corners.Medium)
            .background(if (current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).alpha(if (chapter.read && !current) 0.5f else 1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (chapter.bookmark) {
                    Icon(
                        Icons.Filled.Bookmark,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp).padding(end = 2.dp),
                    )
                }
                Text(
                    chapter.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (current) FontWeight.SemiBold else null,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val details = listOfNotNull(
                chapter.dateUpload.takeIf { it > 0L }?.let(Dates::relative),
                chapter.scanlator?.takeIf { it.isNotBlank() },
                if (!chapter.read && chapter.lastPageRead > 0) localized(DMR.strings.page, chapter.lastPageRead + 1) else null,
            )
            if (details.isNotEmpty()) {
                Text(
                    details.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        MenuButton(
            if (chapter.bookmark) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
            if (chapter.bookmark) localized(DMR.strings.remove_bookmark) else localized(DMR.strings.bookmark),
            onBookmark,
        )
    }
}
