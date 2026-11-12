package zoukan.desktop.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.history.interactor.GetTotalReadDuration
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetReadMangaNotInLibraryView
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.compat.localized
import zoukan.desktop.local.LocalSource
import zoukan.desktop.storage.ZoukanStorage
import zoukan.desktop.ui.common.Corners
import zoukan.desktop.ui.common.DropdownMenu
import zoukan.desktop.ui.common.LoadingScreen
import zoukan.desktop.ui.common.ScreenHeader
import zoukan.desktop.ui.common.TooltipIconButton
import zoukan.desktop.ui.common.verticalScrollWithBar
import zoukan.i18n.desktop.DMR
import java.io.File
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

/** The numbers of the Android app's Statistics screen. */
data class Stats(
    val libraryCount: Int,
    val completedCount: Int,
    val readDurationMs: Long,
    val globalUpdateCount: Int,
    val startedCount: Int,
    val localCount: Int,
    val totalChapters: Int,
    val readChapters: Int,
    val downloadedChapters: Int,
    val trackedCount: Int,
    val meanScore: Double,
    val trackerCount: Int,
)

class StatsScreenModel(
    private val getLibraryManga: GetLibraryManga = Injekt.get(),
    private val getTotalReadDuration: GetTotalReadDuration = Injekt.get(),
    private val getTracks: GetTracks = Injekt.get(),
    private val trackerManager: TrackerManager = Injekt.get(),
    private val preferences: LibraryPreferences = Injekt.get(),
    private val storage: ZoukanStorage = Injekt.get(),
    // SY -->
    private val getReadMangaNotInLibraryView: GetReadMangaNotInLibraryView = Injekt.get(),
    // SY <--
) : StateScreenModel<Stats?>(null) {

    // SY: "Include all read entries" adds the read entries that are not in the library
    private val _allRead = MutableStateFlow(false)
    val allRead = _allRead.asStateFlow()

    init {
        screenModelScope.launch {
            _allRead.collectLatest { allRead ->
                mutableState.value = null
                mutableState.value = withContext(Dispatchers.IO) { compute(allRead) }
            }
        }
    }

    fun toggleReadManga() {
        _allRead.value = !_allRead.value
    }

    private suspend fun compute(allRead: Boolean): Stats {
        val entries = getLibraryManga.await() + if (allRead) getReadMangaNotInLibraryView.await() else emptyList()
        val library = entries.distinctBy { it.id }
        val loggedIn = trackerManager.loggedInTrackers()
        val loggedInIds = loggedIn.mapTo(HashSet()) { it.id }
        val tracks = getTracks.await(library.map { it.id })
            .mapValues { (_, list) -> list.filter { it.trackerId in loggedInIds } }
        val scores = tracks.values.mapNotNull { list ->
            list.filter { it.score > 0.0 }
                .map { trackerManager.get(it.trackerId)!!.get10PointScore(it) }
                .takeIf { it.isNotEmpty() }?.average()
        }.filterNot { it.isNaN() }
        return Stats(
            libraryCount = library.size,
            completedCount = library.count { it.manga.status.toInt() == SManga.COMPLETED && it.unreadCount == 0L },
            readDurationMs = getTotalReadDuration.await(),
            globalUpdateCount = globalUpdateCount(entries),
            startedCount = library.count { it.hasStarted },
            localCount = library.count { it.manga.source == LocalSource.ID },
            totalChapters = library.sumOf { it.totalChapters }.toInt(),
            readChapters = library.sumOf { it.readCount }.toInt(),
            downloadedChapters = countDownloads(storage.downloads),
            trackedCount = tracks.count { it.value.isNotEmpty() },
            meanScore = if (scores.isEmpty()) Double.NaN else scores.average(),
            trackerCount = loggedIn.size,
        )
    }

    /** Entries a library update checks, as StatsScreenModel counts them. */
    private fun globalUpdateCount(library: List<LibraryManga>): Int {
        val included = preferences.updateCategories().get().mapTo(HashSet()) { it.toLong() }
        val excluded = preferences.updateCategoriesExclude().get().mapTo(HashSet()) { it.toLong() }
        val restrictions = preferences.autoUpdateMangaRestrictions().get()
        return library.filter {
            (included.isEmpty() || it.categories.any(included::contains)) && it.categories.none(excluded::contains)
        }.count {
            !(
                (LibraryPreferences.MANGA_NON_COMPLETED in restrictions && it.manga.status.toInt() == SManga.COMPLETED) ||
                    (LibraryPreferences.MANGA_HAS_UNREAD in restrictions && it.unreadCount != 0L) ||
                    (LibraryPreferences.MANGA_NON_READ in restrictions && it.totalChapters > 0 && !it.hasStarted)
                )
        }
    }

    /** Downloaded chapters: `downloads/<source>/<entry>/<chapter>` (a CBZ or a folder). */
    private fun countDownloads(root: File): Int =
        root.listFiles().orEmpty().filter { it.isDirectory }.sumOf { source ->
            source.listFiles().orEmpty().filter { it.isDirectory }.sumOf { entry ->
                entry.listFiles().orEmpty().count { (it.isDirectory || it.extension == "cbz") && !it.name.startsWith("_tmp") }
            }
        }
}

