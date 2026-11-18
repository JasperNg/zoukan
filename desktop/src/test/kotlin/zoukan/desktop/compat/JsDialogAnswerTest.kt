package zoukan.desktop.compat

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import xyz.nulldev.androidcompat.webkit.KcefWebViewProvider.JsDialogAnswer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * A page's alert, confirm or prompt in an extension's hidden WebView is answered to Chromium
 * exactly once, and never left waiting: a dropped post or a client that never answers cancels it.
 * Chromium is not started; the answer goes to a recording callback.
 */
class JsDialogAnswerTest {

    private val answers = CopyOnWriteArrayList<Pair<Boolean, String>>()
    private val record: (Boolean, String) -> Unit = { ok, text -> answers += ok to text }

    /** A timeout that runs only when the test calls [fire]. */
    private class ManualTimeout {
        var task: Runnable? = null
        val future = CompletableFuture<Unit>()

        fun schedule(task: Runnable): Future<*> {
            this.task = task
            return future
        }

        fun fire() = task!!.run()
    }

    @Test
    fun `a dropped post cancels the dialog at once`() {
        val timeout = ManualTimeout()
        val dialog = JsDialogAnswer(record, timeout::schedule)
        var ran = false

        dialog.post({ false }) { ran = true }

        ran shouldBe false
        dialog.isAnswered shouldBe true
        answers.shouldContainExactly(false to "")
        timeout.future.isCancelled shouldBe true
    }

    @Test
    fun `a post that throws cancels the dialog`() {
        val dialog = JsDialogAnswer(record, ManualTimeout()::schedule)

        dialog.post({ throw IllegalStateException("looper gone") }) {}

        answers.shouldContainExactly(false to "")
    }

    @Test
    fun `an accepted post leaves the answer to the task`() {
        val dialog = JsDialogAnswer(record, ManualTimeout()::schedule)
        val posted = mutableListOf<Runnable>()

        dialog.post({
            posted += it
            true
        }) { dialog.answer(true, "typed") }

        answers.shouldBeEmpty()
        posted.single().run()
        answers.shouldContainExactly(true to "typed")
    }

    @Test
    fun `the timeout cancels a dialog the client never answers`() {
        val timeout = ManualTimeout()
        val dialog = JsDialogAnswer(record, timeout::schedule)
        // The client kept the JsResult and never confirmed or cancelled it
        dialog.post({
            it.run()
            true
        }) {}
        answers.shouldBeEmpty()

        timeout.fire()

        answers.shouldContainExactly(false to "")
    }

    @Test
    fun `an answer before the timeout is given once and cancels the timeout`() {
        val timeout = ManualTimeout()
        val dialog = JsDialogAnswer(record, timeout::schedule)

        dialog.answer(true, null) shouldBe true
        timeout.future.isCancelled shouldBe true
        // A timeout already running when it was cancelled does nothing either
        timeout.fire()
        dialog.answer(false, null) shouldBe false

        answers.shouldContainExactly(true to "")
    }

    @Test
    fun `the shared timeout thread cancels an unanswered dialog`() {
        val done = CountDownLatch(1)
        JsDialogAnswer({ ok, text ->
            answers += ok to text
            done.countDown()
        }) {
            JsDialogAnswer.scheduleOnTimeoutThread(it, 20)
        }

        done.await(5, TimeUnit.SECONDS) shouldBe true
        answers.shouldContainExactly(false to "")
    }

    @Test
    fun `a failing Chromium callback or timeout scheduler never throws`() {
        val dialog = JsDialogAnswer({ _, _ -> throw IllegalStateException("browser gone") }) {
            throw java.util.concurrent.RejectedExecutionException("shut down")
        }

        dialog.answer(false, null) shouldBe true
        dialog.isAnswered shouldBe true
    }
}
