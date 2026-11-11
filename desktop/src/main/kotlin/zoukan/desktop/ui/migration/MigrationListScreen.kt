package zoukan.desktop.ui.migration

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.browse.GlobalSearchScreen
import zoukan.desktop.ui.common.Corners
import zoukan.desktop.ui.common.Cover
import zoukan.desktop.ui.common.CoverImage
import zoukan.desktop.ui.common.LazyColumn
import zoukan.desktop.ui.common.LoadingScreen
import zoukan.desktop.ui.common.Toasts
import zoukan.desktop.ui.common.TooltipIconButton
import zoukan.desktop.ui.manga.MangaScreen
import zoukan.desktop.ui.manga.SearchPurpose
import zoukan.desktop.ui.migration.MigrationListScreenModel.Item
import zoukan.desktop.ui.migration.MigrationListScreenModel.SearchResult
import zoukan.i18n.desktop.DMR

/**
 * The Android app's MigrationListScreen: each entry beside the match found for it, with a manual
 * search, "Don't migrate" and migrating one entry now; then Migrate or Copy for all of them.
 */
data class MigrationListScreen(
    val mangaIds: List<Long>,
    val extraSearchQuery: String?,
    /** A single entry without smart search: no automatic search, the manual search opens at once */
    val runManually: Boolean = false,
) : Screen {
    override val key: ScreenKey = "migration-list/${mangaIds.joinToString(",")}/$extraSearchQuery/$runManually/"

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val model = rememberScreenModel { MigrationListScreenModel(mangaIds, extraSearchQuery, runManually) }
        val state by model.state.collectAsState()
        val searchManually = { item: Item ->
            navigator.push(GlobalSearchScreen(item.manga.ogTitle, SearchPurpose.MigrateInList(item.manga.id, item.manga.source)))
        }

        LaunchedEffect(state.finished) {
            if (state.finished) navigator.pop()
        }
        var openedManualSearch by remember { mutableStateOf(false) }
        LaunchedEffect(state.loading) {
            if (runManually && !state.loading && !openedManualSearch) {
                openedManualSearch = true
                state.items.singleOrNull()?.let(searchManually)
            }
        }

        Column(Modifier.fillMaxSize()) {
            Header(
                title = if (state.loading) localized(MR.strings.migrationListScreenTitle) else localized(DMR.strings.migration, state.finishedCount, state.items.size),
                complete = state.migrationComplete,
                onBack = { if (state.items.isEmpty()) navigator.pop() else model.showExitDialog() },
                onCopy = { model.showMigrateDialog(copy = true) },
                onMigrate = { model.showMigrateDialog(copy = false) },
            )
            if (state.loading) {
                LoadingScreen()
                return@Column
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(state.items, key = { it.manga.id }) { item ->
                    MigrationRow(
                        item,
                        onOpen = { navigator.push(MangaScreen(it.id)) },
                        onSearchManually = { searchManually(item) },
                        onSkip = { model.removeManga(item.manga.id) },
                        onCancel = { model.cancelSearch(item.manga.id) },
                        onMigrateNow = { replace ->
                            model.migrateNow(item.manga.id, replace) { Toasts.error(localized(DMR.strings.migration_failed), it) }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }

        when (val dialog = state.dialog) {
            is MigrationListScreenModel.Dialog.Migrate -> AlertDialog(
                onDismissRequest = model::dismissDialog,
                title = { Text(if (dialog.copy) localized(DMR.strings.copy_entries) else localized(DMR.strings.migrate_entries)) },
                text = {
                    val count = dialog.totalCount - dialog.skippedCount
                    Text(
                        buildString {
                            append(localized(if (dialog.copy) DMR.strings.copy_count_of_entries else DMR.strings.migrate_count_of_entries, count, entries(dialog.totalCount)))
                            if (dialog.skippedCount == 1) append("\n\n").append(localized(DMR.strings.one_skipped_without_match))
                            if (dialog.skippedCount > 1) append("\n\n").append(localized(DMR.strings.skipped_without_match, dialog.skippedCount))
                        },
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        model.migrateAll(replace = !dialog.copy) { migrated, failed ->
                            Toasts.show(
                                when {
                                    failed > 0 -> localized(if (dialog.copy) DMR.strings.copied_entries_failed else DMR.strings.migrated_entries_failed, entries(migrated), failed)
                                    else -> localized(if (dialog.copy) DMR.strings.copied_entries else DMR.strings.migrated_entries, entries(migrated))
                                },
                            )
                        }
                    }) { Text(if (dialog.copy) localized(MR.strings.copy) else localized(MR.strings.action_migrate)) }
                },
                dismissButton = { TextButton(onClick = model::dismissDialog) { Text(localized(MR.strings.action_cancel)) } },
            )
            is MigrationListScreenModel.Dialog.Progress -> AlertDialog(
                onDismissRequest = {},
                title = { Text(localized(DMR.strings.migrating_of, dialog.done, dialog.total)) },
                text = {
                    LinearProgressIndicator(
                        progress = { if (dialog.total == 0) 0f else dialog.done.toFloat() / dialog.total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = { TextButton(onClick = model::cancelMigrate) { Text(localized(MR.strings.action_cancel)) } },
            )
            MigrationListScreenModel.Dialog.Exit -> AlertDialog(
                onDismissRequest = model::dismissDialog,
                title = { Text(localized(MR.strings.migrationListScreen_exitDialogTitle)) },
                confirmButton = {
                    TextButton(onClick = {
                        model.dismissDialog()
                        navigator.pop()
                    }) { Text(localized(MR.strings.migrationListScreen_exitDialog_stopLabel)) }
                },
                dismissButton = { TextButton(onClick = model::dismissDialog) { Text(localized(MR.strings.action_cancel)) } },
            )
            null -> Unit
        }
    }

    @Composable
    private fun Header(title: String, complete: Boolean, onBack: () -> Unit, onCopy: () -> Unit, onMigrate: () -> Unit) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(8.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                TooltipIconButton(Icons.AutoMirrored.Outlined.ArrowBack, localized(MR.strings.action_webview_back), onClick = onBack)
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = 8.dp))
                TooltipIconButton(Icons.Outlined.ContentCopy, localized(MR.strings.copy), onClick = onCopy, enabled = complete)
                TooltipIconButton(Icons.Outlined.SwapHoriz, localized(MR.strings.action_migrate), onClick = onMigrate, enabled = complete)
            }
        }
    }

    @Composable
    private fun MigrationRow(
        item: Item,
        onOpen: (Manga) -> Unit,
        onSearchManually: () -> Unit,
        onSkip: () -> Unit,
        onCancel: () -> Unit,
        onMigrateNow: (replace: Boolean) -> Unit,
    ) {
        var menu by remember { mutableStateOf(false) }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("migration-item"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EntryCard(
                item.manga,
                item.source,
                item.chapters,
                Modifier.weight(1f),
                onClick = { onOpen(item.manga) },
            )
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.padding(horizontal = 12.dp))
            Box(Modifier.weight(1f)) {
                when (val result = item.result) {
                    SearchResult.Searching -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(localized(DMR.strings.searching), modifier = Modifier.weight(1f))
                        TextButton(onClick = onCancel) { Text(localized(DMR.strings.stop_searching)) }
                    }
                    SearchResult.NotFound -> Text(
                        localized(MR.strings.migrationListScreen_noMatchFoundText),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable(onClick = onSearchManually).padding(vertical = 24.dp),
                    )
                    is SearchResult.Success -> EntryCard(
                        result.manga,
                        result.source,
                        result.chapters,
                        Modifier.fillMaxWidth(),
                        onClick = { onOpen(result.manga) },
                    )
                }
            }
            Box {
                TooltipIconButton(Icons.Outlined.MoreVert, localized(MR.strings.pref_reader_actions), onClick = { menu = true })
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(localized(MR.strings.migrationListScreen_searchManuallyActionLabel)) }, onClick = {
                        menu = false
                        onSearchManually()
                    })
                    DropdownMenuItem(text = { Text(localized(MR.strings.migrationListScreen_skipActionLabel)) }, onClick = {
                        menu = false
                        onSkip()
                    })
                    if (item.result is SearchResult.Success) {
                        DropdownMenuItem(text = { Text(localized(MR.strings.migrationListScreen_migrateNowActionLabel)) }, onClick = {
                            menu = false
                            onMigrateNow(true)
                        })
                        DropdownMenuItem(text = { Text(localized(MR.strings.migrationListScreen_copyNowActionLabel)) }, onClick = {
                            menu = false
                            onMigrateNow(false)
                        })
                    }
                }
            }
        }
    }

    @Composable
    private fun EntryCard(
        manga: Manga,
        source: String,
        chapters: MigrationListScreenModel.ChapterInfo,
        modifier: Modifier,
        onClick: () -> Unit,
    ) {
        Row(modifier.clip(Corners.Medium).clickable(onClick = onClick).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverImage(
                Cover.of(manga),
                Modifier.height(96.dp).aspectRatio(2f / 3f).clip(Corners.Medium),
            )
            Column(Modifier.padding(start = 12.dp)) {
                Text(manga.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(source, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                val latest = chapters.latestChapter?.takeIf { it >= 0 }?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }
                Text(
                    localized(DMR.strings.chapters_latest, chapters.chapterCount, latest ?: localized(MR.strings.unknown)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun entries(count: Int) = if (count == 1) localized(DMR.strings.n_1_entry) else localized(DMR.strings.entries, count)
