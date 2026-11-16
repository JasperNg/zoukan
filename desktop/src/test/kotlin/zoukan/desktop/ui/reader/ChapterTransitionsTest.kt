package zoukan.desktop.ui.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

/** "Always show chapter transition", as Android's PagerViewerAdapter and WebtoonAdapter. */
class ChapterTransitionsTest {
    private fun chapter(number: Double) = Chapter.create().copy(id = number.toLong(), chapterNumber = number)

    private val current = chapter(5.0)

    @Test
    fun `the setting on always shows both transitions`() {
        ChapterTransitions.shown(true, current, chapter(6.0), next = true, hasPages = true) shouldBe true
        ChapterTransitions.shown(true, current, chapter(4.0), next = false, hasPages = true) shouldBe true
    }

    @Test
    fun `off, the transition between back-to-back chapters is skipped`() {
        ChapterTransitions.shown(false, current, chapter(6.0), next = true, hasPages = true) shouldBe false
        ChapterTransitions.shown(false, current, chapter(4.0), next = false, hasPages = true) shouldBe false
        // A decimal chapter in between is no gap
        ChapterTransitions.shown(false, current, chapter(5.5), next = true, hasPages = true) shouldBe false
    }

    @Test
    fun `off, missing chapters keep the transition and its warning`() {
        ChapterTransitions.shown(false, current, chapter(8.0), next = true, hasPages = true) shouldBe true
        ChapterTransitions.gap(current, chapter(8.0), next = true) shouldBe 2
        ChapterTransitions.shown(false, current, chapter(2.0), next = false, hasPages = true) shouldBe true
        ChapterTransitions.gap(current, chapter(2.0), next = false) shouldBe 2
    }

    @Test
    fun `off, no chapter on that side keeps the transition`() {
        ChapterTransitions.shown(false, current, null, next = true, hasPages = true) shouldBe true
        ChapterTransitions.shown(false, current, null, next = false, hasPages = true) shouldBe true
    }

    @Test
    fun `off, a chapter without pages keeps the transitions`() {
        ChapterTransitions.shown(false, current, chapter(6.0), next = true, hasPages = false) shouldBe true
    }

    @Test
    fun `unrecognized chapter numbers count as no gap`() {
        ChapterTransitions.gap(current, chapter(-1.0), next = true) shouldBe 0
        ChapterTransitions.shown(false, current, chapter(-1.0), next = true, hasPages = true) shouldBe false
    }
}
