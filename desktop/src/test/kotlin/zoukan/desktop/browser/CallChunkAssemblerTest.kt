package zoukan.desktop.browser

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import xyz.nulldev.androidcompat.webkit.KcefWebViewProvider.CallChunkAssembler

/**
 * The WebView's JavaScript-interface bridge puts long calls back together from chunks the page
 * sends; a page can send anything, so chunks that do not fit are refused without allocating.
 */
class CallChunkAssemblerTest {
    // At most 3 chunks of 4 characters, 2 calls unfinished at once
    private val chunks = CallChunkAssembler(chunkChars = 4, maxCallChars = 12, maxPending = 2, maxPendingChars = 100)

    @Test
    fun `a call comes back whole once all its chunks are in`() {
        chunks.add("a", 0, 3, "abcd") shouldBe null
        chunks.add("a", 1, 3, "efgh") shouldBe null
        chunks.add("a", 2, 3, "ij") shouldBe "abcdefghij"
        // And is then forgotten
        shouldThrow<IllegalArgumentException> { chunks.add("a", 1, 3, "efgh") }
    }

    @Test
    fun `a chunk count or index out of range is refused`() {
        shouldThrow<IllegalArgumentException> { chunks.add("a", 0, -1, "") }
        shouldThrow<IllegalArgumentException> { chunks.add("a", 0, 0, "") }
        shouldThrow<IllegalArgumentException> { chunks.add("a", 0, 200_000_000, "") }
        shouldThrow<IllegalArgumentException> { chunks.add("a", 0, 4, "") }
        shouldThrow<IllegalArgumentException> { chunks.add("a", -1, 3, "") }
        shouldThrow<IllegalArgumentException> { chunks.add("a", 3, 3, "") }
        shouldThrow<IllegalArgumentException> { chunks.add("a", 0, 3, "abcde") }
        // A call that does not start with its first chunk
        shouldThrow<IllegalArgumentException> { chunks.add("a", 1, 3, "efgh") }

        chunks.add("a", 0, 2, "abcd") shouldBe null
        // The count cannot change part way
        shouldThrow<IllegalArgumentException> { chunks.add("a", 1, 3, "efgh") }
        chunks.add("a", 1, 2, "ef") shouldBe "abcdef"
    }

    @Test
    fun `only the newest unfinished calls are kept`() {
        chunks.add("a", 0, 2, "abcd") shouldBe null
        chunks.add("b", 0, 2, "bbbb") shouldBe null
        chunks.add("c", 0, 2, "cccc") shouldBe null

        shouldThrow<IllegalArgumentException> { chunks.add("a", 1, 2, "ef") }
        chunks.add("b", 1, 2, "b") shouldBe "bbbbb"
        chunks.add("c", 1, 2, "c") shouldBe "ccccc"
    }

    @Test
    fun `clear drops unfinished calls`() {
        chunks.add("a", 0, 2, "abcd") shouldBe null
        chunks.clear()
        shouldThrow<IllegalArgumentException> { chunks.add("a", 1, 2, "ef") }
    }

    @Test
    fun `the unfinished calls together stay within the character budget`() {
        // 10 characters in all, up to 5 calls
        val budget = CallChunkAssembler(chunkChars = 4, maxCallChars = 12, maxPending = 5, maxPendingChars = 10)
        budget.add("a", 0, 3, "aaaa") shouldBe null
        budget.add("b", 0, 3, "bbbb") shouldBe null
        budget.pendingChars shouldBe 8
        // Over the budget: the oldest call goes
        budget.add("c", 0, 3, "cccc") shouldBe null
        budget.pendingChars shouldBe 8
        shouldThrow<IllegalArgumentException> { budget.add("a", 1, 3, "aaaa") }
        // A finished call gives its characters back
        budget.add("b", 1, 2 + 1, "bb") shouldBe null
        budget.pendingChars shouldBe 10
        budget.add("b", 2, 3, "") shouldBe "bbbbbb"
        budget.pendingChars shouldBe 4
        // A call that alone outgrows the budget is refused, and dropped
        budget.add("c", 1, 3, "cccc") shouldBe null
        shouldThrow<IllegalArgumentException> { budget.add("c", 2, 3, "cccc") }
        budget.pendingChars shouldBe 0
        shouldThrow<IllegalArgumentException> { budget.add("c", 2, 3, "c") }
        budget.clear()
        budget.pendingChars shouldBe 0
    }
}
