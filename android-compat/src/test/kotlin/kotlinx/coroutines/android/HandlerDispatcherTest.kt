package kotlinx.coroutines.android

import android.os.Handler
import android.os.HandlerThread
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext

@OptIn(InternalCoroutinesApi::class)
class HandlerDispatcherTest {
    private inline fun withHandlerThread(
        name: String = "HandlerDispatcherTest",
        block: (HandlerThread, Handler, HandlerDispatcher) -> Unit,
    ) {
        val thread = HandlerThread(name)
        thread.start()
        try {
            val handler = Handler(thread.looper)
            val dispatcher = handler.asCoroutineDispatcher(name)
            block(thread, handler, dispatcher)
        } finally {
            thread.quit()
            thread.join(5_000)
        }
    }

    @Test
    fun `dispatches work onto the handler looper thread in FIFO order`() =
        withHandlerThread { thread, _, dispatcher ->
            val recordedThreads = mutableListOf<Thread>()
            val recordedOrder = mutableListOf<Int>()

            runBlocking {
                withContext(dispatcher) {
                    recordedThreads += Thread.currentThread()
                    val first = launch(dispatcher) { recordedOrder += 1 }
                    val second = launch(dispatcher) { recordedOrder += 2 }
                    val third = launch(dispatcher) { recordedOrder += 3 }
                    first.join()
                    second.join()
                    third.join()
                }
            }

            recordedThreads shouldContainExactly listOf(thread)
            recordedOrder shouldContainExactly listOf(1, 2, 3)
        }

    @Test
    fun `isDispatchNeeded reflects immediate vs non-immediate on and off the looper thread`() =
        withHandlerThread { _, _, dispatcher ->
            dispatcher.isDispatchNeeded(EmptyCoroutineContext) shouldBe true
            dispatcher.immediate.isDispatchNeeded(EmptyCoroutineContext) shouldBe true

            val (normalNeededOnLooper, immediateNeededOnLooper) =
                runBlocking {
                    withContext(dispatcher) {
                        dispatcher.isDispatchNeeded(EmptyCoroutineContext) to
                            dispatcher.immediate.isDispatchNeeded(EmptyCoroutineContext)
                    }
                }

            normalNeededOnLooper shouldBe true
            immediateNeededOnLooper shouldBe false
        }

    @Test
    fun `immediate runs inline on the looper thread without re-queueing behind posted work`() =
        withHandlerThread { _, handler, dispatcher ->
            val events = mutableListOf<String>()

            runBlocking {
                withContext(dispatcher) {
                    val queuedDone = CompletableDeferred<Unit>()
                    handler.post {
                        events += "queued"
                        queuedDone.complete(Unit)
                    }
                    withContext(dispatcher.immediate) {
                        events += "immediate"
                    }
                    queuedDone.await()
                }

                withContext(dispatcher) {
                    val queuedDone = CompletableDeferred<Unit>()
                    handler.post {
                        events += "queued-2"
                        queuedDone.complete(Unit)
                    }
                    launch(dispatcher) {
                        events += "dispatched"
                    }.join()
                    queuedDone.await()
                }
            }

            events shouldContainExactly listOf("immediate", "queued", "queued-2", "dispatched")
        }

    @Test
    fun `supports delayed resumption and timeout cancellation on the handler thread`() =
        withHandlerThread { thread, _, dispatcher ->
            runBlocking {
                val resumedThread =
                    withContext(dispatcher) {
                        delay(20)
                        Thread.currentThread()
                    }
                resumedThread shouldBe thread

                shouldThrow<TimeoutCancellationException> {
                    withContext(dispatcher) {
                        withTimeout(20) {
                            delay(5_000)
                        }
                    }
                }
            }
        }

    @Test
    fun `invokeOnTimeout cancels pending callback cleanly when disposed early`() =
        withHandlerThread { _, _, dispatcher ->
            val fired = CountDownLatch(1)
            val handle = dispatcher.invokeOnTimeout(25, { fired.countDown() }, EmptyCoroutineContext)
            handle.dispose()

            runBlocking {
                withContext(dispatcher) {
                    delay(60)
                }
            }
            fired.count shouldBe 1L
        }

    @Test
    fun `propagates exceptions thrown in dispatched coroutines`() =
        withHandlerThread { _, _, dispatcher ->
            runBlocking {
                val failure =
                    shouldThrow<IllegalStateException> {
                        withContext(dispatcher) {
                            error("handler boom")
                        }
                    }
                failure.message shouldBe "handler boom"

                supervisorScope {
                    val deferred =
                        async(dispatcher) {
                            throw IllegalArgumentException("async boom")
                        }
                    val asyncFailure =
                        shouldThrow<IllegalArgumentException> {
                            deferred.await()
                        }
                    asyncFailure.message shouldBe "async boom"
                }
            }
        }

    @Test
    fun `rejected dispatch after looper quit cancels job and runs fallback on IO`() =
        withHandlerThread { thread, handler, dispatcher ->
            thread.quit()
            thread.join(5_000)

            val fallbackRan = CompletableFuture<String>()
            val job =
                runBlocking {
                    launch(dispatcher) {
                        fallbackRan.complete(Thread.currentThread().name)
                    }.also {
                        it.join()
                    }
                }

            job.isCancelled shouldBe true
            val threadName = fallbackRan.get(5, TimeUnit.SECONDS)
            threadName shouldNotBe thread.name

            val delayedCancelled = CompletableFuture<Throwable?>()
            runBlocking {
                val delayedJob =
                    launch(dispatcher) {
                        try {
                            delay(50)
                        } catch (e: CancellationException) {
                            delayedCancelled.complete(e)
                            throw e
                        }
                    }
                delayedJob.join()
                delayedJob.isCancelled shouldBe true
            }
            (delayedCancelled.get(5, TimeUnit.SECONDS) is CancellationException) shouldBe true
            handler.looper shouldBe thread.looper
        }

    @Test
    fun `equals hashCode and toString respect underlying handler and immediate flag`() =
        withHandlerThread("CustomDispatcher") { thread, handler, dispatcher ->
            val second = Handler(thread.looper).asCoroutineDispatcher("OtherName")

            dispatcher.toString() shouldBe "CustomDispatcher"
            dispatcher.immediate.toString() shouldBe "CustomDispatcher.immediate"
            dispatcher.immediate.immediate shouldBe dispatcher.immediate

            (dispatcher == dispatcher) shouldBe true
            (dispatcher == dispatcher.immediate) shouldBe false
            (dispatcher == second) shouldBe false

            val sameHandlerWrapper = handler.asCoroutineDispatcher("Duplicate")
            (dispatcher == sameHandlerWrapper) shouldBe true
            dispatcher.hashCode() shouldBe sameHandlerWrapper.hashCode()
            (dispatcher.immediate == sameHandlerWrapper.immediate) shouldBe true
            dispatcher.immediate.hashCode() shouldBe sameHandlerWrapper.immediate.hashCode()
        }
}
