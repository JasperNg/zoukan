package zoukan.desktop.ui.migration

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.online.HttpSource
import exh.source.MERGED_SOURCE_ID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import mihon.domain.migration.models.MigrationFlag
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.compat.localized
import zoukan.desktop.migration.MigrateManga
import zoukan.desktop.ui.browse.languageName
import zoukan.desktop.ui.common.DropdownMenu
import zoukan.desktop.ui.common.EmptyScreen
import zoukan.desktop.ui.common.LazyColumn
import zoukan.desktop.ui.common.LoadingScreen
import zoukan.desktop.ui.common.ScreenHeader
import zoukan.desktop.ui.common.SearchField
import zoukan.desktop.ui.common.TooltipIconButton
import zoukan.desktop.ui.settings.SwitchPreference
import zoukan.desktop.ui.settings.SwitchSetting
import zoukan.desktop.ui.settings.settingsGroup
import zoukan.i18n.desktop.DMR

/**
 * The Android app's MigrationConfigScreen and its options sheet: which sources to search, in
 * priority order, and what to migrate. Start opens the [MigrationListScreen].
 */
data class MigrationConfigScreen(val mangaIds: List<Long>) : Screen {
    override val key: ScreenKey = "migration-config/${mangaIds.joinToString(",")}/"

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val model = rememberScreenModel { MigrationConfigScreenModel() }
        val state by model.state.collectAsState()
        var query by rememberSaveable { mutableStateOf("") }
        var selectMenu by remember { mutableStateOf(false) }
        var extraQuery by rememberSaveable { mutableStateOf("") }

