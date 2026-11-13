package zoukan.desktop.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.StarHalf
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.source.Source
import exh.metadata.MetadataUtil
import exh.metadata.metadata.EHentaiSearchMetadata
import exh.metadata.metadata.MangaDexSearchMetadata
import exh.metadata.metadata.RaisedSearchMetadata
import exh.metadata.metadata.RankedSearchMetadata
import exh.source.isEhBasedSource
import exh.util.floor
import kotlinx.coroutines.flow.distinctUntilChanged
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.compat.localized
import zoukan.desktop.compat.localizedPlural
import zoukan.desktop.exh.SourceTags
import zoukan.desktop.ui.common.Corners
import zoukan.desktop.ui.common.Cover
import zoukan.desktop.ui.common.CoverImage
import zoukan.desktop.ui.common.DropdownMenu
import zoukan.desktop.ui.common.LazyColumn
import zoukan.desktop.ui.common.LazyVerticalGrid
import zoukan.desktop.ui.common.MangaGridItem
import zoukan.desktop.ui.common.TooltipIconButton
import zoukan.desktop.ui.common.collectAsState
import zoukan.desktop.ui.common.coverOutline
import zoukan.desktop.ui.common.onRightClick
import java.time.Instant
import java.time.ZoneId

/*
 * The display modes of a source's browse screen, ported from Komikku: BrowseSourceToolbar's
 * display mode menu, BrowseSourceContent and its grids and list (BrowseSourceComfortableGrid,
 * BrowseSourceCompactGrid, BrowseSourceList, BrowseSourceEHentaiList), and GlobalSearchCardRow's
 * MangaItem, which the feed and global search rows use.
 */

/** Android's BrowseFavoriteCoverAlpha: entries already in the library show faded. */
private const val BROWSE_FAVORITE_COVER_ALPHA = 0.34f

/** Komikku's RatioSwitchToPanorama: covers this wide or wider show as panoramas. */
private const val RATIO_SWITCH_TO_PANORAMA = 0.75f

/** Komikku's MangaCover.Book and MangaCover.Panorama ratios (width / height). */
private const val BOOK_RATIO = 2f / 3f
private const val PANORAMA_RATIO = 3f / 2f

/** The toolbar's display mode button and menu, as BrowseSourceToolbar. */
@Composable
fun BrowseDisplayModeButton(displayMode: LibraryDisplayMode, onDisplayModeChange: (LibraryDisplayMode) -> Unit) {
    var selectingDisplayMode by remember { mutableStateOf(false) }
    Box {
        TooltipIconButton(
            if (displayMode == LibraryDisplayMode.List) Icons.AutoMirrored.Filled.ViewList else Icons.Filled.ViewModule,
            localized(MR.strings.action_display_mode),
            onClick = { selectingDisplayMode = true },
        )
        DropdownMenu(expanded = selectingDisplayMode, onDismissRequest = { selectingDisplayMode = false }) {
            listOf(
                LibraryDisplayMode.ComfortableGrid to localized(MR.strings.action_display_comfortable_grid),
                // KMK -->
                LibraryDisplayMode.ComfortableGridPanorama to localized(KMR.strings.action_display_comfortable_grid_panorama),
                // KMK <--
                LibraryDisplayMode.CompactGrid to localized(MR.strings.action_display_grid),
                LibraryDisplayMode.List to localized(MR.strings.action_display_list),
            ).forEach { (mode, label) ->
                RadioMenuItem(label, isChecked = displayMode == mode) {
                    selectingDisplayMode = false
                    onDisplayModeChange(mode)
                }
            }
        }
    }
}

/** Android's RadioMenuItem: a menu row with a radio mark at its end. */
@Composable
private fun RadioMenuItem(text: String, isChecked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        onClick = onClick,
        trailingIcon = {
            if (isChecked) {
                Icon(Icons.Outlined.RadioButtonChecked, localized(MR.strings.selected), tint = MaterialTheme.colorScheme.primary)
            } else {
                Icon(Icons.Outlined.RadioButtonUnchecked, localized(MR.strings.not_selected))
            }
        },
    )
}

/** The grid columns, as BrowseSourceScreenModel.getColumnsPreference: the library's column count, or fit the window. */
@Composable
private fun browseColumns(): GridCells {
    val preference = remember { Injekt.get<LibraryPreferences>().landscapeColumns() }
    val columns by preference.collectAsState()
    return if (columns == 0) GridCells.Adaptive(160.dp) else GridCells.Fixed(columns)
}

