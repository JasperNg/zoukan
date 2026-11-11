package zoukan.desktop.ui.upcoming

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.domain.upcoming.interactor.GetUpcomingManga
import tachiyomi.core.common.Constants
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.library.service.LibraryPreferences.Companion.MANGA_OUTSIDE_RELEASE_PERIOD
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.common.Corners
import zoukan.desktop.ui.common.Cover
import zoukan.desktop.ui.common.CoverImage
import zoukan.desktop.ui.common.EmptyScreen
import zoukan.desktop.ui.common.LazyColumn
import zoukan.desktop.ui.common.LoadingScreen
import zoukan.desktop.ui.common.ScreenHeader
import zoukan.desktop.ui.common.TooltipIconButton
import zoukan.desktop.ui.common.coverOutline
import zoukan.desktop.ui.common.reducedMotion
import zoukan.desktop.ui.manga.MangaScreen
import zoukan.i18n.desktop.DMR
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Locale

/** A row of the Upcoming list: a date with its entry count, or an entry. */
sealed interface UpcomingUIModel {
    data class Header(val date: LocalDate, val mangaCount: Int) : UpcomingUIModel
    data class Item(val manga: Manga) : UpcomingUIModel
}

/**
 * Android's Updates > Upcoming (mihon.feature.upcoming): library entries by their expected next
 * release date, on a calendar and in a list. Komikku's "Smart update" button switches to the
 * entries the next library update will check ("To be updated").
 */
class UpcomingScreenModel(
    private val getUpcomingManga: GetUpcomingManga = Injekt.get(),
    libraryPreferences: LibraryPreferences = Injekt.get(),
) : StateScreenModel<UpcomingScreenModel.State>(State()) {

    data class State(
        val selectedYearMonth: YearMonth = YearMonth.now(),
        val items: List<UpcomingUIModel> = emptyList(),
        val events: Map<LocalDate, Int> = emptyMap(),
        val headerIndexes: Map<LocalDate, Int> = emptyMap(),
        val isLoadingUpcoming: Boolean = true,
        val isShowingUpdatingMangas: Boolean = false,
        val updatingItems: List<UpcomingUIModel> = emptyList(),
        val updatingEvents: Map<LocalDate, Int> = emptyMap(),
        val updatingHeaderIndexes: Map<LocalDate, Int> = emptyMap(),
        val isLoadingUpdating: Boolean = true,
    )

    /** Android shows the "Smart update" button only when updates skip entries outside their release period. */
    val isPredictReleaseDate: Boolean = MANGA_OUTSIDE_RELEASE_PERIOD in libraryPreferences.autoUpdateMangaRestrictions().get()

    init {
        screenModelScope.launch {
            getUpcomingManga.subscribe().collectLatest { list ->
                val items = list.toUpcomingUIModels()
                mutableState.update {
                    it.copy(isLoadingUpcoming = false, items = items, events = items.toEvents(), headerIndexes = items.headerIndexes())
                }
            }
        }
        screenModelScope.launch {
            val items = try {
                withContext(Dispatchers.IO) { getUpcomingManga.updatingMangas() }.toUpcomingUIModels()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Loading the entries to be updated failed" }
                emptyList()
            }
            mutableState.update {
                it.copy(
                    isLoadingUpdating = false,
                    updatingItems = items,
                    updatingEvents = items.toEvents(),
                    updatingHeaderIndexes = items.headerIndexes(),
                )
            }
        }
    }

    fun setSelectedYearMonth(yearMonth: YearMonth) {
        mutableState.update { it.copy(selectedYearMonth = yearMonth) }
    }

    fun toggleUpdatingMangas() {
        mutableState.update { it.copy(isShowingUpdatingMangas = !it.isShowingUpdatingMangas) }
    }

    companion object {
        /** The entries in date order, each date preceded by a header with its entry count. */
        fun List<Manga>.toUpcomingUIModels(): List<UpcomingUIModel> {
            val zone = ZoneId.systemDefault()
            val result = mutableListOf<UpcomingUIModel>()
            var date: LocalDate? = null
            var headerAt = -1
            forEach { manga ->
                val day = manga.expectedNextUpdate?.atZone(zone)?.toLocalDate()
                if (day != null && day != date) {
                    headerAt = result.size
                    result += UpcomingUIModel.Header(day, 0)
                }
                date = day
                result += UpcomingUIModel.Item(manga)
                if (day != null && headerAt >= 0) {
                    val header = result[headerAt] as UpcomingUIModel.Header
                    result[headerAt] = header.copy(mangaCount = header.mangaCount + 1)
                }
            }
            return result
        }

        fun List<UpcomingUIModel>.toEvents(): Map<LocalDate, Int> =
            filterIsInstance<UpcomingUIModel.Header>().associate { it.date to it.mangaCount }

        fun List<UpcomingUIModel>.headerIndexes(): Map<LocalDate, Int> =
            withIndex().filter { it.value is UpcomingUIModel.Header }
                .associate { (it.value as UpcomingUIModel.Header).date to it.index }
    }
}

