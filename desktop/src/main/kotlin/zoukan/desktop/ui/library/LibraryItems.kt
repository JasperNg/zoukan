package zoukan.desktop.ui.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.LocalLibrary
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.i18n.MR
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.browse.languageName
import zoukan.desktop.ui.common.ContextMenu
import zoukan.desktop.ui.common.ContextMenuState
import zoukan.desktop.ui.common.Corners
import zoukan.desktop.ui.common.Cover
import zoukan.desktop.ui.common.CoverImage
import zoukan.desktop.ui.common.LazyColumn
import zoukan.desktop.ui.common.LazyVerticalGrid
import zoukan.desktop.ui.common.Tooltip
import zoukan.desktop.ui.common.coverOutline
import zoukan.desktop.ui.common.languageIconPainter
import zoukan.desktop.ui.common.rememberContextMenuState
import zoukan.i18n.desktop.DMR

/** How a click on an entry was made: a plain click, Ctrl/Cmd + click or Shift + click. */
enum class ClickKind { Plain, Toggle, Range }

/**
 * Clicks that know the keyboard modifiers, for selecting like a file manager. A right click opens
 * [onContextMenu] at the pointer; without one, it toggles the selection as Ctrl/Cmd + click does.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.selectableClick(onContextMenu: ((Offset) -> Unit)? = null, onClick: (ClickKind) -> Unit): Modifier {
    var mods: PointerKeyboardModifiers? = null
    return this
        .onPointerEvent(PointerEventType.Press) { event ->
            mods = event.keyboardModifiers
            if (event.button == PointerButton.Secondary) {
                if (onContextMenu != null) {
                    onContextMenu(event.changes.firstOrNull()?.position ?: Offset.Zero)
                } else {
                    onClick(ClickKind.Toggle)
                }
            }
        }
        .clickable {
            val m = mods
            onClick(
                when {
                    m?.isShiftPressed == true -> ClickKind.Range
                    m?.isCtrlPressed == true || m?.isMetaPressed == true -> ClickKind.Toggle
                    else -> ClickKind.Plain
                },
            )
        }
}

/** A badge on an entry: a count or label, or (KMK) a language flag or a source icon. */
private sealed interface Badge {
    data class Label(val text: String, val primary: Boolean = false) : Badge
    data class Language(val lang: String) : Badge
    data class Source(val name: String, val iconUrl: String?, val isStub: Boolean, val isLocal: Boolean) : Badge
}

/** The badges at the cover's start, as Android's coverBadgeStart: downloads and unread. */
private fun startBadges(item: LibraryScreenModel.Item, settings: LibraryScreenModel.Settings): List<Badge> = buildList {
    if (settings.downloadBadge && item.downloadCount > 0) add(Badge.Label(item.downloadCount.toString()))
    if (settings.unreadBadge && item.library.unreadCount > 0) add(Badge.Label(item.library.unreadCount.toString(), primary = true))
    if (settings.localBadge && item.isLocal) add(Badge.Label(localized(MR.strings.label_local)))
}

/**
 * The badges at the cover's end, as Android's coverBadgeEnd (KMK): the language, as text or a flag
 * ("Use language icon"), and the source's icon.
 */
private fun endBadges(item: LibraryScreenModel.Item, settings: LibraryScreenModel.Settings): List<Badge> = buildList {
    if (settings.languageBadge && !item.isLocal && item.language.isNotEmpty()) {
        add(if (settings.useLangIcon) Badge.Language(item.language) else Badge.Label(item.language.uppercase()))
    }
    if (settings.sourceBadge) add(Badge.Source(item.sourceName, item.sourceIconUrl, item.isStubSource, item.isLocal))
}