/**
 * A source's results in the chosen display mode, as BrowseSourceContent: E-Hentai sources show
 * the detailed list while "Enhanced E-Hentai view" is on, other sources the comfortable,
 * panorama or compact grid, or the list. [onNearEnd] loads the next page; [footer] shows the
 * loading, error or "load more" row after the results.
 */
@Composable
fun BrowseSourceContent(
    source: Source?,
    items: List<Manga>,
    metadata: Map<Long, RaisedSearchMetadata>,
    favorites: Set<Long>,
    displayMode: LibraryDisplayMode,
    ehentaiBrowseDisplayMode: Boolean,
    listingKey: Any?,
    isSelected: (Manga) -> Boolean,
    onMangaClick: (Manga) -> Unit,
    onMangaSecondaryClick: (Manga) -> Unit,
    onNearEnd: () -> Unit,
    footer: @Composable () -> Unit,
) {
    // SY -->
    val ehentaiList = source?.isEhBasedSource() == true && ehentaiBrowseDisplayMode
    // SY <--
    if (ehentaiList || displayMode == LibraryDisplayMode.List) {
        val listState = rememberLazyListState()
        LaunchedEffect(listState, listingKey) {
            snapshotFlow {
                val info = listState.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                last >= info.totalItemsCount - 8
            }.distinctUntilChanged().collect { nearEnd -> if (nearEnd) onNearEnd() }
        }
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(vertical = if (ehentaiList) 0.dp else 8.dp)) {
            items(items.size, key = { items[it].id }) { index ->
                val manga = items[index]
                if (ehentaiList) {
                    BrowseSourceEHentaiListItem(
                        manga = manga,
                        metadata = metadata[manga.id],
                        favorite = manga.id in favorites,
                        isSelected = isSelected(manga),
                        onClick = { onMangaClick(manga) },
                        onSecondaryClick = { onMangaSecondaryClick(manga) },
                    )
                } else {
                    BrowseSourceListItem(
                        manga = manga,
                        metadata = metadata[manga.id],
                        favorite = manga.id in favorites,
                        isSelected = isSelected(manga),
                        onClick = { onMangaClick(manga) },
                        onSecondaryClick = { onMangaSecondaryClick(manga) },
                    )
                }
            }
            item { footer() }
        }
        return
    }

    val gridState = rememberLazyGridState()
    LaunchedEffect(gridState, listingKey) {
        snapshotFlow {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= info.totalItemsCount - 8
        }.distinctUntilChanged().collect { nearEnd -> if (nearEnd) onNearEnd() }
    }
    LazyVerticalGrid(
        columns = browseColumns(),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items.size, key = { items[it].id }) { index ->
            val manga = items[index]
            val favorite = manga.id in favorites
            val selected = isSelected(manga)
            when (displayMode) {
                LibraryDisplayMode.ComfortableGrid, LibraryDisplayMode.ComfortableGridPanorama -> BrowseComfortableGridItem(
                    title = manga.title,
                    cover = Cover.of(manga),
                    favorite = favorite,
                    // KMK -->
                    usePanoramaCover = displayMode == LibraryDisplayMode.ComfortableGridPanorama,
                    // KMK <--
                    onClick = { onMangaClick(manga) },
                    onSecondaryClick = { onMangaSecondaryClick(manga) },
                    modifier = Modifier.bulkSelected(selected),
                    // SY -->
                    coverBadgeEnd = { MetadataBadges(metadata[manga.id]) },
                    // SY <--
                )
                else -> MangaGridItem(
                    title = manga.title,
                    cover = Cover.of(manga),
                    badge = if (favorite) localized(MR.strings.in_library) else null,
                    dimmed = favorite,
                    onClick = { onMangaClick(manga) },
                    onSecondaryClick = { onMangaSecondaryClick(manga) },
                    modifier = Modifier.bulkSelected(selected),
                )
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { footer() }
    }
}

/**
 * A comfortable grid cell, as MangaComfortableGridItem: the cover with the title under it. With
 * [usePanoramaCover], a wide cover shows at the panorama ratio instead of being cropped to a book
 * cover; [coverRatio] holds the loaded cover's height / width.
 */
