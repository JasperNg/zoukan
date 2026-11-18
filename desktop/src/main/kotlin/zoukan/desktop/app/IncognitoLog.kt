package zoukan.desktop.app

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.turbo.TurboFilter
import ch.qos.logback.core.spi.FilterReply
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.slf4j.Marker
import tachiyomi.core.common.preference.Preference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * While app-wide incognito mode is on, nothing is logged: not to the log file (`zoukan.log`) and
 * not to the console. A crash or error report is still written, with its stack trace, but without
 * the end of the log (see [CrashReporter.report]).
 *
 * The logging path reads only [isOn], an atomic flag kept in step with the preference by [follow];
 * it never reads a preference itself.
 */
object IncognitoLog {
    private val on = AtomicBoolean(false)

    /** Whether incognito mode is on, as last seen by [follow]. */
    val isOn: Boolean get() = on.get()

    internal fun set(value: Boolean) {
        on.set(value)
    }

    /**
     * Keeps the flag in step with [incognitoMode] from now on: its current value at once, then each
     * change. A preference that cannot be read leaves logging on, so a failure there stays diagnosable.
     */
    fun follow(scope: CoroutineScope, incognitoMode: Preference<Boolean>) {
        set(runCatching { incognitoMode.get() }.getOrDefault(false))
        incognitoMode.changes()
            .onEach { set(it) }
            .catch { /* the flag keeps its last value; logging must never stop the app */ }
            .launchIn(scope)
    }

    /** Adds the filter to [context] once. Every appender (the log file, the console) is behind it. */
    internal fun installFilter(context: LoggerContext) {
        synchronized(context) {
            if (context.turboFilterList.any { it is Filter }) return
            context.addTurboFilter(
                Filter().apply {
                    this.context = context
                    start()
                },
            )
        }
    }

    /** Drops every logging event while [isOn]; otherwise leaves the decision to the rest of logback. */
    internal class Filter : TurboFilter() {
        override fun decide(
            marker: Marker?,
            logger: Logger?,
            level: Level?,
            format: String?,
            params: Array<out Any?>?,
            t: Throwable?,
        ): FilterReply = reply()

        // SLF4J's fluent API (logger.atInfo()...) arrives here instead
        override fun decide(logger: Logger?, event: org.slf4j.event.LoggingEvent?): FilterReply = reply()

        private fun reply(): FilterReply = if (on.get()) FilterReply.DENY else FilterReply.NEUTRAL
    }
}
