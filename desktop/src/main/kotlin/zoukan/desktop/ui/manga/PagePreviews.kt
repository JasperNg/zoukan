package zoukan.desktop.ui.manga

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import zoukan.desktop.domain.toSChapter
import zoukan.desktop.domain.toSManga
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.source.PagePreviewInfo
import eu.kanade.tachiyomi.source.PagePreviewSource
import exh.source.getMainSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Buffer
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.ui.common.EmptyScreen
import zoukan.desktop.ui.common.Images
import zoukan.desktop.ui.common.LoadingScreen
import zoukan.desktop.ui.common.ScreenHeader
import zoukan.desktop.ui.reader.ReaderScreen
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Page previews (SY): thumbnails of an entry's pages, from sources that offer them (E-Hentai,
 * nhentai, LANraragi). The manga page shows the first rows; More previews opens them all.
 */
object PagePreviews {

    /** One page of previews, as Android's GetPagePreviews.Result.Success. */
    data class Page(val previews: List<PagePreviewImage>, val hasNextPage: Boolean, val pageCount: Int?)

    /** Pages already fetched this session, by entry and preview page, as Android's PagePreviewCache. */
    private val cache = ConcurrentHashMap<Pair<Long, Int>, Page>()

    /** The source's previews, or null when the source has none. */
    fun sourceOf(manga: Manga): PagePreviewSource? =
        Injekt.get<SourceManager>().get(manga.source)?.getMainSource<PagePreviewSource>()

    suspend fun load(manga: Manga, page: Int): Page? {
        val source = sourceOf(manga) ?: return null
        cache[manga.id to page]?.let { return it }
        return withContext(Dispatchers.IO) {
            val chapters = Injekt.get<GetChaptersByMangaId>().await(manga.id).sortedByDescending { it.sourceOrder }
            val result = source.getPagePreviewList(manga.toSManga(), chapters.map { it.toSChapter() }, page)
            Page(
                result.pagePreviews.map { PagePreviewImage(source.id, it.index, it.imageUrl) },
                result.hasNextPage,
                result.pagePreviewPages,
            ).also { cache[manga.id to page] = it }
        }
    }

    /** Opens the reader at a preview's page, in the entry's first chapter, as Android does. */
    suspend fun readerFor(manga: Manga, preview: PagePreviewImage): ReaderScreen? {
        val first = Injekt.get<GetChaptersByMangaId>().await(manga.id).minByOrNull { it.sourceOrder } ?: return null
        return ReaderScreen(manga.id, first.id, page = preview.index - 1)
    }
}

/** A page preview image for Coil, fetched by its source (which may need its own request). */
data class PagePreviewImage(val sourceId: Long, val index: Int, val imageUrl: String)

internal class PagePreviewKeyer : Keyer<PagePreviewImage> {
    override fun key(data: PagePreviewImage, options: Options) = "preview:${data.sourceId}:${data.imageUrl}"
}

internal class PagePreviewFetcher(private val image: PagePreviewImage) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val source = Injekt.get<SourceManager>().get(image.sourceId)?.getMainSource<PagePreviewSource>()
            ?: throw IOException("The source of this preview is not installed")
        val bytes = source.fetchPreviewImage(PagePreviewInfo(image.index, image.imageUrl)).use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for ${image.imageUrl}")
            response.body.bytes()
        }
        return SourceFetchResult(
            source = ImageSource(Buffer().write(bytes), okio.FileSystem.SYSTEM),
            mimeType = null,
            dataSource = DataSource.NETWORK,
        )
    }

    class Factory : Fetcher.Factory<PagePreviewImage> {
        override fun create(data: PagePreviewImage, options: Options, imageLoader: ImageLoader): Fetcher = PagePreviewFetcher(data)
    }
}

private val PREVIEW_WIDTH = 120.dp

