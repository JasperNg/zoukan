package xyz.nulldev.androidcompat.webkit

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import org.cef.browser.CefBrowser
import org.cef.browser.CefDevToolsClient
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class KcefHelperTest {
    private fun browserWithFuture(future: CompletableFuture<String>): CefBrowser {
        val client = mockk<CefDevToolsClient> {
            every { executeDevToolsMethod("Runtime.evaluate", any()) } returns future
        }
        return mockk<CefBrowser> {
            every { devToolsClient } returns client
        }
    }

    @Test
    fun `completing DevTools future on devtools-reply thread invokes callback on another thread`() {
        val future = CompletableFuture<String>()
        val browser = browserWithFuture(future)
        val callbackThread = CompletableFuture<String>()
        val callbackValue = CompletableFuture<String>()

        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "devtools-reply").apply { isDaemon = true }
        }
        try {
            browser.evaluateJavaScript("1 + 1") { value ->
                callbackThread.complete(Thread.currentThread().name)
                callbackValue.complete(value)
            }
            executor.submit {
                future.complete("""{"result":{"value":2}}""")
            }.get(5, TimeUnit.SECONDS)

            callbackValue.get(5, TimeUnit.SECONDS) shouldBe "2"
            callbackThread.get(5, TimeUnit.SECONDS) shouldNotBe "devtools-reply"
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `exception thrown by callback does not crash completing thread and is logged`() {
        val future = CompletableFuture<String>()
        val browser = browserWithFuture(future)
        val logger = LoggerFactory.getLogger("xyz.nulldev.androidcompat.webkit.KcefHelper") as Logger
        val loggedError = CompletableFuture<ILoggingEvent>()
        val appender = object : AppenderBase<ILoggingEvent>() {
            override fun append(eventObject: ILoggingEvent) {
                if (eventObject.level == Level.ERROR) loggedError.complete(eventObject)
            }
        }.apply { start() }
        logger.addAppender(appender)

        val uncaught = AtomicReference<Throwable?>()
        val cbEntered = CountDownLatch(1)
        val thread = Thread(
            {
                future.complete("""{"result":{"value":"boom"}}""")
            },
            "devtools-reply",
        ).apply {
            isDaemon = true
            setUncaughtExceptionHandler { _, e -> uncaught.set(e) }
        }

        try {
            browser.evaluateJavaScript("throwTest()") {
                cbEntered.countDown()
                error("callback failure")
            }
            thread.start()
            thread.join(5_000)
            cbEntered.await(5, TimeUnit.SECONDS) shouldBe true

            val errorEvent = loggedError.get(5, TimeUnit.SECONDS)
            uncaught.get() shouldBe null
            errorEvent.formattedMessage shouldBe "JavaScript evaluation callback failed"
            errorEvent.throwableProxy?.cause?.message shouldBe "callback failure"
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    @Test
    fun `null devToolsClient invokes callback with null string`() {
        val browser = mockk<CefBrowser> {
            every { devToolsClient } returns null
        }
        var received: String? = null
        browser.evaluateJavaScript("1") { received = it }
        received shouldBe "null"
    }

    @Test
    fun `failed DevTools evaluation invokes callback with null string`() {
        val future = CompletableFuture<String>()
        val browser = browserWithFuture(future)
        val received = CompletableFuture<String>()
        try {
            browser.evaluateJavaScript("1") { received.complete(it) }
            future.completeExceptionally(RuntimeException("devtools disconnected"))
            received.get(5, TimeUnit.SECONDS) shouldBe "null"
        } finally {
            future.cancel(true)
        }
    }

    @Test
    fun `androidEvaluationResult and plainValue decode valid results and exceptions`() {
        androidEvaluationResult("""{"result":{"value":"ok"}}""") shouldBe "\"ok\""
        androidEvaluationResult("""{"result":{"value":"ok"},"exceptionDetails":{"text":"Uncaught"}}""") shouldBe "null"
        androidEvaluationResult("""{"result":{}}""") shouldBe "null"
        androidEvaluationResult(null) shouldBe "null"

        plainValue("\"ok\"") shouldBe "ok"
        plainValue("null") shouldBe null
        plainValue("42") shouldBe "42"
        plainValue("""{"a":1}""") shouldBe """{"a":1}"""
    }
}
