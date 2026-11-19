package zoukan.desktop.ui.settings

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import eu.kanade.tachiyomi.data.track.TrackerManager
import exh.md.utils.MdUtil
import kotlinx.coroutines.flow.update
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Per-screen state holder for [SettingsTab], keeping settings navigation, search query, search
 * results and jump-to-page highlight scoped to one screen instance rather than process globals.
 */
class SettingsScreenModel(
    initialCategory: SettingsCategory = SettingsCategory.Appearance,
    isMangaDexInstalled: () -> Boolean = {
        runCatching { MdUtil.getEnabledMangaDexs(Injekt.get()).isNotEmpty() }.getOrDefault(false)
    },
    trackersProvider: () -> List<Pair<Long, String>> = {
        runCatching { Injekt.get<TrackerManager>().trackers.map { it.id to it.name } }.getOrDefault(emptyList())
    },
    entriesProvider: ((includeMangaDex: Boolean, trackers: List<Pair<Long, String>>) -> List<SettingsSearchEntry>)? = null,
) : StateScreenModel<SettingsScreenModel.State>(State()) {

    @Immutable
    data class State(
        val selectedCategory: SettingsCategory = SettingsCategory.Appearance,
        val searchQuery: String = "",
        val highlightedItem: Pair<SettingsCategory, Any>? = null,
        val pages: List<SettingsCategory> = SettingsCategory.entries,
        val searchResults: List<SettingsSearchEntry> = emptyList(),
    )

    private val mangaDexInstalled: Boolean = isMangaDexInstalled()
    private val visiblePages: List<SettingsCategory> =
        SettingsCategory.entries.filter { it != SettingsCategory.MangaDex || mangaDexInstalled }

    private val index: List<SettingsSearchEntry> by lazy {
        val trackers = trackersProvider()
        entriesProvider?.invoke(mangaDexInstalled, trackers)
            ?: SettingsSearch.index(includeMangaDex = mangaDexInstalled, trackers = trackers)
    }

    init {
        val startCategory = consumePendingCategory() ?: initialCategory
        val normalized = startCategory.takeIf { it in visiblePages } ?: SettingsCategory.Appearance
        mutableState.value = State(selectedCategory = normalized, pages = visiblePages)
    }

    fun selectCategory(category: SettingsCategory) {
        val target = category.takeIf { it in visiblePages } ?: SettingsCategory.Appearance
        mutableState.update {
            it.copy(
                selectedCategory = target,
                searchQuery = "",
                searchResults = emptyList(),
            )
        }
    }

    fun setSearchQuery(query: String) {
        val results = if (query.isBlank()) {
            emptyList()
        } else {
            SettingsSearch.search(query, index, enabledCategories = visiblePages.toSet())
        }
        mutableState.update {
            it.copy(
                searchQuery = query,
                searchResults = results,
            )
        }
    }

    fun clearSearch() {
        setSearchQuery("")
    }

    fun openSearchResult(entry: SettingsSearchEntry) {
        val target = entry.category.takeIf { it in visiblePages } ?: SettingsCategory.Appearance
        mutableState.update {
            it.copy(
                selectedCategory = target,
                searchQuery = "",
                searchResults = emptyList(),
                highlightedItem = entry.itemKey?.let { key -> target to key },
            )
        }
    }

    fun clearHighlight() {
        mutableState.update { it.copy(highlightedItem = null) }
    }

    override fun onDispose() {
        unregister(this)
        super.onDispose()
    }

    internal companion object {
        private val activeModels = mutableListOf<SettingsScreenModel>()
        private var pendingCategory: SettingsCategory? = null

        @Synchronized
        internal fun register(model: SettingsScreenModel) {
            if (model !in activeModels) activeModels += model
            pendingCategory?.let { pending ->
                pendingCategory = null
                model.selectCategory(pending)
            }
        }

        @Synchronized
        internal fun unregister(model: SettingsScreenModel) {
            activeModels -= model
            if (activeModels.isEmpty()) pendingCategory = null
        }

        @Synchronized
        internal fun consumePendingCategory(): SettingsCategory? {
            val pending = pendingCategory
            pendingCategory = null
            return pending
        }

        @Synchronized
        internal fun openCategory(category: SettingsCategory) {
            val active = activeModels.lastOrNull()
            if (active != null) {
                active.selectCategory(category)
            } else {
                pendingCategory = category
            }
        }
    }
}
