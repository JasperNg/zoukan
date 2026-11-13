package zoukan.desktop.ui.browse

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.source.interactor.GetExhSavedSearch
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.MetadataMangasPage
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.MetadataSource
import eu.kanade.tachiyomi.source.online.all.MangaDex
import exh.metadata.metadata.RaisedSearchMetadata
import exh.source.getMainSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import logcat.LogPriority
import mihon.domain.manga.model.toDomainManga
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetFlatMetadataById
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.interactor.DeleteSavedSearchById
import tachiyomi.domain.source.interactor.InsertSavedSearch
import tachiyomi.domain.source.model.EXHSavedSearch
import tachiyomi.domain.source.model.SavedSearch
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import xyz.nulldev.ts.api.http.serializer.FilterSerializer
import zoukan.desktop.browser.BrowserWindow
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.common.DesktopPreferences
import zoukan.desktop.ui.common.Toasts
import zoukan.i18n.desktop.DMR

class SourceScreenModel(
    sourceId: Long,
    latest: Boolean,
    initialQuery: String?,
    // ZOUKAN -->
    savedSearchId: Long? = null,
    /** The MangaDex follows instead of the source's listings, as Android's MangaDexFollowsScreen */
    follows: Boolean = false,
    private val getExhSavedSearch: GetExhSavedSearch = Injekt.get(),
    private val insertSavedSearch: InsertSavedSearch = Injekt.get(),
    private val deleteSavedSearchById: DeleteSavedSearchById = Injekt.get(),
    private val filterSerializer: FilterSerializer = Injekt.get(),
    private val getFlatMetadataById: GetFlatMetadataById = Injekt.get(),
    // ZOUKAN <--
    sourceManager: SourceManager = Injekt.get(),
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
    getFavorites: GetFavorites = Injekt.get(),
    preferences: DesktopPreferences = Injekt.get(),
) : StateScreenModel<SourceScreenModel.State>(State()) {

    val source: CatalogueSource? = sourceManager.get(sourceId) as? CatalogueSource

    sealed interface Listing {
        data object Popular : Listing
        data object Latest : Listing
        data class Search(val query: String) : Listing

        // ZOUKAN -->
        /** The signed-in user's MangaDex follows, as MangaDexFollowsPagingSource */
        data object Follows : Listing
        // ZOUKAN <--
    }

    data class State(
        val listing: Listing = Listing.Popular,
        val items: List<Manga> = emptyList(),
        val page: Int = 0,
        val hasNextPage: Boolean = true,
        val loading: Boolean = false,
        val error: String? = null,
        val favorites: Set<Long> = emptySet(),
        /** Bumped when a filter's state changes, so the filter panel redraws */
        val filterVersion: Int = 0,
        // ZOUKAN -->
        val savedSearches: List<EXHSavedSearch> = emptyList(),
        /** The saved search the listing shows, if any */
        val savedSearch: Long? = null,
        /** The search metadata of each result (E-Hentai galleries, MangaDex follows), by entry id */
        val metadata: Map<Long, RaisedSearchMetadata> = emptyMap(),
        // ZOUKAN <--
    )

    // ZOUKAN -->
    /**
     * The source's default filters, or none when the source fails to make them. Called from the UI
     * thread (the screen's creation, button clicks), where an exception closes the app: the
     * extension can throw, or have been updated or uninstalled while the screen was open (see
     * ExtensionClassLoader).
     */
    private fun defaultFilters(): FilterList = try {
        source?.getFilterList() ?: FilterList()
    } catch (e: VirtualMachineError) {
        throw e
    } catch (e: Throwable) {
        logcat(LogPriority.ERROR, e) { "Failed to get the filters of ${source?.name}" }
        FilterList()
    }
    // ZOUKAN <--

    var filters: FilterList = defaultFilters()
        private set

    /** Whether the source has filters, read once instead of on every recomposition. */
    val hasFilters: Boolean = filters.isNotEmpty()

    private var job: Job? = null

    init {
        if (source != null) preferences.lastUsedSource().set(source.id)
        screenModelScope.launch {
            getFavorites.subscribe(sourceId).collect { favorites ->
                mutableState.update { it.copy(favorites = favorites.mapTo(HashSet()) { m -> m.id }) }
            }
        }
        // ZOUKAN -->
        val source = source
        if (source != null) {
            screenModelScope.launch {
                getExhSavedSearch.subscribe(source.id, ::defaultFilters).collect { searches ->
                    mutableState.update { it.copy(savedSearches = searches.sortedBy { s -> s.name.lowercase() }) }
                }
            }
        }
        if (follows) {
            setListing(Listing.Follows)
        } else if (source != null && savedSearchId != null) {
            screenModelScope.launch {
                val saved = getExhSavedSearch.awaitOne(savedSearchId, ::defaultFilters)
                if (saved != null) applySavedSearch(saved) else setListing(Listing.Popular)
            }
        } else {
            // ZOUKAN <--
            val listing = when {
                !initialQuery.isNullOrBlank() -> Listing.Search(initialQuery)
                latest && source?.supportsLatest == true -> Listing.Latest
                else -> Listing.Popular
            }
            setListing(listing)
        }
    }

    fun setListing(listing: Listing, savedSearch: Long? = null) {
        job?.cancel()
        mutableState.update {
            it.copy(listing = listing, items = emptyList(), /* ZOUKAN --> */metadata = emptyMap(), /* ZOUKAN <-- */page = 0, hasNextPage = true, error = null, loading = false, savedSearch = savedSearch)
        }
        loadNextPage()
    }

    fun search(query: String) = setListing(Listing.Search(query.trim()))

    fun filtersChanged() = mutableState.update { it.copy(filterVersion = it.filterVersion + 1) }

    fun resetFilters() {
        filters = defaultFilters()
        filtersChanged()
    }

    fun applyFilters() {
        val query = (state.value.listing as? Listing.Search)?.query.orEmpty()
        setListing(Listing.Search(query))
    }

    // ZOUKAN -->
    /**
     * Searches by a genre from an entry's tags, as Android's searchGenre: with the source's default
     * filters, the filter named as the genre is turned on, or the genre is the query when no
     * filter has its name.
     */
    fun searchGenre(genreName: String) {
        val defaultFilters = defaultFilters()
        var genreExists = false

        filter@ for (sourceFilter in defaultFilters) {
            if (sourceFilter is eu.kanade.tachiyomi.source.model.Filter.Group<*>) {
                for (filter in sourceFilter.state) {
                    if (filter is eu.kanade.tachiyomi.source.model.Filter<*> && filter.name.equals(genreName, true)) {
                        when (filter) {
                            is eu.kanade.tachiyomi.source.model.Filter.TriState -> filter.state = 1
                            is eu.kanade.tachiyomi.source.model.Filter.CheckBox -> filter.state = true
                            else -> {}
                        }
                        genreExists = true
                        break@filter
                    }
                }
            } else if (sourceFilter is eu.kanade.tachiyomi.source.model.Filter.Select<*>) {
                val index = sourceFilter.values.filterIsInstance<String>()
                    .indexOfFirst { it.equals(genreName, true) }

                if (index != -1) {
                    sourceFilter.state = index
                    genreExists = true
                    break
                }
            }
        }

        filters = defaultFilters
        filtersChanged()
        setListing(Listing.Search(if (genreExists) "" else genreName))
    }

    /** Shows a saved search: its query with its filters, as the Android app does. */
    fun applySavedSearch(search: EXHSavedSearch) {
        filters = search.filterList ?: defaultFilters()
        filtersChanged()
        setListing(Listing.Search(search.query.orEmpty()), savedSearch = search.id)
    }

    /** Saves the current query and filters under [name]. */
    fun saveSearch(name: String) {
        val source = source ?: return
        val query = (state.value.listing as? Listing.Search)?.query?.takeIf { it.isNotBlank() }
        val filterList = filters.ifEmpty { defaultFilters() }
        screenModelScope.launch(Dispatchers.IO) {
            insertSavedSearch.await(
                SavedSearch(
                    id = -1,
                    source = source.id,
                    name = name.trim(),
                    query = query,
                    filtersJson = runCatching {
                        filterSerializer.serialize(filterList).ifEmpty { null }?.let { Json.encodeToString(it) }
                    }.getOrNull(),
                ),
            )
        }
    }

    fun deleteSavedSearch(id: Long) {
        screenModelScope.launch(Dispatchers.IO) { deleteSavedSearchById.await(id) }
    }

    /** Finds a random MangaDex entry and gives its id, as Android's onMangaDexRandom. */
    fun mangaDexRandom(onRandomFound: (String) -> Unit) {
        val mangaDex = source?.getMainSource<MangaDex>() ?: return
        screenModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { mangaDex.fetchRandomMangaUrl() } }
                .onSuccess(onRandomFound)
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it else Toasts.show(it.message ?: it::class.simpleName.orEmpty()) }
        }
    }
    // ZOUKAN <--

    /**
     * Opens the site in the built-in browser, to sign in or pass a Cloudflare check. Cookies the
     * site sets reach the app when the window closes; the listing then loads again with them.
     */
    fun openWebsite() {
        val url = (source as? HttpSource)?.baseUrl?.takeIf { it.isNotBlank() }
            ?: return Toasts.show(localized(DMR.strings.this_source_has_no_website))
        screenModelScope.launch(Dispatchers.IO) {
            val userAgent = Injekt.get<NetworkHelper>().defaultUserAgentProvider()
            val window = BrowserWindow(url, userAgent, message = localized(DMR.strings.sign_in_or_pass_the_sites))
            if (!window.open()) return@launch Toasts.show(localized(DMR.strings.the_built_in_browser_could_not))
            window.await(Long.MAX_VALUE)
            withContext(Dispatchers.Main) { state.value.let { setListing(it.listing, it.savedSearch) } }
        }
    }

    // ZOUKAN -->
    /**
     * The metadata of a page's results, as BrowseSourceScreenModel.combineMetadata and
     * SourcePagingSource: an entry's stored metadata when it has some, else the metadata the page
     * came with. Sources that are not metadata sources have none.
     */
    private suspend fun metadataOf(manga: List<Manga>, page: MangasPage): Map<Long, RaisedSearchMetadata> {
        val metadataSource = source?.getMainSource<MetadataSource<*, *>>() ?: return emptyMap()
        val pageMetadata = (page as? MetadataMangasPage)?.mangasMetadata.orEmpty()
        return manga.mapIndexedNotNull { index, entry ->
            val stored = runCatching { getFlatMetadataById.await(entry.id)?.raise(metadataSource.metaClass) }.getOrNull()
            (stored ?: pageMetadata.getOrNull(index))?.let { entry.id to it }
        }.toMap()
    }
    // ZOUKAN <--

    fun retry() {
        mutableState.update { it.copy(error = null) }
        loadNextPage()
    }

    fun loadNextPage() {
        val source = source ?: return
        val current = state.value
        if (current.loading || !current.hasNextPage || current.error != null) return
        val page = current.page + 1
        mutableState.update { it.copy(loading = true) }
        job = screenModelScope.launch {
            try {
                val result: MangasPage = withContext(Dispatchers.IO) {
                    when (val listing = current.listing) {
                        Listing.Popular -> source.getPopularManga(page)
                        Listing.Latest -> source.getLatestUpdates(page)
                        is Listing.Search -> source.getSearchManga(page, listing.query, filters)
                        // ZOUKAN -->
                        Listing.Follows -> source.getMainSource<MangaDex>()!!.fetchFollows(page)
                        // ZOUKAN <--
                    }
                }
                val manga = withContext(Dispatchers.IO) {
                    networkToLocalManga(result.mangas.map { it.toDomainManga(source.id) })
                }
                // ZOUKAN -->
                val metadata = withContext(Dispatchers.IO) {
                    // The follows show the metadata they came with (their follow status), as MangaDexFollowsScreenModel
                    if (current.listing == Listing.Follows) {
                        (result as? MetadataMangasPage)?.mangasMetadata.orEmpty()
                            .zip(manga) { meta, entry -> entry.id to meta }.toMap()
                    } else {
                        metadataOf(manga, result)
                    }
                }
                // ZOUKAN <--
                mutableState.update { state ->
                    state.copy(
                        items = (state.items + manga).distinctBy { it.id },
                        // ZOUKAN -->
                        metadata = state.metadata + metadata,
                        // ZOUKAN <--
                        page = page,
                        hasNextPage = result.hasNextPage && manga.isNotEmpty(),
                        loading = false,
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                mutableState.update { it.copy(loading = false, error = e.message ?: e::class.simpleName) }
            }
        }
    }
}
