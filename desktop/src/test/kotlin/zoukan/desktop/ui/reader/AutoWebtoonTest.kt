package zoukan.desktop.ui.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AutoWebtoonTest {
    @Test
    fun `tags and source names pick webtoon as SY does`() {
        AutoWebtoon.isWebtoon(listOf("Action", "Long Strip"), "MangaDex") shouldBe true
        AutoWebtoon.isWebtoon(listOf("Manhwa"), null) shouldBe true
        AutoWebtoon.isWebtoon(listOf("Manga", "Long Strip"), null) shouldBe false
        AutoWebtoon.isWebtoon(emptyList(), "Webtoons.com") shouldBe true
        AutoWebtoon.isWebtoon(emptyList(), "xkcd") shouldBe false
        AutoWebtoon.isWebtoon(listOf("Romance"), "MangaDex") shouldBe false
    }
}
