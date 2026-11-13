package zoukan.desktop.ui.recs

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The recommendation search's status updates never undo a Cancel (ui.md, RecommendationSearch race). */
class SearchStatusRaceTest {

    private val processing = SearchStatus.Error("stands in for any progress update")

    @Test
    fun `a running search's update replaces its own progress`() {
        searchStatusAfter(SearchStatus.Initializing, processing, searchActive = true) shouldBe processing
    }

    @Test
    fun `a cancel is never overwritten by a progress update`() {
        searchStatusAfter(SearchStatus.Cancelling, processing, searchActive = true) shouldBe SearchStatus.Cancelling
        searchStatusAfter(SearchStatus.Cancelling, SearchStatus.Finished.WithoutResults, searchActive = true) shouldBe SearchStatus.Cancelling
    }

    @Test
    fun `once back to Idle or cancelled, a late update does not reopen the dialog`() {
        searchStatusAfter(SearchStatus.Idle, processing, searchActive = true) shouldBe SearchStatus.Idle
        searchStatusAfter(SearchStatus.Initializing, processing, searchActive = false) shouldBe SearchStatus.Initializing
    }
}