@Composable
private fun PreviewThumbnail(preview: PagePreviewImage, onOpen: (PagePreviewImage) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(MaterialTheme.shapes.small).clickable { onOpen(preview) },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AsyncImage(
            model = preview,
            imageLoader = Images.loader,
            contentDescription = "Page ${preview.index}",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().aspectRatio(0.7f).clip(MaterialTheme.shapes.small),
        )
        Text(preview.index.toString(), style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * The manga page's previews: Settings > Appearance's row count of thumbnails, as many per row as
 * fit, then More previews. Shows nothing for sources without previews.
 */
@Composable
fun PagePreviewSection(manga: Manga, modifier: Modifier = Modifier) {
    if (remember(manga.source) { PagePreviews.sourceOf(manga) } == null) return
    val navigator = LocalNavigator.currentOrThrow
    val prefs = remember { Injekt.get<UiPreferences>() }
    val rows by prefs.previewsRowCount().changes().collectAsState(prefs.previewsRowCount().get())
    if (rows <= 0) return
    val page by produceState<Result<PagePreviews.Page?>?>(null, manga.id) {
        value = try {
            Result.success(PagePreviews.load(manga, 1))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    var opening by remember { mutableStateOf<PagePreviewImage?>(null) }
    LaunchedEffect(opening) {
        val preview = opening ?: return@LaunchedEffect
        PagePreviews.readerFor(manga, preview)?.let { navigator.push(it) }
        opening = null
    }
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        val result = page
        when {
            result == null -> Box(Modifier.fillMaxWidth().height(60.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(32.dp))
            }
            result.isFailure -> Text(
                "Couldn't load page previews: ${result.exceptionOrNull()?.message ?: "unknown error"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            else -> {
                val previews = result.getOrNull()?.previews.orEmpty()
                if (previews.isEmpty()) return@BoxWithConstraints
                val perRow = (maxWidth / PREVIEW_WIDTH).toInt().coerceAtLeast(1)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    previews.take(rows * perRow).chunked(perRow).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { PreviewThumbnail(it, onOpen = { p -> opening = p }, Modifier.weight(1f)) }
                            // Keep a short last row's thumbnails the size of the others
                            repeat(perRow - row.size) { Box(Modifier.weight(1f)) }
                        }
                    }
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        TextButton(onClick = { navigator.push(PagePreviewScreen(manga.id)) }) { Text("More previews") }
                    }
                }
            }
        }
    }
}

/** Every page preview of an entry, loaded a page at a time as the grid scrolls (SY's PagePreviewScreen). */
data class PagePreviewScreen(val mangaId: Long) : Screen {
    override val key: ScreenKey = "page-previews-$mangaId/"

    class Model(private val mangaId: Long) : StateScreenModel<Model.State>(State()) {
        data class State(
            val manga: Manga? = null,
            val previews: List<PagePreviewImage> = emptyList(),
            val nextPage: Int? = 1,
            val pageCount: Int? = null,
            val loading: Boolean = false,
            val error: String? = null,
        )

        init {
            screenModelScope.launch {
                val manga = Injekt.get<GetManga>().await(mangaId)
                mutableState.update { it.copy(manga = manga, error = if (manga == null) "Entry not found" else null) }
                loadMore()
            }
        }

        fun loadMore() {
            val s = state.value
            val manga = s.manga ?: return
            val page = s.nextPage ?: return
            if (s.loading) return
            mutableState.update { it.copy(loading = true, error = null) }
            screenModelScope.launch {
                try {
                    val result = PagePreviews.load(manga, page)
                    mutableState.update {
                        it.copy(
                            previews = it.previews + result?.previews.orEmpty(),
                            nextPage = if (result?.hasNextPage == true) page + 1 else null,
                            pageCount = result?.pageCount ?: it.pageCount,
                            loading = false,
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    mutableState.update { it.copy(loading = false, error = e.message ?: "Couldn't load page previews") }
                }
            }
        }
    }

    @Composable
    override fun Content() {
        val model = rememberScreenModel { Model(mangaId) }
        val state by model.state.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        var opening by remember { mutableStateOf<PagePreviewImage?>(null) }
        LaunchedEffect(opening) {
            val preview = opening ?: return@LaunchedEffect
            state.manga?.let { manga -> PagePreviews.readerFor(manga, preview)?.let { navigator.push(it) } }
            opening = null
        }
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("Page previews", subtitle = state.manga?.title)
            when {
                state.previews.isEmpty() && state.error != null -> EmptyScreen(state.error!!)
                state.previews.isEmpty() && (state.loading || state.manga == null) -> LoadingScreen()
                state.previews.isEmpty() -> EmptyScreen("This entry has no page previews")
                else -> {
                    val grid = rememberLazyGridState()
                    val nearEnd by remember {
                        derivedStateOf {
                            val last = grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                            last >= grid.layoutInfo.totalItemsCount - 6
                        }
                    }
                    LaunchedEffect(nearEnd, state.previews.size) { if (nearEnd) model.loadMore() }
                    LazyVerticalGrid(
                        GridCells.Adaptive(PREVIEW_WIDTH),
                        state = grid,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.previews, key = { it.index }) { preview ->
                            PreviewThumbnail(preview, onOpen = { opening = it })
                        }
                        if (state.loading || state.error != null) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                    if (state.loading) {
                                        CircularProgressIndicator(Modifier.size(32.dp))
                                    } else {
                                        TextButton(onClick = model::loadMore) { Text("Retry: ${state.error}") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