@Composable
private fun Badges(list: List<Badge>, modifier: Modifier = Modifier, shape: Shape = Corners.Small) {
    if (list.isEmpty()) return
    Row(modifier.clip(shape).height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
        list.forEach { badge ->
            when (badge) {
                is Badge.Label -> Box(
                    Modifier.fillMaxHeight()
                        .background(if (badge.primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        badge.text,
                        color = if (badge.primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onTertiary,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 120.dp),
                    )
                }
                is Badge.Language -> {
                    val painter = languageIconPainter(badge.lang)
                    Tooltip(languageName(badge.lang)) {
                        if (painter != null) {
                            Image(painter, contentDescription = languageName(badge.lang), modifier = Modifier.width(25.dp).height(18.dp))
                        } else {
                            Text(badge.lang.uppercase(), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                is Badge.Source -> Tooltip(badge.name) { SourceIconBadge(badge) }
            }
        }
    }
}

/** KMK's SourceIconBadge: the source's icon, a warning for a missing source, a folder for local. */
@Composable
private fun SourceIconBadge(badge: Badge.Source) {
    val iconModifier = Modifier.height(18.dp).aspectRatio(1f)
    when {
        badge.isStub && badge.iconUrl == null -> Box(
            Modifier.background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 3.dp, vertical = 1.dp),
        ) {
            Icon(Icons.Filled.Warning, contentDescription = badge.name, tint = MaterialTheme.colorScheme.error, modifier = iconModifier)
        }
        badge.iconUrl != null -> CoverImage(badge.iconUrl, Modifier.height(22.dp).aspectRatio(1f), contentScale = ContentScale.Fit)
        else -> Box(
            Modifier.background(MaterialTheme.colorScheme.tertiary).padding(horizontal = 3.dp, vertical = 1.dp),
        ) {
            Icon(
                if (badge.isLocal) Icons.Outlined.Folder else Icons.Outlined.LocalLibrary,
                contentDescription = badge.name,
                tint = MaterialTheme.colorScheme.onTertiary,
                modifier = iconModifier,
            )
        }
    }
}

@Composable
private fun ContinueButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Tooltip(localized(DMR.strings.continue_reading), modifier) {
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(32.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f)),
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = localized(DMR.strings.continue_reading), modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun SelectedMark(modifier: Modifier = Modifier) {
    Icon(
        Icons.Filled.CheckCircle,
        contentDescription = localized(MR.strings.selected),
        tint = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(Corners.CoverInset).background(MaterialTheme.colorScheme.surface, CircleShape),
    )
}

/** The library in its display mode: compact, comfortable or cover-only grid, or a list. */
@Composable
fun LibraryContent(
    items: List<LibraryScreenModel.Item>,
    settings: LibraryScreenModel.Settings,
    selection: Set<Long>,
    onClick: (LibraryScreenModel.Item, ClickKind) -> Unit,
    onContinue: (LibraryScreenModel.Item) -> Unit,
    contextMenu: @Composable ColumnScope.(LibraryScreenModel.Item, ContextMenuState) -> Unit,
    searchQuery: String = "",
    onGlobalSearch: () -> Unit = {},
) {
    val display = settings.display
    if (display == LibraryDisplayMode.List) {
        val listState = rememberLazyListState()
        // A new search starts at the top, where the global search row is
        LaunchedEffect(searchQuery) { listState.scrollToItem(0) }
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(vertical = 8.dp)) {
            if (searchQuery.isNotEmpty()) {
                item(key = "library-global-search") { GlobalSearchItem(searchQuery, onGlobalSearch, Modifier.fillMaxWidth()) }
            }
            items(items, key = { it.id }) { item ->
                ListItem(item, settings, item.id in selection, { onClick(item, it) }, { onContinue(item) }) { contextMenu(item, it) }
            }
        }
        return
    }
    val panorama = display == LibraryDisplayMode.ComfortableGridPanorama
    val columns = if (settings.columns > 0) GridCells.Fixed(settings.columns) else GridCells.Adaptive(if (panorama) 260.dp else 160.dp)
    val gridState = rememberLazyGridState()
    LaunchedEffect(searchQuery) { gridState.scrollToItem(0) }
    LazyVerticalGrid(
        columns = columns,
        modifier = Modifier.fillMaxSize(),
        state = gridState,
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (searchQuery.isNotEmpty()) {
            item(key = "library-global-search", span = { GridItemSpan(maxLineSpan) }) { GlobalSearchItem(searchQuery, onGlobalSearch) }
        }
        items(items, key = { it.id }) { item ->
            GridItem(item, settings, item.id in selection, { onClick(item, it) }, { onContinue(item) }) { contextMenu(item, it) }
        }
    }
}

@Composable
private fun GridItem(
    item: LibraryScreenModel.Item,
    settings: LibraryScreenModel.Settings,
    selected: Boolean,
    onClick: (ClickKind) -> Unit,
    onContinue: () -> Unit,
    contextMenu: @Composable ColumnScope.(ContextMenuState) -> Unit,
) {
    val menu = rememberContextMenuState()
    val display = settings.display
    val titleBelow = display == LibraryDisplayMode.ComfortableGrid || display == LibraryDisplayMode.ComfortableGridPanorama
    val titleOver = display == LibraryDisplayMode.CompactGrid
    val ratio = if (display == LibraryDisplayMode.ComfortableGridPanorama) 16f / 9f else 2f / 3f
    val border = if (selected) {
        Modifier.border(3.dp, MaterialTheme.colorScheme.primary, Corners.Medium)
    } else {
        Modifier.coverOutline(Corners.Medium)
    }
    Box {
        Column(Modifier.testTag("manga-item").clip(Corners.Medium).selectableClick(menu::open, onClick)) {
            Box(Modifier.fillMaxWidth().aspectRatio(ratio).clip(Corners.Medium).then(border)) {
                CoverImage(Cover.of(item.manga), Modifier.fillMaxSize())
                if (selected) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)))
                if (titleOver) {
                    Box(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                            .padding(start = 8.dp, end = if (settings.continueReading) 40.dp else 8.dp, top = 24.dp, bottom = 8.dp),
                    ) {
                        Text(
                            item.manga.title,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Badges(startBadges(item, settings), Modifier.align(Alignment.TopStart).padding(Corners.CoverInset), Corners.OnCover)
                Row(Modifier.align(Alignment.TopEnd), verticalAlignment = Alignment.Top) {
                    Badges(endBadges(item, settings), Modifier.padding(Corners.CoverInset), Corners.OnCover)
                    if (selected) SelectedMark()
                }
                if (settings.continueReading && item.library.unreadCount > 0) {
                    ContinueButton(onContinue, Modifier.align(Alignment.BottomEnd).padding(Corners.CoverInset))
                }
            }
            if (titleBelow) {
                Text(
                    item.manga.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                )
            }
        }
        ContextMenu(menu) { contextMenu(menu) }
    }
}

@Composable
private fun ListItem(
    item: LibraryScreenModel.Item,
    settings: LibraryScreenModel.Settings,
    selected: Boolean,
    onClick: (ClickKind) -> Unit,
    onContinue: () -> Unit,
    contextMenu: @Composable ColumnScope.(ContextMenuState) -> Unit,
) {
    val menu = rememberContextMenuState()
    Box {
        Surface(color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent) {
            Row(
                Modifier.testTag("manga-item").fillMaxWidth().selectableClick(menu::open, onClick).padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoverImage(Cover.of(item.manga), Modifier.height(56.dp).aspectRatio(1f).clip(Corners.Medium).coverOutline(Corners.Medium))
                Text(
                    item.manga.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                )
                Badges(startBadges(item, settings) + endBadges(item, settings))
                if (settings.continueReading && item.library.unreadCount > 0) {
                    ContinueButton(onContinue, Modifier.padding(start = 8.dp))
                }
            }
        }
        ContextMenu(menu) { contextMenu(menu) }
    }
}

/** Android's GlobalSearchItem: "Search for \"…\" globally", above the library's search results. */
@Composable
fun GlobalSearchItem(searchQuery: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(modifier = modifier.testTag("library-global-search"), onClick = onClick) {
        Text(localized(MR.strings.action_global_search_query, searchQuery))
    }
}
