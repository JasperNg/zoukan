package zoukan.desktop.download

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import logcat.LogPriority
import logcat.LogcatLogger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import zoukan.desktop.Slf4jLogcatLogger
import java.io.File

/**
 * An entry whose extension is not installed (a library restored before its extensions) cannot
 * download. That is an expected state: the chapter fails with a message, and nothing is logged as
 * an error, which the button crawl and the crash reports treat as a bug.
 */
class DownloadSourceMissingTest {

    @TempDir
    lateinit var temp: File

    private val kits = mutableListOf<DownloadKit>()
    private val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
    private val appender = ListAppender<ILoggingEvent>().apply { start() }
    private var installedLogger = false

    @AfterEach
    fun tearDown() {
        root.detachAppender(appender)
        if (installedLogger) LogcatLogger.uninstall()
        // Queue saves run in the background; let them finish before the temp folder goes
        kits.forEach { it.awaitBackground() }
    }

    private fun installLogger() {
        // As the app does at start (ZoukanRuntime): logcat goes to SLF4J
        if (!LogcatLogger.isInstalled) {
            LogcatLogger.install(Slf4jLogcatLogger(LogPriority.INFO))
            installedLogger = true
        }
        root.addAppender(appender)
    }

    @Test
    fun `an entry whose source is not installed queues nothing, as on Android`() = runBlocking<Unit> {
        installLogger()
        val kit = DownloadKit(temp).also { kits += it }

        kit.manager.enqueue(kit.manga(1, "Alpha", source = 42), listOf(kit.chapter(1, 1)))
        withTimeout(30_000) { kit.manager.awaitIdle() }

        kit.manager.queue.value.shouldBeEmpty()
        appender.list.filter { it.level == Level.ERROR }.shouldBeEmpty()
    }

    @Test
    fun `a source uninstalled after its chapters were queued fails them without logging an error`() = runBlocking<Unit> {
        installLogger()
        val kit = DownloadKit(temp, sources = listOf(DownloadKit.TestSource("Gone", 42))).also { kits += it }
        val manga = kit.manga(1, "Alpha", source = 42)

        kit.manager.enqueue(manga, listOf(kit.chapter(1, 1)), start = false)
        kit.byId.remove(42)
        kit.manager.start()
        withTimeout(30_000) { kit.manager.awaitIdle() }

        val download = kit.manager.queue.value.single()
        download.state.value shouldBe Download.State.ERROR
        download.error!! shouldContain "Alpha"
        val logs = appender.list.filter { "Alpha" in it.formattedMessage }
        logs.shouldNotBeEmpty()
        logs.filter { it.level == Level.ERROR }.shouldBeEmpty()
    }
}
