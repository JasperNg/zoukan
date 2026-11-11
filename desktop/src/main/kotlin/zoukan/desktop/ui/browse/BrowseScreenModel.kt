package zoukan.desktop.ui.browse

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.source.CatalogueSource
import exh.source.MERGED_SOURCE_ID
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mihon.domain.extension.interactor.AddExtensionStore
import mihon.domain.extension.interactor.GetExtensionStores
import mihon.domain.extension.interactor.RemoveExtensionStore
import mihon.domain.extension.model.ExtensionStore
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.compat.localized
import zoukan.desktop.local.LocalSource
import zoukan.desktop.ui.common.DesktopPreferences
import zoukan.desktop.ui.common.Toasts
import zoukan.extension.DesktopExtensionManager
import zoukan.extension.LoadResult
import zoukan.i18n.desktop.DMR

class BrowseScreenModel(
    private val extensionManager: DesktopExtensionManager = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val preferences: DesktopPreferences = Injekt.get(),
    private val getStores: GetExtensionStores = Injekt.get(),
    private val addStore: AddExtensionStore = Injekt.get(),
    private val removeStore: RemoveExtensionStore = Injekt.get(),
    // ZOUKAN -->
    private val sourcePreferences: SourcePreferences = Injekt.get(),
    private val uiPreferences: UiPreferences = Injekt.get(),
    private val exhPreferences: exh.source.ExhPreferences = Injekt.get(),
    // ZOUKAN <--
) : StateScreenModel<BrowseScreenModel.State>(State()) {

    data class State(
        val sources: List<CatalogueSource> = emptyList(),
        val sourcesLoaded: Boolean = false,
        val pinned: Set<String> = emptySet(),
        val installed: List<Extension.Installed> = emptyList(),
        val untrusted: List<Extension.Untrusted> = emptyList(),
        val available: List<Extension.Available> = emptyList(),
        val loadErrors: Map<String, String> = emptyMap(),
        val busy: Set<String> = emptySet(),
        val refreshing: Boolean = false,
        val extensionQuery: String = "",
        val languages: Set<String> = emptySet(),
        val showNsfw: Boolean = true,
        val stores: List<ExtensionStore> = emptyList(),
        // ZOUKAN -->
        /** The Sources list as Android's GetEnabledSources groups it: pinned, last used, categories, languages */
        val sections: List<Pair<String, List<CatalogueSource>>> = emptyList(),
        /** Every source, hidden ones too, for the sources filter */
        val allSources: List<CatalogueSource> = emptyList(),
        val disabled: Set<String> = emptySet(),
        val categories: List<String> = emptyList(),
        val sourceCategories: Map<Long, Set<String>> = emptyMap(),
        /** Android's "Replace latest button" is off */
        val showLatest: Boolean = false,
        // ZOUKAN <--
    ) {
        private fun Extension.matches(): Boolean =
            extensionQuery.isBlank() ||
                name.contains(extensionQuery, ignoreCase = true) ||
                pkgName.contains(extensionQuery, ignoreCase = true)

        val visibleInstalled get() = installed.filter { it.matches() }.sortedWith(compareBy({ !it.hasUpdate }, { it.name.lowercase() }))
        val visibleUntrusted get() = untrusted.filter { it.matches() }
        val visibleAvailable: List<Extension.Available>
            get() {
                val installedPkgs = installed.mapTo(HashSet()) { it.pkgName } + untrusted.map { it.pkgName }
                return available
                    .filter { it.pkgName !in installedPkgs && it.matches() }
                    .filter { showNsfw || !it.isNsfw }
                    .filter { languages.isEmpty() || it.lang in languages }
                    .sortedWith(compareBy({ it.lang }, { it.name.lowercase() }))
            }

        /** Every language the store lists, for the language filter */
        val allLanguages get() = available.map { it.lang }.distinct().sorted()
    }

    init {
        // ZOUKAN -->
        screenModelScope.launch {
            combine(
                combine(sourceManager.sources, sourceManager.isInitialized, preferences.pinnedSources().changes()) { a, b, c -> Triple(a, b, c) },
                combine(
                    preferences.enabledLanguages().changes(),
                    sourcePreferences.disabledSources().changes(),
                    preferences.lastUsedSource().changes(),
                ) { a, b, c -> Triple(a, b, c) },
                combine(
                    sourcePreferences.sourcesTabCategories().changes(),
                    sourcePreferences.sourcesTabSourcesInCategories().changes(),
                    sourcePreferences.sourcesTabCategoriesFilter().changes(),
                ) { a, b, c -> Triple(a, b, c) },
                uiPreferences.useNewSourceNavigation().changes(),
            ) { (sources, ready, pinned), (languages, disabled, lastUsed), (categories, inCategories, categoriesFilter), newNavigation ->
                val all = sources.filterIsInstance<CatalogueSource>()
                    // The merged source only serves merged entries; Android hides it too
                    .filterNot { it.id == MERGED_SOURCE_ID }
                    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                val sourceCategories = inCategories.mapNotNull { entry ->
                    val id = entry.substringBefore('|').toLongOrNull() ?: return@mapNotNull null
                    id to entry.substringAfter('|')
                }.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }
                val enabled = all
                    .filter { it.lang in languages || it is LocalSource }
                    .filterNot { it.id.toString() in disabled }
                val sections = buildList {
                    enabled.filter { it.id.toString() in pinned }.takeIf { it.isNotEmpty() }?.let { add(localized(MR.strings.pinned_sources) to it) }
                    enabled.filter { it.id == lastUsed }.takeIf { it.isNotEmpty() }?.let { add(localized(MR.strings.last_used_source) to it) }
                    categories.sortedWith(String.CASE_INSENSITIVE_ORDER).forEach { category ->
                        enabled.filter { category in sourceCategories[it.id].orEmpty() }.takeIf { it.isNotEmpty() }?.let { add(category to it) }
                    }
                    // As Android: a pinned source shows only under Pinned, and with "Filter sources in
                    // categories" a source in a category is not listed again under its language
                    enabled
                        .filter { it.id.toString() !in pinned }
                        .filterNot { categoriesFilter && it.id in sourceCategories }
                        .groupBy { it.lang }
                        .toSortedMap()
                        .forEach { (lang, list) -> add(languageName(lang) to list) }
                }
                State(
                    sources = enabled,
                    sourcesLoaded = ready,
                    pinned = pinned,
                    sections = sections,
                    allSources = all,
                    disabled = disabled,
                    categories = categories.sortedWith(String.CASE_INSENSITIVE_ORDER),
                    sourceCategories = sourceCategories,
                    showLatest = !newNavigation,
                )
            }.collect { new ->
                mutableState.update { state ->
                    state.copy(
                        sources = new.sources,
                        sourcesLoaded = new.sourcesLoaded,
                        pinned = new.pinned,
                        sections = new.sections,
                        allSources = new.allSources,
                        disabled = new.disabled,
                        categories = new.categories,
                        sourceCategories = new.sourceCategories,
                        showLatest = new.showLatest,
                    )
                }
            }
        }
        // ZOUKAN <--
        screenModelScope.launch {
            combine(
                extensionManager.installedExtensionsFlow,
                extensionManager.untrustedExtensionsFlow,
                extensionManager.availableExtensionsFlow,
                extensionManager.loadErrorsFlow,
            ) { installed, untrusted, available, errors ->
                // ZOUKAN -->
                // SY's source blacklist: the built-in E-Hentai replaces the E-Hentai extension
                val hidden = if (sourcePreferences.enableSourceBlacklist().get() && exhPreferences.isHentaiEnabled().get()) {
                    BLACKLISTED_EXTENSIONS
                } else {
                    emptySet()
                }
                // ZOUKAN <--
                State(
                    installed = installed.values.filterNot { it.pkgName in hidden },
                    untrusted = untrusted.values.filterNot { it.pkgName in hidden },
                    available = available.values.filterNot { it.pkgName in hidden },
                    loadErrors = errors.mapValues { (_, e) -> e.message ?: e::class.simpleName.orEmpty() },
                )
            }.collect { new ->
                mutableState.update {
                    it.copy(installed = new.installed, untrusted = new.untrusted, available = new.available, loadErrors = new.loadErrors)
                }
            }
        }
        screenModelScope.launch {
            combine(preferences.enabledLanguages().changes(), preferences.showNsfwSource().changes()) { langs, nsfw -> langs to nsfw }
                .collect { (langs, nsfw) -> mutableState.update { it.copy(languages = langs, showNsfw = nsfw) } }
        }
        screenModelScope.launch {
            getStores.subscribe().collect { stores -> mutableState.update { it.copy(stores = stores) } }
        }
    }

    fun togglePin(source: CatalogueSource) {
        val key = source.id.toString()
        val pinned = preferences.pinnedSources().get()
        preferences.pinnedSources().set(if (key in pinned) pinned - key else pinned + key)
    }

    // ZOUKAN -->
    /** Android's "Disable" in a source's long-press dialog, and the sources filter's checkbox */
    fun toggleHidden(source: CatalogueSource) {
        val key = source.id.toString()
        val disabled = sourcePreferences.disabledSources().get()
        sourcePreferences.disabledSources().set(if (key in disabled) disabled - key else disabled + key)
    }

    /** Android's "Set categories" for a source, stored as "sourceId|category" */
    fun setCategories(source: CatalogueSource, categories: Set<String>) {
        val others = sourcePreferences.sourcesTabSourcesInCategories().get().filterNot { it.substringBefore('|') == source.id.toString() }
        sourcePreferences.sourcesTabSourcesInCategories().set(others.toSet() + categories.map { "${source.id}|$it" })
    }
    // ZOUKAN <--

    fun searchExtensions(query: String) = mutableState.update { it.copy(extensionQuery = query) }

    fun toggleLanguage(lang: String) {
        val langs = preferences.enabledLanguages().get()
        preferences.enabledLanguages().set(if (lang in langs) langs - lang else langs + lang)
    }

    fun refreshAvailable() {
        if (state.value.refreshing) return
        mutableState.update { it.copy(refreshing = true) }
        screenModelScope.launch {
            try {
                val results = extensionManager.findAvailableExtensions()
                results.forEach { (store, result) ->
                    result.onFailure { Toasts.error(localized(DMR.strings.could_not_load, store.name), it) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Toasts.error(localized(DMR.strings.could_not_load_the_extension_lists), e)
            } finally {
                mutableState.update { it.copy(refreshing = false) }
            }
        }
    }

    private fun busy(pkg: String, block: suspend () -> Unit) {
        if (pkg in state.value.busy) return
        mutableState.update { it.copy(busy = it.busy + pkg) }
        screenModelScope.launch {
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Toasts.error(localized(DMR.strings.failed), e)
            } finally {
                mutableState.update { it.copy(busy = it.busy - pkg) }
            }
        }
    }

    fun install(extension: Extension.Available) = busy(extension.pkgName) {
        report(extension.name, extensionManager.installExtension(extension))
    }

    fun update(extension: Extension.Installed) = busy(extension.pkgName) {
        extensionManager.updateExtension(extension)?.let { report(extension.name, it) }
    }

    fun uninstall(extension: Extension) = busy(extension.pkgName) {
        extensionManager.uninstallExtension(extension.pkgName)
        Toasts.show(localized(DMR.strings.uninstalled, extension.name))
    }

    fun trust(extension: Extension.Untrusted) = busy(extension.pkgName) {
        extensionManager.trust(extension)?.let { report(extension.name, it) }
    }

    private fun report(name: String, result: LoadResult) {
        when (result) {
            is LoadResult.Success -> Toasts.show(localized(DMR.strings.installed, name))
            is LoadResult.Untrusted -> Toasts.show(localized(DMR.strings.is_signed_with_an_unknown_key, name))
            is LoadResult.Error -> Toasts.show(localized(DMR.strings.failed_to_load, name, result.error.message))
        }
    }

    fun addStore(url: String) {
        screenModelScope.launch {
            addStore.invoke(url.trim())
                .onSuccess {
                    Toasts.show(localized(DMR.strings.store_added))
                    refreshAvailable()
                }
                .onFailure { Toasts.error(localized(DMR.strings.could_not_add_the_store), it) }
        }
    }

    fun removeStore(store: ExtensionStore) {
        screenModelScope.launch {
            removeStore.invoke(store.indexUrl)
            Toasts.show(localized(DMR.strings.removed, store.name))
        }
    }

    companion object {
        const val KEIYOUSHI = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/repo.json"

        // ZOUKAN -->
        /** BlacklistedSources.BLACKLISTED_EXTENSIONS on Android */
        val BLACKLISTED_EXTENSIONS = setOf("eu.kanade.tachiyomi.extension.all.ehentai")
        // ZOUKAN <--
    }
}