@Composable
fun BrowseComfortableGridItem(
    title: String,
    cover: Cover?,
    favorite: Boolean,
    usePanoramaCover: Boolean,
    onClick: () -> Unit,
    onSecondaryClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    titleMaxLines: Int = 2,
    coverRatio: MutableFloatState = remember { mutableFloatStateOf(1f) },
    coverBadgeEnd: @Composable RowScope.() -> Unit = {},
) {
    val coverIsWide = coverRatio.floatValue <= RATIO_SWITCH_TO_PANORAMA
    Column(
        modifier
            .testTag("manga-item")
            .clip(Corners.Medium)
            .clickable(onClick = onClick)
            .then(if (onSecondaryClick != null) Modifier.onRightClick(onSecondaryClick) else Modifier),
    ) {
        Box(
            Modifier.fillMaxWidth()
                .aspectRatio(if (usePanoramaCover && coverIsWide) PANORAMA_RATIO else BOOK_RATIO)
                .clip(Corners.Medium)
                .coverOutline(Corners.Medium),
        ) {
            CoverImage(
                cover,
                Modifier.fillMaxSize().alpha(if (favorite) BROWSE_FAVORITE_COVER_ALPHA else 1f),
                onLoaded = { width, height -> if (width > 0) coverRatio.floatValue = height.toFloat() / width },
            )
            Row(
                Modifier.align(Alignment.TopStart).padding(Corners.CoverInset),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (favorite) InLibraryBadge()
            }
            Row(
                Modifier.align(Alignment.TopEnd).padding(Corners.CoverInset),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                content = coverBadgeEnd,
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            minLines = 2,
            maxLines = titleMaxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(4.dp),
        )
    }
}

/**
 * A result in a feed or global search row, as GlobalSearchCardRow's MangaItem: a comfortable grid
 * cell, wider for a wide cover while Appearance > "Panorama cover flow" is on.
 */
@Composable
fun BrowseRowItem(
    manga: Manga,
    onClick: () -> Unit,
    onSecondaryClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    // KMK -->
    val preference = remember { Injekt.get<UiPreferences>().usePanoramaCoverFlow() }
    val panoramaCover by preference.collectAsState()
    val coverRatio = remember { mutableFloatStateOf(1f) }
    // KMK <--
    Box(Modifier.width(if (panoramaCover && coverRatio.floatValue <= RATIO_SWITCH_TO_PANORAMA) 300.dp else 140.dp)) {
        BrowseComfortableGridItem(
            title = manga.title,
            cover = Cover.of(manga),
            favorite = manga.favorite,
            usePanoramaCover = panoramaCover,
            onClick = onClick,
            onSecondaryClick = onSecondaryClick,
            modifier = modifier,
            titleMaxLines = 3,
            coverRatio = coverRatio,
        )
    }
}

/** A list row, as BrowseSourceList's MangaListItem: a square cover, the title and the badges. */
@Composable
private fun BrowseSourceListItem(
    manga: Manga,
    metadata: RaisedSearchMetadata?,
    favorite: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onSecondaryClick: () -> Unit,
) {
    Row(
        Modifier
            .testTag("manga-item")
            .fillMaxWidth()
            .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .height(56.dp)
            .clickable(onClick = onClick)
            .onRightClick(onSecondaryClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverImage(
            Cover.of(manga),
            Modifier.fillMaxHeight().aspectRatio(1f).clip(Corners.Small).alpha(if (favorite) BROWSE_FAVORITE_COVER_ALPHA else 1f),
        )
        Text(
            manga.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp).weight(1f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (favorite) InLibraryBadge()
            // SY -->
            MetadataBadges(metadata)
            // SY <--
        }
    }
}

/**
 * An E-Hentai result with its gallery details, as BrowseSourceEHentaiListItem: the cover, title
 * and uploader, the rating stars and category, and the language with the page count and the
 * date posted. Results without E-Hentai metadata show nothing, as on Android.
 */
@Composable
private fun BrowseSourceEHentaiListItem(
    manga: Manga,
    metadata: RaisedSearchMetadata?,
    favorite: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onSecondaryClick: () -> Unit,
) {
    if (metadata !is EHentaiSearchMetadata) return
    val languageText = remember(metadata) {
        // KMK -->
        val flag = metadata.tags
            .filter { it.namespace == EHentaiSearchMetadata.EH_LANGUAGE_NAMESPACE }
            .firstNotNullOfOrNull { SourceTags.languageFlagOrNull(it.name) }
        // KMK <--
        val pageCount = metadata.length
        if (flag != null && pageCount != null) {
            localizedPlural(SYMR.plurals.browse_language_and_pages, pageCount, pageCount, flag)
        } else if (pageCount != null) {
            localizedPlural(SYMR.plurals.num_pages, pageCount, pageCount)
        } else {
            flag.orEmpty()
        }
    }
    val datePosted = remember(metadata) {
        runCatching {
            metadata.datePosted?.let {
                MetadataUtil.EX_DATE_FORMAT.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
            }
        }.getOrNull().orEmpty()
    }
    val genre = remember(metadata) { metadata.genre?.let(SourceTags::genre) }
    val rating = remember(metadata) {
        val rating = metadata.averageRating?.toFloat()
        rating?.div(0.5F)?.floor()?.let { 0.5F.times(it) } ?: 0f
    }

    Row(
        Modifier
            .testTag("manga-item")
            .fillMaxWidth()
            .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .height(148.dp)
            .clickable(onClick = onClick)
            .onRightClick(onSecondaryClick)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            CoverImage(
                Cover.of(manga),
                Modifier.fillMaxHeight().aspectRatio(BOOK_RATIO).clip(Corners.Small).alpha(if (favorite) BROWSE_FAVORITE_COVER_ALPHA else 1f),
            )
            if (favorite) {
                Row(Modifier.padding(4.dp).align(Alignment.TopStart)) { InLibraryBadge() }
            }
        }
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    manga.title,
                    maxLines = 2,
                    modifier = Modifier.padding(start = 8.dp, top = 8.dp),
                    style = MaterialTheme.typography.titleSmall,
                    overflow = TextOverflow.Ellipsis,
                )
                metadata.uploader?.let {
                    Text(it, maxLines = 1, modifier = Modifier.padding(start = 8.dp), overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp, start = 8.dp, end = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.Start) {
                    Stars(rating)
                    Surface(
                        color = genre?.first?.color ?: MaterialTheme.colorScheme.surfaceVariant,
                        shape = Corners.Medium,
                    ) {
                        Text(
                            genre?.second?.let(::localized) ?: metadata.genre.orEmpty(),
                            color = genre?.first?.textColor ?: Color.Unspecified,
                            modifier = Modifier.padding(vertical = 4.dp, horizontal = 8.dp),
                            maxLines = 1,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.End) {
                    Text(languageText, maxLines = 1, fontSize = 14.sp)
                    Text(datePosted, maxLines = 1, fontSize = 14.sp)
                }
            }
        }
    }
}

/** Five stars with half stars, as the ComposeStars rating bar (18dp stars, 2dp apart). */
@Composable
private fun Stars(value: Float) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        (1..5).forEach { i ->
            val icon = when {
                value >= i -> Icons.Filled.Star
                value >= i - 0.5f -> Icons.Filled.StarHalf
                else -> Icons.Filled.StarBorder
            }
            Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/** The badge on entries already in the library, as InLibraryBadge. */
@Composable
private fun InLibraryBadge() {
    Badge(localized(MR.strings.in_library), MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
}

/** The MangaDex follow status and relation, or a recommendation's rank, as the browse items' end badges. */
@Composable
private fun MetadataBadges(metadata: RaisedSearchMetadata?) {
    val color = MaterialTheme.colorScheme.tertiary
    val textColor = MaterialTheme.colorScheme.onTertiary
    if (metadata is MangaDexSearchMetadata) {
        metadata.followStatus?.let { mdFollowsOptions.getOrNull(it) }?.let { Badge(localized(it), color, textColor) }
        metadata.relation?.let { Badge(localized(it.res), color, textColor) }
    } else if (metadata is RankedSearchMetadata) {
        metadata.rank?.let { Badge("+$it", color, textColor) }
    }
}

/** Android's md_follows_options array. */
private val mdFollowsOptions = listOf(
    SYMR.strings.md_follows_unfollowed,
    MR.strings.reading,
    MR.strings.completed,
    MR.strings.on_hold,
    MR.strings.plan_to_read,
    MR.strings.dropped,
    MR.strings.repeating,
)

@Composable
private fun Badge(text: String, color: Color, textColor: Color) {
    Surface(color = color, shape = Corners.OnCover) {
        Text(
            text,
            color = textColor,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
