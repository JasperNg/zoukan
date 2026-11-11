package zoukan.desktop.ui.manga

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.AttachMoney
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.PlatformContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import com.materialkolor.dynamicColorScheme
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.quantize.QuantizerCelebi
import com.materialkolor.score.Score
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.common.Corners
import zoukan.desktop.ui.common.Cover
import zoukan.desktop.ui.common.CoverImage
import zoukan.desktop.ui.common.Images
import zoukan.desktop.ui.common.coverOutline
import zoukan.desktop.ui.common.verticalScrollWithBar
import zoukan.i18n.desktop.DMR
import java.util.concurrent.ConcurrentHashMap
import androidx.compose.ui.graphics.Brush as GradientBrush

// ZOUKAN -->

/**
 * The entry page in the colors of its cover, as Android's "Theme based on cover" (on by default):
 * a scheme generated from the cover's most fitting color, in the cover-based theme style.
 */
@Composable
fun CoverBasedTheme(manga: Manga, content: @Composable () -> Unit) {
    val ui = remember { Injekt.get<UiPreferences>() }
    val enabled by ui.themeCoverBased().changes().collectAsState(ui.themeCoverBased().get())
    val style by ui.themeCoverBasedStyle().changes().collectAsState(ui.themeCoverBasedStyle().get())
    val amoled by ui.themeDarkAmoled().changes().collectAsState(ui.themeDarkAmoled().get())
    val cover = Cover.of(manga)
    val seed by produceState(cover?.let(CoverColors::cached), cover, enabled) {
        value = if (enabled && cover != null) CoverColors.seed(cover) else null
    }
    val base = MaterialTheme.colorScheme
    val dark = base.background.luminance() < 0.5f
    val scheme = remember(seed, enabled, style, dark, amoled) {
        seed?.takeIf { enabled }?.let {
            dynamicColorScheme(
                seedColor = Color(it),
                isDark = dark,
                isAmoled = amoled && dark,
                style = style,
                specVersion = ColorSpec.SpecVersion.SPEC_2025,
            )
        }
    }
    if (scheme == null) {
        content()
    } else {
        MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes, content = content)
    }
}

/** Each cover's seed color, picked as Material You picks one from a wallpaper. */
private object CoverColors {
    private val seeds = ConcurrentHashMap<Cover, Int>()

    fun cached(cover: Cover): Int? = seeds[cover]

    suspend fun seed(cover: Cover): Int? {
        seeds[cover]?.let { return it }
        val request = ImageRequest.Builder(PlatformContext.INSTANCE).data(cover).size(112).build()
        val result = Images.loader.execute(request) as? SuccessResult ?: return null
        return withContext(Dispatchers.Default) {
            val pixels = result.image.toBitmap().asComposeImageBitmap().toPixelMap().buffer
                .filter { (it ushr 24) >= 255 }
                .toIntArray()
            if (pixels.isEmpty()) return@withContext null
            val quantized = QuantizerCelebi.quantize(pixels, 128)
            Score.score(quantized, desired = 1, fallbackColorArgb = NO_COLOR, filter = true)
                .firstOrNull()
                ?.takeIf { it != NO_COLOR }
        }?.also { seeds[cover] = it }
    }

    private const val NO_COLOR = 0
}

/**
 * The details pane, as Komikku's on tablets: the cover centered over a blurred backdrop of
 * itself, the title, author and status centered under it, the action row, then the description.
 */
@Composable
fun MangaInfoPane(
    manga: Manga,
    state: MangaScreenModel.State,
    model: MangaScreenModel,
    onWebView: (() -> Unit)?,
    onSearch: (String) -> Unit,
    onOpenMetadata: () -> Unit,
    modifier: Modifier = Modifier,
    onCoverClick: (() -> Unit)? = null,
) {
    Column(modifier.verticalScrollWithBar()) {
        InfoHeader(
            manga,
            sourceName = state.source?.name ?: localized(DMR.strings.source_not_installed_2),
            missingSource = state.source == null,
            onCoverClick = onCoverClick,
        )
        ActionRow(
            favorite = manga.favorite,
            trackCount = state.trackCount,
            onFavorite = model::toggleFavorite,
            onTracking = { model.setTrackingVisible(true) },
            onWebView = onWebView,
        )
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            val meta = state.meta
            if (meta != null && hasMetadataDescription(state.source)) {
                MetadataDescription(meta, openMetadataViewer = onOpenMetadata, search = onSearch)
            }
            manga.description?.takeIf { it.isNotBlank() }?.let { Description(it) }
            manga.notes.takeIf { it.isNotBlank() }?.let { notes ->
                Text(localized(MR.strings.action_notes), style = MaterialTheme.typography.titleSmall)
                Text(notes, style = MaterialTheme.typography.bodyMedium)
            }
            // Metadata sources show their tags by namespace, each searching the source
            if (meta != null && meta.tags.isNotEmpty()) {
                NamespaceTags(meta, manga.source, onClick = onSearch)
            } else {
                manga.genre?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }?.let { genres ->
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        genres.forEach { AssistChip(onClick = { onSearch(it) }, label = { Text(it) }) }
                    }
                }
            }
        }
        // ZOUKAN -->
        // Komikku's related titles row, then SY's page previews, under the description
        RelatedTitlesSection(manga)
        PagePreviewSection(manga)
        // ZOUKAN <--
    }
}

