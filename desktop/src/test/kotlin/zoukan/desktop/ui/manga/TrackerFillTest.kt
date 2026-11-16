package zoukan.desktop.ui.manga

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Edit details > Fill from tracker: what it does with no, one and several trackers. */
class TrackerFillTest {

    @Test
    fun `an entry no tracker tracks says so`() {
        trackerFill(emptyList<String>()) shouldBe TrackerFill.NotTracked
    }

    @Test
    fun `one tracker fills the details at once`() {
        trackerFill(listOf("AniList")) shouldBe TrackerFill.Fill("AniList")
    }

    @Test
    fun `several trackers open the tracker select dialog with every one of them`() {
        trackerFill(listOf("AniList", "MyAnimeList")) shouldBe TrackerFill.Choose(listOf("AniList", "MyAnimeList"))
        trackerFill(listOf("AniList", "MyAnimeList", "Kitsu")) shouldBe TrackerFill.Choose(listOf("AniList", "MyAnimeList", "Kitsu"))
    }
}
