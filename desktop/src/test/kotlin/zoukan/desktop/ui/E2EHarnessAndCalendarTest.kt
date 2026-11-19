package zoukan.desktop.ui

import android.os.Looper
import android.os.Message
import android.os.SystemClock
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import cafe.adriel.voyager.core.model.ScreenModelStore
import cafe.adriel.voyager.core.model.StateScreenModel
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.jupiter.api.Test
import zoukan.desktop.ui.settings.SettingsCategory
import zoukan.desktop.ui.settings.openSettingsCategory
import java.time.LocalDate
import java.time.YearMonth
import kotlin.coroutines.CoroutineContext

/**
 * The effect context for end-to-end UI tests. By default the Compose test runs effects on an
 * UnconfinedTestDispatcher, so a collector (collectAsState, produceState) resumes on whichever
 * thread emitted: a collector that emits on Dispatchers.Main (the Swing event thread, as in the
 * app) writes its state on that thread, which then races the composition on the test thread, and
 * about one run in ten on Windows the screen never recomposed for it. In the app, effects and
 * composition share the Swing thread. A StandardTestDispatcher queues every resumption and runs it
 * on the test thread between frames, as before.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun e2eEffectContext(): CoroutineContext = StandardTestDispatcher()

/** What an end-to-end test prints when it times out, to see where a stalled step waits. */
object E2EDiagnostics {

    @Suppress("UNCHECKED_CAST")
    fun dump() {
        println("E2E: Dispatchers.Main is ${runCatching { "${Dispatchers.Main} (${Dispatchers.Main.javaClass.name}), immediate ${Dispatchers.Main.immediate}" }.getOrElse { "unavailable: $it" }}")
        val looper = Looper.getMainLooper()
        if (looper == null) {
            println("E2E: no main looper")
        } else {
            println("E2E: main looper queue at uptime ${SystemClock.uptimeMillis()}:")
            runCatching {
                val queue = looper.queue
                synchronized(queue) {
                    var message = field(queue, "mMessages") as Message?
                    if (message == null) println("E2E:   (empty)")
                    while (message != null) {
                        println("E2E:   when=${message.`when`} async=${message.isAsynchronous} target=${message.target} callback=${field(message, "callback")}")
                        message = field(message, "next") as Message?
                    }
                }
            }.onFailure { println("E2E:   reading it failed: $it") }
        }
        runCatching {
            println("E2E: screen models:")
            (ScreenModelStore.javaClass.getMethod("getScreenModels").invoke(ScreenModelStore) as Map<String, Any>).forEach { (key, model) ->
                val state = (model as? StateScreenModel<*>)?.state?.value
                val jobs = model.javaClass.declaredFields.filter { Job::class.java.isAssignableFrom(it.type) }
                    .joinToString { f -> "${f.name}=" + (f.apply { isAccessible = true }.get(model) as Job?)?.let(::jobState) }
                println("E2E:   $key -> $state ${if (jobs.isEmpty()) "" else "[$jobs]"}")
            }
            println("E2E: screen model dependencies:")
            (ScreenModelStore.javaClass.getMethod("getDependencies").invoke(ScreenModelStore) as Map<String, Pair<Any, *>>).forEach { (key, value) ->
                val job = (value.first as? CoroutineScope)?.coroutineContext?.get(Job)
                println("E2E:   $key -> ${job?.let(::jobState) ?: value.first}")
            }
        }.onFailure { println("E2E:   reading the screen models failed: $it") }
        runCatching {
            androidx.compose.runtime.Recomposer.runningRecomposers.value.forEach { info ->
                println("E2E: recomposer state=${runBlocking { info.state.first() }} changeCount=${info.changeCount}")
            }
        }.onFailure { println("E2E:   reading the recomposers failed: $it") }
        Thread.getAllStackTraces().entries.sortedBy { it.key.name }.forEach { (thread, stack) ->
            println("E2E: thread \"${thread.name}\" ${thread.state}:\n    " + stack.take(30).joinToString("\n    "))
        }
    }

    private fun field(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun jobState(job: Job): String = when {
        job.isCancelled -> "cancelled"
        job.isCompleted -> "completed"
        job.isActive -> "active (${job.children.count()} children)"
        else -> "new"
    }
}

/**
 * Opens [page] in Settings and returns the list of its settings. Settings shows the list of pages
 * on the left and the open page on the right; both scroll, so the page is the scrolling list that
 * does not list the pages.
 */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.settingsPage(page: SettingsCategory): SemanticsNodeInteraction {
    runOnUiThread { openSettingsCategory(page) }
    waitForIdle()
    val pageList = hasScrollAction() and !hasAnyDescendant(hasText(SettingsCategory.Appearance.subtitle))
    waitUntil(timeoutMillis = 10_000) { onAllNodes(pageList).fetchSemanticsNodes().isNotEmpty() }
    return onAllNodes(pageList).onFirst()
}

/**
 * The day to click in the Upcoming calendar: the first seeded release day in the month the
 * calendar opens on, or today when every release falls in the next month.
 */
internal fun visibleCalendarDay(today: LocalDate, releaseOffsets: List<Long>): LocalDate {
    val month = YearMonth.from(today)
    return releaseOffsets.sortedDescending()
        .map { today.plusDays(it) }
        .firstOrNull { YearMonth.from(it) == month }
        ?: today
}

/**
 * The Upcoming calendar opens on the current month and only tags that month's days, so the
 * end-to-end test must click a release day inside it, also on the last days of a month.
 */
class UpcomingCalendarDayTest {

    @Test
    fun `a release three days ahead is clicked when it is in this month`() {
        visibleCalendarDay(LocalDate.of(2026, 11, 10), listOf(1, 3)) shouldBe LocalDate.of(2026, 11, 13)
    }

    @Test
    fun `on the 29th the release tomorrow is clicked, not one in the next month`() {
        visibleCalendarDay(LocalDate.of(2026, 11, 29), listOf(1, 3)) shouldBe LocalDate.of(2026, 11, 30)
    }

    @Test
    fun `on the last day of the month today is clicked`() {
        visibleCalendarDay(LocalDate.of(2026, 12, 31), listOf(1, 3)) shouldBe LocalDate.of(2026, 12, 31)
    }
}
