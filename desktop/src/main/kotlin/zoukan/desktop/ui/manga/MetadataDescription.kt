package zoukan.desktop.ui.manga

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.StarHalf
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.desc.PluralFormatted
import dev.icerock.moko.resources.desc.StringDesc
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.MetadataSource
import eu.kanade.tachiyomi.source.online.all.EHentai
import eu.kanade.tachiyomi.source.online.all.Lanraragi
import eu.kanade.tachiyomi.source.online.all.MangaDex
import eu.kanade.tachiyomi.source.online.all.NHentai
import eu.kanade.tachiyomi.source.online.all.Pururin
import eu.kanade.tachiyomi.source.online.english.EightMuses
import exh.metadata.MetadataUtil
import exh.metadata.metadata.EHentaiSearchMetadata
import exh.metadata.metadata.EightMusesSearchMetadata
import exh.metadata.metadata.LanraragiSearchMetadata
import exh.metadata.metadata.MangaDexSearchMetadata
import exh.metadata.metadata.NHentaiSearchMetadata
import exh.metadata.metadata.PururinSearchMetadata
import exh.metadata.metadata.RaisedSearchMetadata
import exh.source.eHentaiSourceIds
import exh.source.getMainSource
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import zoukan.desktop.compat.localized
import zoukan.desktop.exh.SourceTags
import zoukan.desktop.ui.common.Toasts
import zoukan.desktop.ui.common.onRightClick
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.round

/*
 * The per-source details block above the description (the Android app's exh.ui.metadata.adapters,
 * which are Android views there): rating, category, pages, uploader and a "More info" button that
 * opens the metadata viewer. Right-click copies a value, as a long press does on Android.
 */

/** Whether the entry's source has a details block, as Android's metadataDescription(source). */
fun hasMetadataDescription(source: Source?): Boolean = when (source?.getMainSource<MetadataSource<*, *>>()) {
    is EHentai, is MangaDex, is NHentai, is EightMuses, is Pururin, is Lanraragi -> true
    else -> false
}

@Composable
fun MetadataDescription(
    meta: RaisedSearchMetadata,
    openMetadataViewer: () -> Unit,
    search: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (meta) {
            is EHentaiSearchMetadata -> EHentaiDescription(meta, search)
            is MangaDexSearchMetadata -> MangaDexDescription(meta)
            is NHentaiSearchMetadata -> NHentaiDescription(meta)
            is EightMusesSearchMetadata -> Value(meta.title ?: localized(MR.strings.unknown))
            is PururinSearchMetadata -> PururinDescription(meta)
            is LanraragiSearchMetadata -> LanraragiDescription(meta)
            else -> Unit
        }
        TextButton(onClick = openMetadataViewer, contentPadding = SmallPadding) {
            Icon(Icons.Outlined.Info, null, Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(localized(SYMR.strings.more_info))
        }
    }
}

@Composable
private fun EHentaiDescription(meta: EHentaiSearchMetadata, search: (String) -> Unit) {
    val unknown = localized(MR.strings.unknown)
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            GenreChip(meta.genre)
            val length = meta.length ?: 0
            Value(pages(length), Icons.AutoMirrored.Filled.MenuBook)
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val rating = meta.averageRating?.toFloat()
            Rating(rating ?: 0f, "${rating ?: 0f} - ${localized(SourceTags.rating(rating?.times(2)))}")
            val language = (meta.language ?: unknown).let { "${SourceTags.languageFlag(it)} $it" }
            Value(if (meta.translated == true) localized(SYMR.strings.language_translated, language) else language)
            Value(MetadataUtil.humanReadableByteCount(meta.size ?: 0, true), Icons.Outlined.SdCard)
            Value((meta.favorites ?: 0).toString(), Icons.Default.Book)
            Value(localized(SYMR.strings.is_visible, meta.visible ?: unknown))
            Value(meta.uploader ?: unknown, onClick = meta.uploader?.let { { search("uploader:\"$it\"") } })
        }
    }
}

@Composable
private fun MangaDexDescription(meta: MangaDexSearchMetadata) {
    val rating = meta.rating ?: return
    Rating(rating / 2f, "${round(rating * 100.0) / 100.0} - ${localized(SourceTags.rating(rating))}")
}

