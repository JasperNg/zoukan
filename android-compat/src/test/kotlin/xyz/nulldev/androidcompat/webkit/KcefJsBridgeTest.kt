package xyz.nulldev.androidcompat.webkit

import android.webkit.JavascriptInterface
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

class KcefJsBridgeTest {
    private class SampleInterface {
        val calls = mutableListOf<String>()

        @JavascriptInterface
        fun echo(value: String): String {
            calls += value
            return "echo:$value"
        }

        @JavascriptInterface
        fun boom(): String = throw IllegalStateException("bridge exploded")

        fun notAnnotated(): String = "hidden"

        @JavascriptInterface
        private fun privateAnnotated(): String = "private"
    }

    private class RecordingResponder : KcefJsBridge.QueryResponder {
        val successes = mutableListOf<String>()
        val failures = mutableListOf<Pair<Int, String?>>()

        override fun success(response: String) {
            successes += response
        }

        override fun failure(
            errorCode: Int,
            errorMessage: String?,
        ) {
            failures += errorCode to errorMessage
        }
    }

    @Test
    fun `exposes only public JavascriptInterface methods and removes by interface name`() {
        val bridge = KcefJsBridge(isDestroyed = { false }, postToLooper = { it.run(); true })
        val sample = SampleInterface()

        bridge.addJavascriptInterface(sample, "AndroidBridge")
        bridge.addJavascriptInterface(sample, "OtherBridge")

        bridge.mappings.map { it.toNice() }.toSet() shouldBe setOf(
            "AndroidBridge.echo",
            "AndroidBridge.boom",
            "OtherBridge.echo",
            "OtherBridge.boom",
        )

        bridge.removeJavascriptInterface("AndroidBridge") shouldBe true
        bridge.removeJavascriptInterface("MissingBridge") shouldBe false
        bridge.mappings.map { it.toNice() }.toSet() shouldBe setOf(
            "OtherBridge.echo",
            "OtherBridge.boom",
        )
    }

    @Test
    fun `pushMappings emits bridge scripts with configured chunk size and query function`() {
        val bridge = KcefJsBridge(
            isDestroyed = { false },
            postToLooper = { it.run(); true },
            chunkChars = 512,
            queryFn = "__customQuery",
        )
        bridge.addJavascriptInterface(SampleInterface(), "Bridge")

        val pushed = mutableListOf<Pair<String, String>>()
        bridge.pushMappings { js, sourceUrl -> pushed += sourceUrl to js }

        val echoEntry = pushed.single { it.first == "SUWAYOMI Bridge.echo" }
        echoEntry.second shouldContain "window.Bridge.echo = async function()"
        echoEntry.second shouldContain "window.__customQuery({"
        echoEntry.second shouldContain "const size = 512;"
        echoEntry.second shouldContain """\u007f-\uffff"""
    }

    @Test
    fun `handleQuery dispatches single invocation onto looper and reports return value or error`() {
        val posted = ArrayDeque<Runnable>()
        val bridge = KcefJsBridge(
            isDestroyed = { false },
            postToLooper = {
                posted += it
                true
            },
        )
        val sample = SampleInterface()
        bridge.addJavascriptInterface(sample, "Bridge")

        val okResponder = RecordingResponder()
        val okPayload = """{"interfaceName":"Bridge","functionName":"echo","args":["hello"]}"""
        bridge.handleQuery(okPayload, okResponder) shouldBe true
        // Not executed until looper drains
        sample.calls.shouldBeEmpty()
        posted.removeFirst().run()
        sample.calls.shouldContainExactly("hello")
        okResponder.successes.shouldContainExactly("echo:hello")
        okResponder.failures.shouldBeEmpty()

        val failResponder = RecordingResponder()
        val failPayload = """{"interfaceName":"Bridge","functionName":"boom","args":[]}"""
        bridge.handleQuery(failPayload, failResponder) shouldBe true
        posted.removeFirst().run()
        failResponder.successes.shouldBeEmpty()
        failResponder.failures.shouldContainExactly(0 to "bridge exploded")
    }