        Column(Modifier.fillMaxSize()) {
            ScreenHeader(
                localized(SYMR.strings.select_sources),
                subtitle = localized(
                    DMR.strings.migration_config_subtitle,
                    if (mangaIds.size == 1) localized(DMR.strings.n_1_entry) else localized(DMR.strings.entries, mangaIds.size),
                    state.sources.count { it.selected }.let { if (it == 1) localized(DMR.strings.n_1_source) else localized(DMR.strings.sources, it) },
                ),
            ) {
                SearchField(query, { query = it }, localized(DMR.strings.search_for_a_source), Modifier.width(260.dp).padding(end = 8.dp))
                Box {
                    TooltipIconButton(Icons.Outlined.MoreVert, localized(DMR.strings.select), onClick = { selectMenu = true })
                    DropdownMenu(selectMenu, onDismissRequest = { selectMenu = false }) {
                        MigrationConfigScreenModel.Selection.entries.forEach { selection ->
                            DropdownMenuItem(
                                text = { Text(selection.label) },
                                onClick = {
                                    selectMenu = false
                                    model.select(selection)
                                },
                            )
                        }
                    }
                }
                Button(
                    enabled = state.sources.any { it.selected },
                    onClick = {
                        val runManually = mangaIds.size == 1 && !model.preferences.migrationSmartSearchSingleEntry().get()
                        navigator.replace(MigrationListScreen(mangaIds, extraQuery.trim().ifBlank { null }, runManually))
                    },
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) { Text(localized(MR.strings.action_start)) }
            }
            if (state.loading) {
                LoadingScreen()
                return@Column
            }
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    SourceList(state.sources, query, model)
                }
                VerticalDivider()
                Options(model.preferences, extraQuery) { extraQuery = it }
            }
        }
    }

    @Composable
    private fun SourceList(sources: List<MigrationConfigScreenModel.MigrationSource>, query: String, model: MigrationConfigScreenModel) {
        if (sources.isEmpty()) {
            EmptyScreen(localized(DMR.strings.no_sources_to_search_install_an))
            return
        }
        // As Android, a comma separates several names or ids
        val terms = query.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val shown = sources.filter { s -> terms.isEmpty() || terms.any { s.name.contains(it, ignoreCase = true) || s.id == it.toLongOrNull() } }
        val (selected, available) = shown.partition { it.selected }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (selected.isNotEmpty()) {
                settingsGroup(localized(MR.strings.selected))
                itemsIndexed(selected, key = { _, s -> "selected-${s.id}" }) { index, source ->
                    SourceRow(source, model) {
                        TooltipIconButton(Icons.Outlined.ArrowUpward, localized(KMR.strings.action_move_up), enabled = index > 0 && terms.isEmpty(), onClick = { model.move(source.id, -1) })
                        TooltipIconButton(
                            Icons.Outlined.ArrowDownward,
                            localized(KMR.strings.action_move_down),
                            enabled = index < selected.lastIndex && terms.isEmpty(),
                            onClick = { model.move(source.id, 1) },
                        )
                    }
                }
            }
            if (available.isNotEmpty()) {
                settingsGroup(localized(MR.strings.migrationConfigScreen_availableHeader))
                itemsIndexed(available, key = { _, s -> "available-${s.id}" }) { _, source -> SourceRow(source, model) {} }
            }
        }
    }

    @Composable
    private fun SourceRow(
        source: MigrationConfigScreenModel.MigrationSource,
        model: MigrationConfigScreenModel,
        trailing: @Composable () -> Unit,
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { model.toggle(source.id) }.padding(horizontal = 16.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(source.selected, onCheckedChange = { model.toggle(source.id) })
            Column(Modifier.weight(1f)) {
                Text(source.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    languageName(source.lang),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            trailing()
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun Options(preferences: SourcePreferences, extraQuery: String, onExtraQuery: (String) -> Unit) {
        val migrate = remember { Injekt.get<MigrateManga>() }
        val flagsPreference = remember { migrate.flags() }
        val flags by flagsPreference.changes().collectAsState(flagsPreference.get())
        LazyColumn(Modifier.width(420.dp).fillMaxHeight(), contentPadding = PaddingValues(bottom = 16.dp)) {
            settingsGroup(localized(MR.strings.migrationConfigScreen_dataToMigrateHeader))
            item(key = "flags") {
                FlowRow(
                    Modifier.padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MigrationFlag.entries.filter { it != MigrationFlag.REMOVE_DOWNLOAD }.forEach { flag ->
                        FilterChip(
                            selected = flag in flags,
                            onClick = { flagsPreference.set(if (flag in flags) flags - flag else flags + flag) },
                            label = { Text(MigrateManga.label(flag)) },
                        )
                    }
                }
            }
            item(key = "remove-download") {
                SwitchSetting(
                    localized(MR.strings.migrationConfigScreen_removeDownloadsTitle),
                    checked = MigrationFlag.REMOVE_DOWNLOAD in flags,
                ) {
                    flagsPreference.set(if (it) flags + MigrationFlag.REMOVE_DOWNLOAD else flags - MigrationFlag.REMOVE_DOWNLOAD)
                }
            }
            item(key = "extra-query") {
                OutlinedTextField(
                    extraQuery,
                    onExtraQuery,
                    singleLine = true,
                    label = { Text(localized(MR.strings.migrationConfigScreen_additionalSearchQueryLabel)) },
                    supportingText = { Text(localized(MR.strings.migrationConfigScreen_additionalSearchQuerySupportingText)) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            item(key = "hide-unmatched") { SwitchPreference(localized(MR.strings.migrationConfigScreen_hideUnmatchedTitle), preferences.migrationHideUnmatched()) }
            item(key = "hide-without-updates") {
                SwitchPreference(
                    localized(MR.strings.migrationConfigScreen_hideWithoutUpdatesTitle),
                    preferences.migrationHideWithoutUpdates(),
                    subtitle = localized(MR.strings.migrationConfigScreen_hideWithoutUpdatesSubtitle),
                )
            }
            item(key = "smart-search") {
                SwitchPreference(
                    localized(KMR.strings.migrationConfigScreen_smartSearchSingleEntryTitle),
                    preferences.migrationSmartSearchSingleEntry(),
                    subtitle = localized(DMR.strings.for_a_single_entry_if_enabled),
                )
            }
            item(key = "warning") {
                Text(
                    localized(MR.strings.migrationConfigScreen_enhancedOptionsWarning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            item(key = "deep-search") {
                SwitchPreference(
                    localized(MR.strings.migrationConfigScreen_deepSearchModeTitle),
                    preferences.migrationDeepSearchMode(),
                    subtitle = localized(MR.strings.migrationConfigScreen_deepSearchModeSubtitle),
                )
            }
            item(key = "prioritize") {
                SwitchPreference(
                    localized(MR.strings.migrationConfigScreen_prioritizeByChaptersTitle),
                    preferences.migrationPrioritizeByChapters(),
                    subtitle = localized(MR.strings.migrationConfigScreen_prioritizeByChaptersSubtitle),
                )
            }
        }
    }
}

/** The sources to search, kept in the Android app's "migration_sources" preference in priority order. */
class MigrationConfigScreenModel(
    val preferences: SourcePreferences = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
) : StateScreenModel<MigrationConfigScreenModel.State>(State()) {

    data class MigrationSource(val id: Long, val name: String, val lang: String, val selected: Boolean)

    data class State(val loading: Boolean = true, val sources: List<MigrationSource> = emptyList())

    enum class Selection(val label: String) {
        Pinned(localized(MR.strings.migrationConfigScreen_selectPinnedLabel)),
        None(localized(MR.strings.migrationConfigScreen_selectNoneLabel)),
        Enabled(localized(MR.strings.migrationConfigScreen_selectEnabledLabel)),
        All(localized(MR.strings.action_select_all)),
    }

    private val pinned by lazy { preferences.pinnedSources().get().mapNotNull { it.toLongOrNull() }.toSet() }
    private val disabled by lazy { preferences.disabledSources().get().mapNotNull { it.toLongOrNull() }.toSet() }

    init {
        screenModelScope.launch(Dispatchers.IO) {
            withTimeoutOrNull(15_000) { sourceManager.isInitialized.first { it } }
            val saved = preferences.migrationSources().get()
            val sources = sourceManager.getAll()
                .filterIsInstance<HttpSource>()
                .filter { it.id != MERGED_SOURCE_ID }
                .map { source ->
                    MigrationSource(
                        id = source.id,
                        name = source.name,
                        lang = source.lang,
                        selected = when {
                            saved.isNotEmpty() -> source.id in saved
                            pinned.isNotEmpty() -> source.id in pinned
                            else -> source.id !in disabled
                        },
                    )
                }
            mutableState.update { it.copy(loading = false, sources = sorted(sources, saved)) }
            save()
        }
    }

    /** Selected sources first, in priority order, then the rest by name and language, as Android. */
    private fun sorted(sources: List<MigrationSource>, order: List<Long>): List<MigrationSource> {
        val rank = order.withIndex().associate { (index, id) -> id to index }
        return sources.sortedWith(
            compareBy<MigrationSource>(
                { if (it.selected) rank[it.id] ?: (Int.MAX_VALUE - 1) else Int.MAX_VALUE },
                { "${it.name} (${it.lang})".lowercase() },
            ),
        )
    }

    private fun update(change: (List<MigrationSource>) -> List<MigrationSource>) {
        mutableState.update { state ->
            val changed = change(state.sources)
            state.copy(sources = sorted(changed, changed.filter { it.selected }.map { it.id }))
        }
        save()
    }

    private fun save() {
        preferences.migrationSources().set(state.value.sources.filter { it.selected }.map { it.id })
    }

    fun toggle(id: Long) = update { sources -> sources.map { if (it.id == id) it.copy(selected = !it.selected) else it } }

    fun select(selection: Selection) = update { sources ->
        sources.map {
            it.copy(
                selected = when (selection) {
                    Selection.All -> true
                    Selection.None -> false
                    Selection.Pinned -> it.id in pinned
                    Selection.Enabled -> it.id !in disabled
                },
            )
        }
    }

    /** Moves a selected source [by] places in the priority order. */
    fun move(id: Long, by: Int) = update { sources ->
        val selected = sources.filter { it.selected }.toMutableList()
        val from = selected.indexOfFirst { it.id == id }
        val to = from + by
        if (from < 0 || to !in selected.indices) return@update sources
        selected.add(to, selected.removeAt(from))
        selected + sources.filterNot { it.selected }
    }
}