@Composable
private fun NHentaiDescription(meta: NHentaiSearchMetadata) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val categories = meta.tags.filter { it.namespace == NHentaiSearchMetadata.NHENTAI_CATEGORIES_NAMESPACE }
                .takeIf { it.isNotEmpty() }
                ?.joinToString { it.name }
            GenreChip(categories)
            val pages = meta.pageImagePreviewUrls.size
            Value(pages(pages), Icons.AutoMirrored.Filled.MenuBook)
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Value("#" + (meta.nhId ?: 0))
            meta.favoritesCount?.takeIf { it != 0L }?.let { Value(it.toString(), Icons.Default.Book) }
            Value(
                MetadataUtil.EX_DATE_FORMAT.format(
                    ZonedDateTime.ofInstant(Instant.ofEpochSecond(meta.uploadDate ?: 0), ZoneId.systemDefault()),
                ),
            )
        }
    }
}

@Composable
private fun PururinDescription(meta: PururinSearchMetadata) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            GenreChip(meta.tags.find { it.namespace == PururinSearchMetadata.TAG_NAMESPACE_CATEGORY }?.name)
            Value(pages(meta.pages ?: 0), Icons.AutoMirrored.Filled.MenuBook)
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val rating = meta.averageRating?.toFloat()
            Rating(
                rating ?: 0f,
                "${round((rating ?: 0f) * 100.0) / 100.0} - ${localized(SourceTags.rating(rating?.times(2)))}",
            )
            Value(meta.uploaderDisp ?: meta.uploader.orEmpty())
            Value(meta.fileSize ?: localized(MR.strings.unknown), Icons.Outlined.SdCard)
        }
    }
}

@Composable
private fun LanraragiDescription(meta: LanraragiSearchMetadata) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Value(pages(meta.pageCount ?: 1), Icons.AutoMirrored.Filled.MenuBook)
        Value(meta.extension?.uppercase().orEmpty())
    }
}

private fun pages(count: Int): String =
    StringDesc.PluralFormatted(SYMR.plurals.num_pages, count, count).localized()

/** The E-Hentai category label on its category color, or the plain text for other categories. */
@Composable
private fun GenreChip(genre: String?) {
    val known = genre?.let(SourceTags::genre)
    val text = known?.second?.let(::localized) ?: genre ?: localized(MR.strings.unknown)
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = known?.first?.textColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .then(if (known != null) Modifier.background(known.first.color) else Modifier)
            .copyOnRightClick(text)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** Five stars (half stars too) and the rating's text, as Android's rating bar. */
@Composable
private fun Rating(stars: Float, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.copyOnRightClick(text)) {
        (1..5).forEach { i ->
            val icon = when {
                stars >= i -> Icons.Default.Star
                stars >= i - 0.5f -> Icons.Default.StarHalf
                else -> Icons.Default.StarBorder
            }
            Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Value(text: String, icon: ImageVector? = null, onClick: (() -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .copyOnRightClick(text),
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * The entry's tags grouped by namespace, each tag searching its source, as Android's NamespaceTags
 * (E-Hentai's weak tags have a thinner border).
 */
@Composable
fun NamespaceTags(meta: RaisedSearchMetadata, sourceId: Long, onClick: (String) -> Unit) {
    val groups = meta.tags
        .filterNot { it.type == RaisedSearchMetadata.TAG_TYPE_VIRTUAL }
        .groupBy { it.namespace.orEmpty() }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        groups.forEach { (namespace, tags) ->
            Row {
                if (namespace.isNotEmpty()) {
                    Text(
                        namespace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .padding(top = 6.dp, end = 8.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    tags.forEach { tag ->
                        val search = if (!tag.namespace.isNullOrEmpty()) {
                            SourceTags.getWrappedTag(sourceId, namespace = tag.namespace, tag = tag.name)
                        } else {
                            SourceTags.getWrappedTag(sourceId, fullTag = tag.name)
                        } ?: tag.name
                        val border = if (sourceId in eHentaiSourceIds) {
                            when (tag.type) {
                                EHentaiSearchMetadata.TAG_TYPE_NORMAL -> 2.dp
                                EHentaiSearchMetadata.TAG_TYPE_LIGHT -> 1.dp
                                else -> null
                            }
                        } else {
                            null
                        }
                        SuggestionChip(
                            onClick = { onClick(search) },
                            label = { Text(tag.name, style = MaterialTheme.typography.bodySmall) },
                            border = BorderStroke(border ?: 1.dp, MaterialTheme.colorScheme.primary),
                            colors = SuggestionChipDefaults.suggestionChipColors(),
                            modifier = Modifier.copyOnRightClick(tag.name),
                        )
                    }
                }
            }
        }
    }
}

private val SmallPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 0.dp)

fun copyToClipboard(text: String) {
    runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
        .onSuccess { Toasts.show(localized(MR.strings.copied_to_clipboard, text.take(50))) }
}

private fun Modifier.copyOnRightClick(text: String): Modifier = onRightClick { copyToClipboard(text) }
