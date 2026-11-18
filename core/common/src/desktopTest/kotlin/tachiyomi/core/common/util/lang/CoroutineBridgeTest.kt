package tachiyomi.core.common.util.lang

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import rx.observers.TestSubscriber
import java.util.concurrent.TimeUnit

/**
 * The bridges between coroutines and the RxJava API extensions still use, and the helper that
 * finishes work the user must not lose: errors must reach the subscriber, and a cancellation must
 * end the work quietly rather than as an error.
 */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class CoroutineBridgeTest {

    @Test
    fun `runAsObservable emits the block's value and completes`() {
        val subscriber = TestSubscriber<Int>()
        runAsObservable { 42 }.subscribe(subscriber)
        subscriber.awaitTerminalEvent(5, TimeUnit.SECONDS)
        subscriber.assertValues(42)
        subscriber.assertCompleted()
    }

    @Test
    fun `runAsObservable passes a failure to the subscriber as an error`() {
        val subscriber = TestSubscriber<Int>()
        runAsObservable<Int> { error("source failed") }.subscribe(subscriber)
        subscriber.awaitTerminalEvent(5, TimeUnit.SECONDS)
        subscriber.assertNoValues()
        subscriber.onErrorEvents.single().shouldBeInstanceOf<IllegalStateException>().message shouldBe "source failed"
    }

    @Test
    fun `unsubscribing cancels the block, which completes quietly instead of erroring`() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val subscriber = TestSubscriber<Int>()
        val subscription = runAsObservable<Int> {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }.subscribe(subscriber)
        withTimeout(5_000) { started.await() }
        subscription.unsubscribe()
        withTimeout(5_000) { cancelled.await() }
        subscriber.assertNoErrors()
        subscriber.assertNoValues()
    }

    @Test
    fun `awaitSingle cancels the subscription when the coroutine is cancelled`() = runBlocking<Unit> {
        val subscribed = CompletableDeferred<Unit>()
        val unsubscribed = CompletableDeferred<Unit>()
        val never = rx.Observable.never<Int>()
            .doOnSubscribe { subscribed.complete(Unit) }
            .doOnUnsubscribe { unsubscribed.complete(Unit) }
        val job = launch { never.awaitSingle() }
        withTimeout(5_000) { subscribed.await() }
        job.cancelAndJoin()
        withTimeout(5_000) { unsubscribed.await() }
    }

    @Test
    fun `non-cancellable work finishes even when its caller is cancelled`() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        var finished = false
        val job = launch {
            withNonCancellableContext {
                entered.complete(Unit)
                // A suspension point after the cancel: it is not interrupted
                proceed.await()
                finished = true
            }
        }
        withTimeout(5_000) { entered.await() }
        job.cancel()
        proceed.complete(Unit)
        withTimeout(5_000) { job.join() }
        finished shouldBe true
        job.isCancelled shouldBe true
    }

    @Test
    fun `booleans are stored as one and zero`() {
        true.toLong() shouldBe 1L
        false.toLong() shouldBe 0L
    }
}
