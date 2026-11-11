package zoukan.desktop.ui.browse

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.tachiyomi.source.online.ResolvableSource
import eu.kanade.tachiyomi.source.online.UriType
import exh.GalleryAddEvent
import exh.GalleryAdder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.domain.manga.model.toDomainManga
import mihon.domain.source.interactor.UpdateMangaFromRemote
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.common.LoadingScreen
import zoukan.desktop.ui.common.ScreenHeader
import zoukan.desktop.ui.manga.MangaScreen
import zoukan.desktop.ui.reader.ReaderScreen
import zoukan.i18n.desktop.DMR

/** Browse's "Open from a URL": paste an entry's or a chapter's address. */
@Composable
fun OpenUrlDialog(onDismiss: () -> Unit) {
    val navigator = LocalNavigator.currentOrThrow
    var url by remember { mutableStateOf("") }
    val open = {
        if (url.isNotBlank()) {
            onDismiss()
            navigator.push(OpenUrlScreen(url.trim()))
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized(DMR.strings.open_from_a_url)) },
        text = {
            Column {
                Text(
                    localized(DMR.strings.paste_the_address_of_an_entry),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    placeholder = { Text("https://…") },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("open-url-field").onKeyEvent {
                        if (it.key == Key.Enter) {
                            open()
                            true
                        } else {
                            false
                        }
                    },
                )
            }
        },
        confirmButton = { TextButton(onClick = open, enabled = url.isNotBlank()) { Text(localized(KMR.strings.open)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(localized(MR.strings.action_cancel)) } },
    )
}

/**
 * Android's deep link screen (DeepLinkScreenModel), with SY's gallery links: finds the source that
 * resolves [url] and opens the entry, or the reader for a chapter's address; with no such source it
 * becomes a global search for the address, as on Android.
 */
class OpenUrlScreenModel(
    private val url: String,
    private val sourceManager: SourceManager = Injekt.get(),
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
    private val chapterRepository: ChapterRepository = Injekt.get(),
    private val updateMangaFromRemote: UpdateMangaFromRemote = Injekt.get(),
) : StateScreenModel<OpenUrlScreenModel.State>(State.Loading) {

    sealed interface State {
        data object Loading : State
        data object NoResults : State
        data class Result(val mangaId: Long, val chapterId: Long? = null) : State
    }

    init {
        screenModelScope.launch(Dispatchers.IO) {
            mutableState.value = runCatching { resolveGallery() ?: resolveSource() }
                .onFailure { logcat(LogPriority.ERROR, it) { "Could not open $url" } }
                .getOrNull() ?: State.NoResults
        }
    }

    /** SY's URL-importable sources (E-Hentai, MangaDex and the other delegated sources) */
    private suspend fun resolveGallery(): State? {
        val event = GalleryAdder().addGallery(Injekt.get<Application>(), url, fav = false)
        return (event as? GalleryAddEvent.Success)?.let { State.Result(it.manga.id, it.chapter?.id) }
    }

    /** Extensions that resolve their own addresses (ResolvableSource, extensions-lib 1.5) */
    private suspend fun resolveSource(): State? {
        val source = sourceManager.getAll()
            .filterIsInstance<ResolvableSource>()
            .firstOrNull { runCatching { it.getUriType(url) != UriType.Unknown }.getOrDefault(false) }
            ?: return null
        val manga = source.getManga(url)?.let { networkToLocalManga(it.toDomainManga(source.id)) } ?: return null
        if (source.getUriType(url) != UriType.Chapter) return State.Result(manga.id)
        val sChapter = source.getChapter(url) ?: return State.Result(manga.id)
        val chapter = chapterRepository.getChapterByUrlAndMangaId(sChapter.url, manga.id)
            ?: updateMangaFromRemote(manga = manga, fetchChapters = true).getOrNull()
                ?.newChapters
                ?.find { it.url == sChapter.url }
        return State.Result(manga.id, chapter?.id)
    }
}

data class OpenUrlScreen(val url: String) : Screen {
    // Ends with / because Voyager disposes every screen model whose key starts with a disposed screen's key
    override val key: ScreenKey = "open-url-$url/"

    @Composable
    override fun Content() {
        val model = rememberScreenModel { OpenUrlScreenModel(url) }
        val state by model.state.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        LaunchedEffect(state) {
            when (val s = state) {
                OpenUrlScreenModel.State.Loading -> Unit
                OpenUrlScreenModel.State.NoResults -> navigator.replace(GlobalSearchScreen(url))
                is OpenUrlScreenModel.State.Result -> navigator.replace(
                    if (s.chapterId != null) ReaderScreen(s.mangaId, s.chapterId) else MangaScreen(s.mangaId),
                )
            }
        }
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(localized(DMR.strings.opening), subtitle = url)
            LoadingScreen()
        }
    }

    companion object {
        fun isUrl(text: String): Boolean =
            (text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true)) && ' ' !in text
    }
}
