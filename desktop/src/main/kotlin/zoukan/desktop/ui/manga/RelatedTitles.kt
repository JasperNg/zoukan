package zoukan.desktop.ui.manga

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.ui.UiPreferences
import exh.source.MERGED_SOURCE_ID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.domain.manga.model.toDomainManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.compat.localized
import zoukan.desktop.domain.toSManga
import zoukan.desktop.ui.common.Cover
import zoukan.desktop.ui.common.MangaGridItem
import zoukan.desktop.ui.recs.RecommendsScreen
import zoukan.i18n.desktop.DMR

/**
 * Komikku's related titles on the entry page: the source's related titles in a row under the
 * description, collapsible (Appearance > Expand related titles), with See all opening Similar
 * titles. Hidden when related titles are off or kept in the overflow menu, and for merged entries.
 */
@Composable
fun RelatedTitlesSection(manga: Manga, modifier: Modifier = Modifier) {
    val sourcePrefs = remember { Injekt.get<SourcePreferences>() }
    val uiPrefs = remember { Injekt.get<UiPreferences>() }
    val enabled by sourcePrefs.relatedMangas().changes().collectAsState(sourcePrefs.relatedMangas().get())
    val inOverflow by uiPrefs.relatedMangasInOverflow().changes().collectAsState(uiPrefs.relatedMangasInOverflow().get())
    if (!enabled || inOverflow || manga.source == MERGED_SOURCE_ID) return
    val navigator = LocalNavigator.currentOrThrow
    var expanded by remember(manga.id) { mutableStateOf(uiPrefs.expandRelatedMangas().get()) }
    // null while loading
    var related by remember(manga.id) { mutableStateOf<List<Manga>?>(null) }
    LaunchedEffect(manga.id) {
        related = try {
            loadRelated(manga) { related = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            related.orEmpty()
        }
    }
    val list = related
    // Nothing to show once the source answered without titles
    if (list != null && list.isEmpty()) return
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(localized(DMR.strings.related_titles), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { navigator.push(RecommendsScreen(manga.id)) }) { Text(localized(DMR.strings.see_all)) }
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (expanded) localized(DMR.strings.collapse) else localized(DMR.strings.expand))
        }
        if (!expanded) return@Column
        if (list == null) {
            Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp))
            }
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(list, key = { it.id }) { item ->
                    MangaGridItem(
                        title = item.title,
                        cover = Cover.of(item),
                        onClick = { navigator.push(MangaScreen(item.id)) },
                        badge = if (item.favorite) localized(MR.strings.in_library) else null,
                        modifier = Modifier.width(120.dp),
                    )
                }
            }
        }
    }
}

/** The source's related titles as library entries, reported to [onPartial] as they arrive. */
private suspend fun loadRelated(manga: Manga, onPartial: (List<Manga>) -> Unit): List<Manga> {
    val source = Injekt.get<SourceManager>().get(manga.source) ?: return emptyList()
    val networkToLocalManga = Injekt.get<NetworkToLocalManga>()
    val found = mutableListOf<Manga>()
    withContext(Dispatchers.IO) {
        source.getRelatedMangaList(manga.toSManga(), exceptionHandler = {}) { (_, list), _ ->
            val local = networkToLocalManga(list.map { it.toDomainManga(source.id) })
            val snapshot = synchronized(found) {
                found += local.filter { it.id != manga.id && found.none { f -> f.id == it.id } }
                found.toList()
            }
            onPartial(snapshot)
        }
    }
    return synchronized(found) { found.toList() }
}
