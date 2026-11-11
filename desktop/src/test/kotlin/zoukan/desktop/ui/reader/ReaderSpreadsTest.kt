package zoukan.desktop.ui.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ReaderSpreadsTest {
    private fun pages(spreads: List<Spread>) = spreads.map { it.pages }

    @Test
    fun `single pages one by one`() {
        pages(ReaderSpreads.build(3, double = false)) shouldBe listOf(listOf(0), listOf(1), listOf(2))
    }

    @Test
    fun `double pages keep the cover alone, and shifting pairs from the first page`() {
        pages(ReaderSpreads.build(6, double = true)) shouldBe listOf(listOf(0), listOf(1, 2), listOf(3, 4), listOf(5))
        pages(ReaderSpreads.build(6, double = true, shift = true)) shouldBe listOf(listOf(0, 1), listOf(2, 3), listOf(4, 5))
    }

    @Test
    fun `wide pages stand alone in double page mode`() {
        pages(ReaderSpreads.build(6, double = true, wide = setOf(2))) shouldBe
            listOf(listOf(0), listOf(1), listOf(2), listOf(3, 4), listOf(5))
    }

    @Test
    fun `split wide pages become two halves`() {
        val spreads = ReaderSpreads.build(3, double = false, wide = setOf(1), split = true)
        spreads.map { it.pages to it.part } shouldBe listOf(
            listOf(0) to PagePart.WHOLE,
            listOf(1) to PagePart.FIRST_HALF,
            listOf(1) to PagePart.SECOND_HALF,
            listOf(2) to PagePart.WHOLE,
        )
    }

    @Test
    fun `the half read first follows the reading direction`() {
        ReaderSpreads.leftHalfFirst(rtl = false, invert = false) shouldBe true
        ReaderSpreads.leftHalfFirst(rtl = true, invert = false) shouldBe false
        ReaderSpreads.leftHalfFirst(rtl = true, invert = true) shouldBe true
    }
}
