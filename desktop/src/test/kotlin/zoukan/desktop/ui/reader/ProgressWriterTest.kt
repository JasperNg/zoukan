package zoukan.desktop.ui.reader

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.ChapterUpdate
import java.util.Collections

class ProgressWriterTest {
    /** A chapter row as the database keeps it: a null field leaves it as it is */
    private class Row(var read: Boolean = false, var lastPageRead: Long = 0) {
        @Synchronized
        fun apply(update: ChapterUpdate) {
            update.read?.let { read = it }
            update.lastPageRead?.let { lastPageRead = it }
        }
    }

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Test
    fun `writes finish in the order the pages were turned, even when an earlier one is slower`() = runBlocking<Unit> {
        val row = Row()
        val order = Collections.synchronizedList(mutableListOf<Long>())
        val writer = ProgressWriter(scope()) { update ->
            // The earlier writes take longer, as a busy database would make them
            delay((10 - update.lastPageRead!!) * 20)
            row.apply(update)
            order += update.lastPageRead!!
        }
        (0..9).forEach { writer.submit(progressUpdate(1L, it, read = it == 9)) }
        writer.close()
        withTimeout(10_000) { writer.job.join() }

        order shouldContainExactly (0L..9L).toList()
        row.lastPageRead shouldBe 9L
        row.read shouldBe true
    }

    @Test
    fun `a progress write never makes a read chapter unread`() = runBlocking<Unit> {
        progressUpdate(1L, 3, read = false).read shouldBe null
        progressUpdate(1L, 3, read = true).read shouldBe true
        progressUpdate(1L, 3, read = false).lastPageRead shouldBe 3L

        val row = Row(read = true, lastPageRead = 20)
        val writer = ProgressWriter(scope()) { row.apply(it) }
        writer.submit(progressUpdate(1L, 4, read = false))
        writer.close()
        withTimeout(10_000) { writer.job.join() }

        row.read shouldBe true
        row.lastPageRead shouldBe 4L
    }

    @Test
    fun `a failed write does not stop the ones after it`() = runBlocking<Unit> {
        val row = Row()
        val writer = ProgressWriter(scope()) { update ->
            if (update.lastPageRead == 1L) error("database is locked")
            row.apply(update)
        }
        writer.submit(progressUpdate(1L, 1, read = false))
        writer.submit(progressUpdate(1L, 2, read = false))
        writer.close()
        withTimeout(10_000) { writer.job.join() }

        row.lastPageRead shouldBe 2L
    }

    @Test
    fun `closing lets the queued writes finish`() = runBlocking<Unit> {
        val row = Row()
        val writer = ProgressWriter(scope()) { update ->
            delay(50)
            row.apply(update)
        }
        writer.submit(progressUpdate(1L, 5, read = false))
        writer.submit(progressUpdate(1L, 6, read = true))
        writer.close()
        // A page change after the reader closed is dropped, not an error
        writer.submit(progressUpdate(1L, 0, read = false))
        withTimeout(10_000) { writer.job.join() }

        writer.job.isCompleted shouldBe true
        row.lastPageRead shouldBe 6L
        row.read shouldBe true
    }
}
