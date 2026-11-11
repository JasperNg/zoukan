package zoukan.desktop.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.history.interactor.GetTotalReadDuration
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.track.interactor.GetTracks
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.local.LocalSource
import zoukan.desktop.storage.ZoukanStorage
import zoukan.desktop.ui.common.Corners
import zoukan.desktop.ui.common.LoadingScreen
import zoukan.desktop.ui.common.ScreenHeader
import zoukan.desktop.ui.common.verticalScrollWithBar
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
) : StateScreenModel<Stats?>(null) {

    init {
        screenModelScope.launch { mutableState.value = withContext(Dispatchers.IO) { compute() } }
    }

    private suspend fun compute(): Stats {
        val library = getLibraryManga.await().distinctBy { it.id }
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
            globalUpdateCount = globalUpdateCount(library),
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
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("Statistics")
            val s = stats ?: return LoadingScreen()
            Column(
                Modifier.fillMaxSize().verticalScrollWithBar().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Section("Overview") {
                    Stat("${s.libraryCount}", "In library")
                    Stat("${s.completedCount}", "Completed")
                    Stat(formatDuration(s.readDurationMs), "Read time")
                }
                Section("Entries") {
                    Stat("${s.globalUpdateCount}", "In global update")
                    Stat("${s.startedCount}", "Started")
                    Stat("${s.localCount}", "Local")
                }
                Section("Chapters") {
                    Stat("${s.totalChapters}", "Total")
                    Stat("${s.readChapters}", "Read")
                    Stat("${s.downloadedChapters}", "Downloaded")
                }
                Section("Trackers") {
                    Stat("${s.trackedCount}", "Tracked entries")
                    Stat(if (s.meanScore.isNaN()) "—" else String.format(Locale.getDefault(), "%.2f ★", s.meanScore), "Mean score")
                    Stat("${s.trackerCount}", "Trackers used")
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