    @Test
    fun `handleQuery ignores unknown targets malformed payloads and destroyed state`() {
        var destroyed = false
        val posted = ArrayDeque<Runnable>()
        val bridge = KcefJsBridge(
            isDestroyed = { destroyed },
            postToLooper = {
                posted += it
                true
            },
        )
        val sample = SampleInterface()
        bridge.addJavascriptInterface(sample, "Bridge")
        val responder = RecordingResponder()

        bridge.handleQuery("{not-json", responder) shouldBe false
        bridge.handleQuery("""{"interfaceName":"Bridge","functionName":"notAnnotated","args":[]}""", responder) shouldBe false
        posted.shouldBeEmpty()

        // Queued before destroy, drained after destroy -> skipped safely
        bridge.handleQuery("""{"interfaceName":"Bridge","functionName":"echo","args":["late"]}""", responder) shouldBe true
        destroyed = true
        posted.removeFirst().run()
        sample.calls.shouldBeEmpty()
        responder.successes.shouldBeEmpty()

        // Rejected immediately when already destroyed
        bridge.handleQuery("""{"interfaceName":"Bridge","functionName":"echo","args":["after"]}""", responder) shouldBe false
    }

    @Test
    fun `handleQuery reassembles chunked payloads and rejects invalid chunks`() {
        val bridge = KcefJsBridge(
            isDestroyed = { false },
            postToLooper = {
                it.run()
                true
            },
            chunkChars = 24,
            maxCallChars = 256,
            maxPendingCalls = 4,
            maxPendingChars = 256,
        )
        val sample = SampleInterface()
        bridge.addJavascriptInterface(sample, "Bridge")
        val responder = RecordingResponder()

        val fullPayload = """{"interfaceName":"Bridge","functionName":"echo","args":["chunked-argument"]}"""
        val parts = fullPayload.chunked(24)
        parts.dropLast(1).forEachIndexed { index, part ->
            val chunkJson = buildJsonObject {
                put("chunkId", "c1")
                put("index", index)
                put("total", parts.size)
                put("data", part)
            }.toString()
            bridge.handleQuery(chunkJson, responder) shouldBe true
        }
        sample.calls.shouldBeEmpty()
        responder.successes.shouldContainExactly(List(parts.size - 1) { "" })

        val lastChunk = buildJsonObject {
            put("chunkId", "c1")
            put("index", parts.lastIndex)
            put("total", parts.size)
            put("data", parts.last())
        }.toString()
        bridge.handleQuery(lastChunk, responder) shouldBe true
        sample.calls.shouldContainExactly("chunked-argument")
        responder.successes.last() shouldBe "echo:chunked-argument"
        bridge.pendingChunkChars shouldBe 0L

        // Invalid chunk JSON or out-of-range chunk index returns false
        bridge.handleQuery("""{"chunkId":"bad","index":1,"total":2,"data":"x"}""", responder) shouldBe false
        bridge.handleQuery("""{"chunkId":123}""", responder) shouldBe false
    }

    @Test
    fun `chunk assembler enforces budgets and clearPendingChunks resets buffered state`() {
        val assembler = KcefCallChunkAssembler(chunkChars = 4, maxCallChars = 12, maxPending = 2, maxPendingChars = 10)
        assembler.add("a", 0, 3, "1234") shouldBe null
        assembler.add("b", 0, 3, "5678") shouldBe null
        assembler.pendingChars shouldBe 8L

        // Exceeding maxPendingChars evicts oldest ("a")
        assembler.add("c", 0, 3, "9012") shouldBe null
        assembler.pendingChars shouldBe 8L
        shouldThrow<IllegalArgumentException> { assembler.add("a", 1, 3, "abcd") }

        assembler.clear()
        assembler.pendingChars shouldBe 0L
        shouldThrow<IllegalArgumentException> { assembler.add("b", 1, 3, "abcd") }
    }

    @Test
    fun `evaluateJavaScript strips javascript prefix and posts result to looper`() {
        val posted = ArrayDeque<Runnable>()
        val bridge = KcefJsBridge(
            isDestroyed = { false },
            postToLooper = {
                posted += it
                true
            },
        )
        val evaluated = CopyOnWriteArrayList<String>()
        val received = CopyOnWriteArrayList<String>()

        bridge.evaluateJavaScript(
            script = "javascript:1 + 1",
            evaluate = { normalized, cb ->
                evaluated += normalized
                cb("2")
            },
            onResult = { received += it },
        )

        evaluated.shouldContainExactly("1 + 1")
        received.shouldBeEmpty()
        posted.removeFirst().run()
        received.shouldContainExactly("2")

        // Scripts without the prefix are passed through untouched
        bridge.normalizeEvaluateScript("window.location.href") shouldBe "window.location.href"
        bridge.normalizeEvaluateScript(Json.encodeToString("ok")) shouldBe "\"ok\""
    }
}
