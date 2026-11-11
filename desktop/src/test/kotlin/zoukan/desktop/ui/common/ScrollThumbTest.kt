package zoukan.desktop.ui.common

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ScrollThumbTest {

    @Test
    fun `no thumb when everything fits`() {
        scrollThumb(value = 0, max = 0, viewport = 400f, minThumb = 40f, inset = 4f) shouldBe null
        scrollThumb(value = 0, max = Int.MAX_VALUE, viewport = 400f, minThumb = 40f, inset = 4f) shouldBe null
    }

    @Test
    fun `the thumb shows how much is in view and where`() {
        // Twice the viewport: the thumb is half the track, at the top, then at the bottom
        scrollThumb(value = 0, max = 400, viewport = 400f, minThumb = 40f, inset = 4f) shouldBe (4f to 196f)
        scrollThumb(value = 400, max = 400, viewport = 400f, minThumb = 40f, inset = 4f) shouldBe (200f to 196f)
    }

    @Test
    fun `a long list keeps a thumb big enough to see`() {
        val (top, height) = scrollThumb(value = 50_000, max = 100_000, viewport = 400f, minThumb = 40f, inset = 4f)!!
        height shouldBe 40f
        top shouldBe 4f + (392f - 40f) / 2
    }
}
