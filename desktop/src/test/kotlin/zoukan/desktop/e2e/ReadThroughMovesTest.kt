package zoukan.desktop.e2e

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import zoukan.desktop.e2e.Journey.Move

/** How `readThrough` decides, after a look, whether to stay on a page, turn, or give up. */
class ReadThroughMovesTest {

    @Test
    fun `a page that drew is never turned away from`() {
        for (turns in 0..Journey.MAX_TURNS + 1) {
            Journey.nextMove(drew = true, othersShown = true, turns = turns) shouldBe Move.Stay
            Journey.nextMove(drew = true, othersShown = false, turns = turns) shouldBe Move.Stay
        }
    }

    @Test
    fun `it turns only while another page drew, and at most MAX_TURNS times`() {
        (0 until Journey.MAX_TURNS).forEach { Journey.nextMove(drew = false, othersShown = true, turns = it) shouldBe Move.Turn }
        Journey.nextMove(drew = false, othersShown = true, turns = Journey.MAX_TURNS) shouldBe Move.GiveUp
    }

    @Test
    fun `with nothing on screen it gives up instead of turning blind`() {
        Journey.nextMove(drew = false, othersShown = false, turns = 0) shouldBe Move.GiveUp
    }
}
