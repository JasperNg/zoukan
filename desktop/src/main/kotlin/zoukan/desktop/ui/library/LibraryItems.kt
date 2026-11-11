package zoukan.desktop.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tachiyomi.domain.library.model.LibraryDisplayMode
import zoukan.desktop.ui.common.Cover
import zoukan.desktop.ui.common.CoverImage

/** How a click on an entry was made: a plain click, Ctrl/Cmd + click, Shift + click or right click. */
enum class ClickKind { Plain, Toggle, Range }

/** Clicks that know the keyboard modifiers and the right button, for selecting like a file manager. */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.selectableClick(onClick: (ClickKind) -> Unit): Modifier {
    var mods: PointerKeyboardModifiers? = null
    return this
        .onPointerEvent(PointerEventType.Press) { event ->
            mods = event.keyboardModifiers
            if (event.button == PointerButton.Secondary) onClick(ClickKind.Toggle)
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

/** The badges an entry shows, by the library's badge settings. */
private fun badges(item: LibraryScreenModel.Item, settings: LibraryScreenModel.Settings): List<Pair<String, Boolean>> = buildList {
    if (settings.downloadBadge && item.downloadCount > 0) add(item.downloadCount.toString() to false)
    if (settings.unreadBadge && item.library.unreadCount > 0) add(item.library.unreadCount.toString() to true)
    if (settings.localBadge && item.isLocal) add("Local" to false)
    if (settings.languageBadge && item.language.isNotBlank() && item.language != "all" && !item.isLocal) add(item.language.uppercase() to false)
    if (settings.sourceBadge && item.sourceName.isNotBlank() && !item.isLocal) add(item.sourceName to false)
}

@Composable
private fun Badges(list: List<Pair<String, Boolean>>, modifier: Modifier = Modifier) {
    if (list.isEmpty()) return
    Row(modifier.clip(RoundedCornerShape(4.dp))) {
        list.forEach { (text, primary) ->
            Box(
                Modifier.background(if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            ) {
                Text(
                    text,
                    color = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onTertiary,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 120.dp),
                )
            }
        }
    }
}

@Composable
private fun ContinueButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilledIconButton(
        onClick = onClick,
        modifier = modifier.size(32.dp),
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f)),
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = "Continue reading", modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun SelectedMark(modifier: Modifier = Modifier) {
    Icon(
        Icons.Filled.CheckCircle,
        contentDescription = "Selected",
        tint = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(6.dp).background(MaterialTheme.colorScheme.surface, CircleShape),
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
) {
    val display = settings.display
    if (display == LibraryDisplayMode.List) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(items, key = { it.id }) { item ->
                ListItem(item, settings, item.id in selection, { onClick(item, it) }, { onContinue(item) })
            }
        }
        return
    }
    val panorama = display == LibraryDisplayMode.ComfortableGridPanorama
    val columns = if (settings.columns > 0) GridCells.Fixed(settings.columns) else GridCells.Adaptive(if (panorama) 260.dp else 160.dp)
    LazyVerticalGrid(
        columns = columns,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items, key = { it.id }) { item ->
            GridItem(item, settings, item.id in selection, { onClick(item, it) }, { onContinue(item) })
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
) {
    val display = settings.display
    val titleBelow = display == LibraryDisplayMode.ComfortableGrid || display == LibraryDisplayMode.ComfortableGridPanorama
    val titleOver = display == LibraryDisplayMode.CompactGrid
    val ratio = if (display == LibraryDisplayMode.ComfortableGridPanorama) 16f / 9f else 2f / 3f
    val border = if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)) else Modifier
    Column(Modifier.testTag("manga-item").clip(RoundedCornerShape(8.dp)).selectableClick(onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(8.dp)).then(border)) {
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
            Badges(badges(item, settings), Modifier.align(Alignment.TopStart).padding(6.dp))
            if (selected) SelectedMark(Modifier.align(Alignment.TopEnd))
            if (settings.continueReading && item.library.unreadCount > 0) {
                ContinueButton(onContinue, Modifier.align(Alignment.BottomEnd).padding(6.dp))
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
}

@Composable
private fun ListItem(
    item: LibraryScreenModel.Item,
    settings: LibraryScreenModel.Settings,
    selected: Boolean,
    onClick: (ClickKind) -> Unit,
    onContinue: () -> Unit,
) {
    Surface(color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent) {
        Row(
            Modifier.testTag("manga-item").fillMaxWidth().selectableClick(onClick).padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverImage(Cover.of(item.manga), Modifier.height(56.dp).aspectRatio(1f).clip(RoundedCornerShape(6.dp)))
            Text(
                item.manga.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
            )
            Badges(badges(item, settings))
            if (settings.continueReading && item.library.unreadCount > 0) {
                ContinueButton(onContinue, Modifier.padding(start = 8.dp))
            }
        }
    }
}
