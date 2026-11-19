package xyz.nulldev.androidcompat.webkit

import android.util.Log
import android.view.MotionEvent
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.cef.browser.CefBrowser
import org.cef.browser.CefDevToolsClient
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class KcefDevToolsTest {
    private fun browserWithClient(client: CefDevToolsClient?): CefBrowser =
        mockk<CefBrowser> {
            every { devToolsClient } returns client
        }

    private inline fun withLogCapture(
        level: Level,
        block: (events: CompletableFuture<ILoggingEvent>) -> Unit,
    ) {
        val logger = LoggerFactory.getLogger(Log::class.java) as Logger
        val matched = CompletableFuture<ILoggingEvent>()
        val appender = object : AppenderBase<ILoggingEvent>() {
            override fun append(eventObject: ILoggingEvent) {
                if (eventObject.level == level && !matched.isDone) {
                    matched.complete(eventObject)
                }
            }
        }.apply { start() }
        logger.addAppender(appender)
        try {
            block(matched)
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    @Test
    fun `buildUserAgentOverrideArgs encodes userAgent with and without metadata`() {
        val withoutMeta = Json.parseToJsonElement(
            KcefDevTools.buildUserAgentOverrideArgs("Mozilla/5.0 Test", null),
        ).jsonObject
        withoutMeta["userAgent"]?.jsonPrimitive?.content shouldBe "Mozilla/5.0 Test"
        withoutMeta.containsKey("userAgentMetadata") shouldBe false

        val metadata = KcefWebSettings.desktopUserAgentMetadata(
            userAgent = "Mozilla/5.0 Chrome/152.0.0.0",
            chromiumFullVersion = "152.0.7977.83",
            osName = "Mac OS X",
            osVersion = "15.2",
            osArch = "aarch64",
        )
        val withMeta = Json.parseToJsonElement(
            KcefDevTools.buildUserAgentOverrideArgs("Mozilla/5.0 Chrome/152.0.0.0", metadata),
        ).jsonObject
        withMeta["userAgent"]?.jsonPrimitive?.content shouldBe "Mozilla/5.0 Chrome/152.0.0.0"
        val metaObj = withMeta["userAgentMetadata"]?.jsonObject.shouldNotBeNull()
        metaObj["platform"]?.jsonPrimitive?.content shouldBe "macOS"
        metaObj["architecture"]?.jsonPrimitive?.content shouldBe "arm"
    }

    @Test
    fun `applyUserAgentOverride and enableTouch send expected CDP commands`() {
        val client = mockk<CefDevToolsClient> {
            every { executeDevToolsMethod(any(), any()) } returns CompletableFuture.completedFuture("{}")
        }
        val browser = browserWithClient(client)

        KcefDevTools.applyUserAgentOverride(browser, "Agent/1.0", null)
        KcefDevTools.enableTouch(browser)

        verify(exactly = 1) {
            client.executeDevToolsMethod(
                "Emulation.setUserAgentOverride",
                match { Json.parseToJsonElement(it).jsonObject["userAgent"]?.jsonPrimitive?.content == "Agent/1.0" },
            )
        }
        verify(exactly = 1) {
            client.executeDevToolsMethod(
                "Emulation.setTouchEmulationEnabled",
                KcefDevTools.TOUCH_EMULATION_PARAMS,
            )
        }
    }

    @Test
    fun `buildTouchEventParams maps touch actions and clamps pointer geometry`() {
        val downProps = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val downCoords = MotionEvent.PointerCoords().apply {
            x = 120f
            y = 340f
            touchMajor = 0.5f
            touchMinor = 6.0f
            pressure = 0f
        }
        val down = MotionEvent.obtain(
            0L,
            0L,
            MotionEvent.ACTION_DOWN,
            1,
            arrayOf(downProps),
            arrayOf(downCoords),
            0,
            0,
            1f,
            1f,
            0,
            0,
            0,
            0,
        )
        val downJson = Json.parseToJsonElement(KcefDevTools.buildTouchEventParams(down).shouldNotBeNull()).jsonObject
        downJson["type"]?.jsonPrimitive?.content shouldBe "touchStart"
        val downPoints = downJson["touchPoints"]?.jsonArray.shouldNotBeNull()
        downPoints.size shouldBe 1
        val pt = downPoints.single().jsonObject
        pt["x"]?.jsonPrimitive?.double shouldBe 120.0
        pt["y"]?.jsonPrimitive?.double shouldBe 340.0
        pt["radiusX"]?.jsonPrimitive?.double shouldBe 1.0
        pt["radiusY"]?.jsonPrimitive?.double shouldBe 3.0
        pt["force"]?.jsonPrimitive?.double shouldBe 0.5
        pt["id"]?.jsonPrimitive?.int shouldBe 0

        val up = MotionEvent.obtain(0L, 10L, MotionEvent.ACTION_UP, 120f, 340f, 0)
        val upJson = Json.parseToJsonElement(KcefDevTools.buildTouchEventParams(up).shouldNotBeNull()).jsonObject
        upJson["type"]?.jsonPrimitive?.content shouldBe "touchEnd"
        upJson["touchPoints"]?.jsonArray?.size shouldBe 0

        val cancel = MotionEvent.obtain(0L, 10L, MotionEvent.ACTION_CANCEL, 120f, 340f, 0)
        Json.parseToJsonElement(KcefDevTools.buildTouchEventParams(cancel).shouldNotBeNull())
            .jsonObject["type"]?.jsonPrimitive?.content shouldBe "touchCancel"

        val hover = MotionEvent.obtain(0L, 10L, MotionEvent.ACTION_HOVER_MOVE, 10f, 20f, 0)
        KcefDevTools.buildTouchEventParams(hover).shouldBeNull()
    }

    @Test
    fun `dispatchTouch handles null browser unsupported action and valid touch event`() {
        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 10f, 20f, 0)
        val hover = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_HOVER_MOVE, 10f, 20f, 0)

        KcefDevTools.dispatchTouch(null, down) shouldBe false

        val client = mockk<CefDevToolsClient> {
            every { executeDevToolsMethod(any(), any()) } returns CompletableFuture.completedFuture("{}")
        }
        val browser = browserWithClient(client)

        KcefDevTools.dispatchTouch(browser, hover) shouldBe false
        verify(exactly = 0) { client.executeDevToolsMethod(any(), any()) }

        KcefDevTools.dispatchTouch(browser, down) shouldBe true
        verify(exactly = 1) { client.executeDevToolsMethod("Input.dispatchTouchEvent", any()) }
    }

    @Test
    fun `sendMethod handles null client and logs CDP errors`() {
        KcefDevTools.sendMethod(null, "Emulation.setTouchEmulationEnabled", "{}", "Missing client").shouldBeNull()

        val future = CompletableFuture<String>()
        val client = mockk<CefDevToolsClient> {
            every { executeDevToolsMethod("Input.dispatchTouchEvent", "{}") } returns future
        }
        withLogCapture(Level.ERROR) { logged ->
            KcefDevTools.sendMethod(client, "Input.dispatchTouchEvent", "{}", "Touch event failed")
            future.completeExceptionally(RuntimeException("cdp closed"))
            val event = logged.get(5, TimeUnit.SECONDS)
            event.formattedMessage shouldBe "[KcefWebViewProvider]: Touch event failed"
            event.throwableProxy?.message shouldBe "cdp closed"
        }
    }

    @Test
    fun `sendMethodAsync dispatches callback off devtools-reply thread and logs callback failures`() {
        val future = CompletableFuture<String>()
        val client = mockk<CefDevToolsClient> {
            every { executeDevToolsMethod("Runtime.evaluate", "{}") } returns future
        }
        val replyExecutor = Executors.newSingleThreadExecutor { Thread(it, "devtools-reply").apply { isDaemon = true } }
        val callbackExecutor = Executors.newSingleThreadExecutor { Thread(it, "cdp-callback").apply { isDaemon = true } }
        try {
            val threadName = CompletableFuture<String>()
            val payload = CompletableFuture<String?>()

            KcefDevTools.sendMethodAsync(
                client = client,
                method = "Runtime.evaluate",
                paramsJson = "{}",
                failureMessage = "CDP call failed",
                executor = callbackExecutor,
            ) { res, ex ->
                ex.shouldBeNull()
                threadName.complete(Thread.currentThread().name)
                payload.complete(res)
            }

            replyExecutor.submit { future.complete("""{"result":true}""") }.get(5, TimeUnit.SECONDS)

            payload.get(5, TimeUnit.SECONDS) shouldBe """{"result":true}"""
            threadName.get(5, TimeUnit.SECONDS) shouldBe "cdp-callback"
            threadName.get(5, TimeUnit.SECONDS) shouldNotBe "devtools-reply"
        } finally {
            replyExecutor.shutdownNow()
            callbackExecutor.shutdownNow()
        }
    }

    @Test
    fun `sendMethodAsync logs callback exception without crashing executor and handles null client`() {
        val future = CompletableFuture<String>()
        val client = mockk<CefDevToolsClient> {
            every { executeDevToolsMethod("Runtime.evaluate", "{}") } returns future
        }
        val uncaught = AtomicReference<Throwable?>()
        val entered = CountDownLatch(1)
        val callbackExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "cdp-callback").apply {
                isDaemon = true
                setUncaughtExceptionHandler { _, e -> uncaught.set(e) }
            }
        }
        try {
            withLogCapture(Level.ERROR) { logged ->
                KcefDevTools.sendMethodAsync(
                    client = client,
                    method = "Runtime.evaluate",
                    paramsJson = "{}",
                    failureMessage = "CDP call failed",
                    callbackFailureMessage = "CDP callback failed",
                    executor = callbackExecutor,
                ) { _, _ ->
                    entered.countDown()
                    error("callback crashed")
                }

                future.complete("""{"ok":1}""")
                entered.await(5, TimeUnit.SECONDS) shouldBe true
                val event = logged.get(5, TimeUnit.SECONDS)
                uncaught.get().shouldBeNull()
                event.formattedMessage shouldBe "[KcefWebViewProvider]: CDP callback failed"
                event.throwableProxy?.cause?.message shouldBe "callback crashed"
            }

            val nullClientResult = CompletableFuture<Throwable?>()
            KcefDevTools.sendMethodAsync(
                client = null,
                method = "Runtime.evaluate",
                paramsJson = "{}",
                failureMessage = "No client",
                executor = callbackExecutor,
            ) { res, ex ->
                res.shouldBeNull()
                nullClientResult.complete(ex)
            }.get(5, TimeUnit.SECONDS)

            (nullClientResult.get(5, TimeUnit.SECONDS) is IllegalStateException) shouldBe true
        } finally {
            callbackExecutor.shutdownNow()
        }
    }
}
