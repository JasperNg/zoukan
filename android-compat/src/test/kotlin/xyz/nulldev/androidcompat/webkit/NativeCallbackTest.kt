package xyz.nulldev.androidcompat.webkit

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** Callbacks from Chromium's native code ([NativeCallback]) never throw back into it. */
class NativeCallbackTest {
    @AfterEach
    fun reset() {
        KcefRequestFilter.filter = null
        KcefRequestFilter.reportError = null
    }

    @Test
    fun `an error in the request filter whose report fails with an error too reaches no native code`() {
        KcefRequestFilter.reportError = { throw OutOfMemoryError("Java heap space, again") }
        KcefRequestFilter.filter = KcefRequestFilter.Filter { _, _ -> throw OutOfMemoryError("Java heap space") }

        KcefRequestFilter.shouldBlock("https://ads.example/", null) shouldBe false
    }

    @Test
    fun `an error in a guarded callback returns the fallback and is reported once`() {
        val reported = mutableListOf<Throwable>()
        KcefRequestFilter.reportError = { reported += it }

        NativeCallback.guard(fallback = true, what = "a test callback") { throw OutOfMemoryError("Java heap space") } shouldBe true
        NativeCallback.guard(fallback = 7, what = "a test callback") { throw StackOverflowError() } shouldBe 7

        reported.map { it::class } shouldBe listOf(OutOfMemoryError::class)
    }

    @Test
    fun `an exception in a guarded callback returns the fallback and is only logged`() {
        val reported = mutableListOf<Throwable>()
        KcefRequestFilter.reportError = { reported += it }

        NativeCallback.guard<String?>(fallback = null, what = "a test callback") { throw IllegalStateException("no window") } shouldBe null

        reported shouldBe emptyList()
    }

    @Test
    fun `a guarded callback that works returns its own value, and an early return still leaves the callback`() {
        NativeCallback.guard(fallback = false, what = "a test callback") { true } shouldBe true
        fun early(stop: Boolean): String {
            NativeCallback.guard(fallback = Unit, what = "a test callback") { if (stop) return "stopped" }
            return "went on"
        }
        early(true) shouldBe "stopped"
        early(false) shouldBe "went on"
    }
}
