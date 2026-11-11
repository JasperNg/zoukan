package zoukan.desktop.ui.browse

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.source.interactor.GetExhSavedSearch
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import mihon.domain.manga.model.toDomainManga
import tachiyomi.domain.manga.interactor.GetFavorites
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
    private val getExhSavedSearch: GetExhSavedSearch = Injekt.get(),
    private val insertSavedSearch: InsertSavedSearch = Injekt.get(),
    private val deleteSavedSearchById: DeleteSavedSearchById = Injekt.get(),
    private val filterSerializer: FilterSerializer = Injekt.get(),
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
        // ZOUKAN <--
    )

    var filters: FilterList = source?.getFilterList() ?: FilterList()
        private set

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
                getExhSavedSearch.subscribe(source.id, source::getFilterList).collect { searches ->
                    mutableState.update { it.copy(savedSearches = searches.sortedBy { s -> s.name.lowercase() }) }
                }
            }
        }
        if (source != null && savedSearchId != null) {
            screenModelScope.launch {
                val saved = getExhSavedSearch.awaitOne(savedSearchId, source::getFilterList)
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
            it.copy(listing = listing, items = emptyList(), page = 0, hasNextPage = true, error = null, loading = false, savedSearch = savedSearch)
        }
        loadNextPage()
    }

    fun search(query: String) = setListing(Listing.Search(query.trim()))

    fun filtersChanged() = mutableState.update { it.copy(filterVersion = it.filterVersion + 1) }

    fun resetFilters() {
        filters = source?.getFilterList() ?: FilterList()
        filtersChanged()
    }

    fun applyFilters() {
        val query = (state.value.listing as? Listing.Search)?.query.orEmpty()
        setListing(Listing.Search(query))
    }

    // ZOUKAN -->
    /** Shows a saved search: its query with its filters, as the Android app does. */
    fun applySavedSearch(search: EXHSavedSearch) {
        filters = search.filterList ?: source?.getFilterList() ?: FilterList()
        filtersChanged()
        setListing(Listing.Search(search.query.orEmpty()), savedSearch = search.id)
    }

    /** Saves the current query and filters under [name]. */
    fun saveSearch(name: String) {
        val source = source ?: return
        val query = (state.value.listing as? Listing.Search)?.query?.takeIf { it.isNotBlank() }
        val filterList = filters.ifEmpty { source.getFilterList() }
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
                    }
                }
                val manga = withContext(Dispatchers.IO) {
                    networkToLocalManga(result.mangas.map { it.toDomainManga(source.id) })
                }
                mutableState.update { state ->
                    state.copy(
                        items = (state.items + manga).distinctBy { it.id },
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