object StatsScreen : Screen {
    private fun readResolve(): Any = StatsScreen

    override val key: ScreenKey = "stats"

    @Composable
    override fun Content() {
        val model = rememberScreenModel { StatsScreenModel() }
        val stats by model.state.collectAsState()
        val allRead by model.allRead.collectAsState()
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(localized(MR.strings.label_stats)) {
                // SY: the overflow switches between library entries and all read entries
                var menu by remember { mutableStateOf(false) }
                Box {
                    TooltipIconButton(Icons.Outlined.MoreVert, localized(MR.strings.label_more), onClick = { menu = true })
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (allRead) {
                                        localized(SYMR.strings.ignore_non_library_entries)
                                    } else {
                                        localized(SYMR.strings.include_all_read_entries)
                                    },
                                )
                            },
                            onClick = {
                                menu = false
                                model.toggleReadManga()
                            },
                            modifier = Modifier.testTag("stats-all-read"),
                        )
                    }
                }
            }
            val s = stats ?: return LoadingScreen()
            Column(
                Modifier.fillMaxSize().verticalScrollWithBar().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Section(localized(MR.strings.label_overview_section)) {
                    Stat("${s.libraryCount}", localized(MR.strings.in_library))
                    Stat("${s.completedCount}", localized(MR.strings.completed))
                    Stat(formatDuration(s.readDurationMs), localized(DMR.strings.read_time))
                }
                Section(localized(MR.strings.label_titles_section)) {
                    Stat("${s.globalUpdateCount}", localized(MR.strings.label_titles_in_global_update))
                    Stat("${s.startedCount}", localized(MR.strings.label_started))
                    Stat("${s.localCount}", localized(MR.strings.label_local))
                }
                Section(localized(MR.strings.chapters)) {
                    Stat("${s.totalChapters}", localized(MR.strings.label_total_chapters))
                    Stat("${s.readChapters}", localized(MR.strings.label_read_chapters))
                    Stat("${s.downloadedChapters}", localized(MR.strings.label_downloaded))
                }
                Section(localized(MR.strings.services)) {
                    Stat("${s.trackedCount}", localized(MR.strings.label_tracked_titles))
                    Stat(if (s.meanScore.isNaN()) "—" else String.format(Locale.getDefault(), "%.2f ★", s.meanScore), localized(MR.strings.label_mean_score))
                    Stat("${s.trackerCount}", localized(DMR.strings.trackers_used))
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Surface(shape = Corners.Large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.width(200.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall)
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0m"
    val d = ms.milliseconds
    return d.toComponents { days, hours, minutes, _, _ ->
        listOfNotNull(
            days.takeIf { it > 0 }?.let { "${it}d" },
            hours.takeIf { it > 0 }?.let { "${it}h" },
            "${minutes}m".takeIf { days == 0L },
        ).joinToString(" ")
    }
}