@Composable
private fun InfoHeader(manga: Manga, sourceName: String, missingSource: Boolean, onCoverClick: (() -> Unit)?) {
    val cover = Cover.of(manga)
    Box {
        // Backdrop: the cover, blurred and faded into the background
        val background = MaterialTheme.colorScheme.background
        AsyncImage(
            model = cover,
            imageLoader = Images.loader,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .matchParentSize()
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = GradientBrush.verticalGradient(
                            colors = listOf(Color.Transparent, background),
                            startY = size.height / 2,
                        ),
                    )
                }
                .background(MaterialTheme.colorScheme.surfaceTint.copy(alpha = 0.4f))
                .blur(7.dp)
                .alpha(0.2f),
        )
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            Column(
                Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, end = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CoverImage(
                    cover,
                    Modifier
                        .fillMaxWidth(0.65f)
                        .aspectRatio(2f / 3f)
                        .clip(Corners.Medium)
                        .coverOutline(Corners.Medium)
                        .then(if (onCoverClick != null) Modifier.clickable(onClick = onCoverClick) else Modifier),
                )
                Spacer(Modifier.height(16.dp))
                // Selectable, so the title can be copied
                SelectionContainer {
                    Text(
                        manga.title.ifBlank { localized(MR.strings.unknown_title) },
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(2.dp))
                IconLine(Icons.Outlined.PersonOutline, manga.author?.takeIf { it.isNotBlank() } ?: localized(MR.strings.unknown_author))
                manga.artist?.takeIf { it.isNotBlank() && it != manga.author }?.let { IconLine(Icons.Outlined.Brush, it) }
                Spacer(Modifier.height(2.dp))
                Row(Modifier.alpha(SECONDARY_ALPHA), verticalAlignment = Alignment.CenterVertically) {
                    Icon(statusIcon(manga.status), null, Modifier.padding(end = 4.dp).size(16.dp))
                    Text(
                        statusLabel(manga.status) ?: localized(MR.strings.unknown),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(" • ", style = MaterialTheme.typography.bodyMedium)
                    if (missingSource) {
                        Icon(
                            Icons.Outlined.Warning,
                            null,
                            Modifier.padding(end = 4.dp).size(16.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(sourceName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun IconLine(icon: ImageVector, text: String) {
    Row(
        Modifier.alpha(SECONDARY_ALPHA),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
    }
}

private fun statusIcon(status: Long): ImageVector = when (status.toInt()) {
    SManga.ONGOING -> Icons.Outlined.Schedule
    SManga.COMPLETED -> Icons.Outlined.DoneAll
    SManga.LICENSED -> Icons.Outlined.AttachMoney
    SManga.PUBLISHING_FINISHED -> Icons.Outlined.Done
    SManga.CANCELLED -> Icons.Outlined.Close
    SManga.ON_HIATUS -> Icons.Outlined.Pause
    else -> Icons.Outlined.Block
}

/** Komikku's row of labelled icons under the header: library, tracking and WebView. */
@Composable
private fun ActionRow(
    favorite: Boolean,
    trackCount: Int,
    onFavorite: () -> Unit,
    onTracking: () -> Unit,
    onWebView: (() -> Unit)?,
) {
    val inactive = MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
    val active = MaterialTheme.colorScheme.primary
    Row(Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp)) {
        ActionButton(
            if (favorite) localized(MR.strings.in_library) else localized(MR.strings.add_to_library),
            if (favorite) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
            if (favorite) active else inactive,
            onFavorite,
        )
        ActionButton(
            when (trackCount) {
                0 -> localized(MR.strings.track)
                1 -> localized(DMR.strings.n_1_tracker)
                else -> localized(DMR.strings.trackers, trackCount)
            },
            if (trackCount == 0) Icons.Outlined.Sync else Icons.Outlined.Done,
            if (trackCount == 0) inactive else active,
            onTracking,
        )
        if (onWebView != null) ActionButton("WebView", Icons.Outlined.Public, active, onWebView)
    }
}

@Composable
private fun RowScope.ActionButton(title: String, icon: ImageVector, color: Color, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.weight(1f)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(4.dp))
            Text(title, color = color, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
    }
}

/**
 * The description, selectable so it can be copied. A click would start a selection, so the
 * long text opens and closes with its own button instead of a click on the text.
 */
@Composable
private fun Description(text: String) {
    var expanded by remember { mutableStateOf(false) }
    var overflows by remember(text) { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = Corners.Medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            SelectionContainer {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) Int.MAX_VALUE else 6,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
                )
            }
            if (expanded || overflows) {
                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.align(Alignment.End)) {
                    Text(if (expanded) localized(DMR.strings.show_less) else localized(DMR.strings.show_more))
                }
            }
        }
    }
}

private const val SECONDARY_ALPHA = 0.78f
private const val DISABLED_ALPHA = 0.38f
// ZOUKAN <--
