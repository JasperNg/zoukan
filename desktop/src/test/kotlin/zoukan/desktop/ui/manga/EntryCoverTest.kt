package zoukan.desktop.ui.manga

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class EntryCoverTest {
    @Test
    fun `a wide cover is a panorama only while the setting is on`() {
        EntryCover.aspectRatio(usePanoramaCover = true, coverRatio = 0.5f) shouldBe EntryCover.PANORAMA_RATIO
        EntryCover.aspectRatio(usePanoramaCover = false, coverRatio = 0.5f) shouldBe EntryCover.BOOK_RATIO
        EntryCover.PANORAMA_RATIO shouldBe 3f / 2f
        EntryCover.BOOK_RATIO shouldBe 2f / 3f
    }

    @Test
    fun `a cover is wide from three quarters as tall as it is wide, as Komikku's`() {
        EntryCover.aspectRatio(usePanoramaCover = true, coverRatio = 0.75f) shouldBe EntryCover.PANORAMA_RATIO
        EntryCover.aspectRatio(usePanoramaCover = true, coverRatio = 0.76f) shouldBe EntryCover.BOOK_RATIO
    }

    @Test
    fun `a book cover, a square one and one that has not loaded yet stay book covers`() {
        EntryCover.aspectRatio(usePanoramaCover = true, coverRatio = 1.5f) shouldBe EntryCover.BOOK_RATIO
        // 1 is the ratio until the cover has loaded, as Komikku's
        EntryCover.aspectRatio(usePanoramaCover = true, coverRatio = 1f) shouldBe EntryCover.BOOK_RATIO
    }
}