object UpcomingScreen : Screen {
    private fun readResolve(): Any = UpcomingScreen

    override val key: ScreenKey = "upcoming"

    @Composable
    override fun Content() {
        val model = rememberScreenModel { UpcomingScreenModel() }
        val state by model.state.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        val uriHandler = LocalUriHandler.current
        val scope = rememberCoroutineScope()
        val listState = rememberLazyListState()

        val updating = state.isShowingUpdatingMangas
        val items = if (updating) state.updatingItems else state.items
        val events = if (updating) state.updatingEvents else state.events
        val headerIndexes = if (updating) state.updatingHeaderIndexes else state.headerIndexes
        val loading = if (updating) state.isLoadingUpdating else state.isLoadingUpcoming

        Column(Modifier.fillMaxSize()) {
            ScreenHeader(if (updating) localized(KMR.strings.label_to_be_updated) else localized(MR.strings.label_upcoming)) {
                if (model.isPredictReleaseDate) {
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                        tooltip = { PlainTooltip { Text(localized(MR.strings.pref_library_update_smart_update)) } },
                        state = rememberTooltipState(),
                    ) {
                        IconButton(onClick = model::toggleUpdatingMangas) {
                            Icon(
                                Icons.Outlined.NewReleases,
                                contentDescription = localized(MR.strings.pref_library_update_smart_update),
                                tint = if (updating) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                            )
                        }
                    }
                }
                TooltipIconButton(Icons.AutoMirrored.Outlined.HelpOutline, localized(DMR.strings.upcoming_guide), onClick = {
                    runCatching { uriHandler.openUri(Constants.URL_HELP_UPCOMING) }
                })
            }
            if (loading) {
                LoadingScreen()
                return@Column
            }
            // Android's large-screen layout: the calendar beside the list
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(0.4f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                    Calendar(
                        selectedYearMonth = state.selectedYearMonth,
                        events = events,
                        setSelectedYearMonth = model::setSelectedYearMonth,
                        onClickDay = { day -> headerIndexes[day]?.let { scope.launch { listState.animateScrollToItem(it) } } },
                        modifier = Modifier.widthIn(max = 480.dp).padding(horizontal = 16.dp),
                    )
                }
                VerticalDivider()
                Box(Modifier.weight(0.6f).fillMaxHeight()) {
                    if (items.isEmpty()) {
                        EmptyScreen(
                            if (updating) {
                                localized(DMR.strings.no_entries_match_the_library_update)
                            } else {
                                localized(DMR.strings.no_upcoming_releases_release_dates_are)
                            },
                        )
                    } else {
                        LazyColumn(Modifier.fillMaxSize(), state = listState) {
                            items(
                                items,
                                key = { (if (updating) "updating" else "upcoming") + it.hashCode() },
                                contentType = { if (it is UpcomingUIModel.Header) "header" else "item" },
                            ) { item ->
                                when (item) {
                                    is UpcomingUIModel.Header -> DateHeading(item.date, item.mangaCount)
                                    is UpcomingUIModel.Item -> UpcomingItem(item.manga) { navigator.push(MangaScreen(item.manga.id)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private val shortDate = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)

/** Android's LocalDate.toRelativeString: "Today", "Tomorrow", "In 3 days", then the date. */
fun relativeDateText(date: LocalDate, today: LocalDate = LocalDate.now()): String {
    val difference = ChronoUnit.DAYS.between(date, today)
    return when {
        difference < -7 -> shortDate.format(date)
        difference == -1L -> localized(DMR.strings.tomorrow)
        difference < 0 -> localized(DMR.strings.in_days, -difference)
        difference < 1 -> localized(MR.strings.relative_time_today)
        difference == 1L -> localized(DMR.strings.yesterday)
        difference < 7 -> localized(DMR.strings.days_ago, difference)
        else -> shortDate.format(date)
    }
}

@Composable
private fun DateHeading(date: LocalDate, mangaCount: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().testTag("upcoming-header")) {
        Text(
            text = relativeDateText(date),
            modifier = Modifier.padding(8.dp).padding(start = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium,
        )
        Badge(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
            Text("$mangaCount")
        }
    }
}

@Composable
private fun UpcomingItem(manga: Manga, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .height(96.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("upcoming-item"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        CoverImage(
            Cover.of(manga),
            Modifier.fillMaxHeight().aspectRatio(2f / 3f).clip(Corners.Medium).coverOutline(Corners.Medium),
        )
        Text(
            modifier = Modifier.weight(1f),
            text = manga.title,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private const val DAYS_OF_WEEK = 7
private const val MAX_EVENTS = 3

@Composable
private fun Calendar(
    selectedYearMonth: YearMonth,
    events: Map<LocalDate, Int>,
    setSelectedYearMonth: (YearMonth) -> Unit,
    onClickDay: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CalendarHeader(
            yearMonth = selectedYearMonth,
            onPreviousClick = { setSelectedYearMonth(selectedYearMonth.minusMonths(1L)) },
            onNextClick = { setSelectedYearMonth(selectedYearMonth.plusMonths(1L)) },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).padding(start = 8.dp),
        )
        CalendarGrid(selectedYearMonth, events, onClickDay)
    }
}

@Composable
private fun CalendarHeader(yearMonth: YearMonth, onPreviousClick: () -> Unit, onNextClick: () -> Unit, modifier: Modifier) {
    val reduceMotion by reducedMotion()
    Row(modifier, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        AnimatedContent(
            targetState = yearMonth,
            transitionSpec = {
                // Under the system's reduced motion setting the month only cross-fades
                if (reduceMotion) {
                    fadeIn(tween(200)) togetherWith fadeOut(tween(200))
                } else {
                    val forward = targetState > initialState
                    val enter = slideInVertically(tween(200)) { if (forward) it else -it } + fadeIn(tween(200))
                    val exit = slideOutVertically(tween(200)) { if (forward) -it else it } + fadeOut(tween(200))
                    enter togetherWith exit
                }
            },
            label = localized(DMR.strings.change_month),
        ) { month ->
            Text(
                DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()).format(month),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.testTag("upcoming-month"),
            )
        }
        Row {
            TooltipIconButton(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, localized(MR.strings.upcoming_calendar_prev), onClick = onPreviousClick)
            TooltipIconButton(Icons.AutoMirrored.Outlined.KeyboardArrowRight, localized(MR.strings.upcoming_calendar_next), onClick = onNextClick)
        }
    }
}

@Composable
private fun CalendarGrid(selectedYearMonth: YearMonth, events: Map<LocalDate, Int>, onClickDay: (LocalDate) -> Unit) {
    val firstDayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek.value
    val weekDays = remember { (0 until DAYS_OF_WEEK).map { DayOfWeek.of((firstDayOfWeek - 1 + it) % DAYS_OF_WEEK + 1) } }
    val emptyFieldCount = weekDays.indexOf(selectedYearMonth.atDay(1).dayOfWeek)
    val cells: List<LocalDate?> = List(emptyFieldCount) { null } + (1..selectedYearMonth.lengthOfMonth()).map { selectedYearMonth.atDay(it) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            weekDays.forEach { day ->
                Text(
                    day.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                )
            }
        }
        cells.chunked(DAYS_OF_WEEK).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                (0 until DAYS_OF_WEEK).forEach { i ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        week.getOrNull(i)?.let { date -> CalendarDay(date, events[date] ?: 0) { onClickDay(date) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarDay(date: LocalDate, events: Int, onDayClick: () -> Unit) {
    val today = remember { LocalDate.now() }
    Box(
        modifier = Modifier.size(44.dp)
            .then(
                if (today == date) {
                    Modifier.border(BorderStroke(1.dp, MaterialTheme.colorScheme.onBackground), CircleShape)
                } else {
                    Modifier
                },
            )
            .clip(CircleShape)
            .clickable(onClick = onDayClick)
            .testTag("upcoming-day-${date.dayOfMonth}"),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = date.dayOfMonth.toString(),
            textAlign = TextAlign.Center,
            fontSize = 16.sp,
            color = if (date.isBefore(today)) {
                MaterialTheme.colorScheme.onBackground.copy(alpha = 0.38f)
            } else {
                MaterialTheme.colorScheme.onBackground
            },
            fontWeight = FontWeight.SemiBold,
        )
        if (events > 0) {
            Text(
                text = events.toString(),
                fontSize = 8.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.offset(x = 12.dp, y = (-10).dp),
            )
        }
        Row(Modifier.offset(y = 12.dp)) {
            repeat(events.coerceAtMost(MAX_EVENTS)) { index ->
                Box(
                    Modifier.padding(horizontal = 1.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = (index + 1) * 0.3f))
                        .size(56.dp / 12),
                )
            }
        }
    }
}
