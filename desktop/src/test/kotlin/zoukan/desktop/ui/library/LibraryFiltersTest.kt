package zoukan.desktop.ui.library

import androidx.compose.ui.unit.Density
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryGroup
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.model.Track
import zoukan.desktop.ui.common.LanguageIcons

/** KMK's library filters and display options, as Android's LibraryScreenModel.applyFilters. */
class LibraryFiltersTest {

    private fun entry(id: Long, categories: List<Long>, fetchInterval: Int = 0) = LibraryManga(
        manga = Manga.create().copy(id = id, ogTitle = "Entry $id", fetchInterval = fetchInterval),
        categories = categories,
        totalChapters = 0,
        readCount = 0,
        bookmarkCount = 0,
        bookmarkReadCount = 0,
        chapterFlags = 0,
        latestUpload = 0,
        chapterFetchedAt = 0,
        lastRead = 0,
    )

    private fun category(id: Long, hidden: Boolean = false) = Category(id = id, name = "Category $id", order = id, flags = 0, hidden = hidden)

    private val items = listOf(
        entry(1, listOf(1L)),
        entry(2, listOf(1L, 2L)),
        entry(3, listOf(2L), fetchInterval = -3),
        entry(4, listOf(0L)),
    )

    private fun input(
        settings: LibraryScreenModel.Settings,
        tracks: Map<Long, List<Track>> = emptyMap(),
        downloadCounts: Map<Long, Int> = emptyMap(),
    ) = LibraryViewInput(
        items = items,
        categories = listOf(category(0), category(1), category(2, hidden = true)),
        settings = settings,
        tracks = tracks,
        downloadCounts = downloadCounts,
    )

    private fun LibraryViewInput.projected() = LibraryViewProjector.project(this)
    private fun LibraryViewInput.shown() = projected().visible.map { it.id }

    @Test
    fun `included categories need all of them, excluded ones none`() {
        input(
            LibraryScreenModel.Settings(
                filterPrefs = LibraryScreenModel.FilterPrefs(
                    categories = LibraryScreenModel.CategoryFilterPrefs(enabled = true, included = setOf(1L)),
                ),
            ),
        ).shown() shouldContainExactly listOf(1L, 2L)
        input(
            LibraryScreenModel.Settings(
                filterPrefs = LibraryScreenModel.FilterPrefs(
                    categories = LibraryScreenModel.CategoryFilterPrefs(enabled = true, included = setOf(1L, 2L)),
                ),
            ),
        ).shown() shouldContainExactly listOf(2L)
        input(
            LibraryScreenModel.Settings(
                filterPrefs = LibraryScreenModel.FilterPrefs(
                    categories = LibraryScreenModel.CategoryFilterPrefs(enabled = true, excluded = setOf(2L)),
                ),
            ),
        ).shown() shouldContainExactly listOf(1L, 4L)
        // Off, the chosen categories do nothing
        input(
            LibraryScreenModel.Settings(
                filterPrefs = LibraryScreenModel.FilterPrefs(
                    categories = LibraryScreenModel.CategoryFilterPrefs(included = setOf(1L)),
                ),
            ),
        ).shown().size shouldBe 4
    }

    @Test
    fun `including categories shows the library ungrouped`() {
        val filtered = input(
            LibraryScreenModel.Settings(
                filterPrefs = LibraryScreenModel.FilterPrefs(
                    categories = LibraryScreenModel.CategoryFilterPrefs(enabled = true, included = setOf(1L)),
                ),
            ),
        )
        filtered.settings.effectiveGroup shouldBe LibraryGroup.UNGROUPED
        filtered.projected().tabs.size shouldBe 0
        filtered.settings.hasFilter shouldBe true
    }

    @Test
    fun `hidden categories show with Show hidden categories`() {
        input(LibraryScreenModel.Settings()).projected().tabs.map { it.id } shouldContainExactly listOf(0L, 1L)
        input(
            LibraryScreenModel.Settings(
                displayPrefs = LibraryScreenModel.DisplayPrefs(showHiddenCategories = true),
            ),
        ).projected().tabs.map { it.id } shouldContainExactly listOf(0L, 1L, 2L)
    }

    @Test
    fun `customized update frequency applies with the release period restriction`() {
        val only = LibraryScreenModel.FilterPrefs(intervalCustom = TriState.ENABLED_IS)
        input(LibraryScreenModel.Settings(filterPrefs = only)).shown().size shouldBe 4
        input(LibraryScreenModel.Settings(filterPrefs = only.copy(skipOutsideReleasePeriod = true))).shown() shouldContainExactly listOf(3L)
        input(
            LibraryScreenModel.Settings(
                filterPrefs = only.copy(intervalCustom = TriState.ENABLED_NOT, skipOutsideReleasePeriod = true),
            ),
        ).shown() shouldContainExactly listOf(1L, 2L, 4L)
    }

    @Test
    fun `tracker filters match any included tracker`() {
        fun track(manga: Long, tracker: Long) = Track(
            id = manga * 10 + tracker, mangaId = manga, trackerId = tracker, remoteId = 0, libraryId = null, title = "",
            lastChapterRead = 0.0, totalChapters = 0, status = 0, score = 0.0, remoteUrl = "", startDate = 0, finishDate = 0, private = false,
        )
        val tracks = mapOf(1L to listOf(track(1, 1)), 2L to listOf(track(2, 2)), 3L to listOf(track(3, 1), track(3, 2)))
        val both = LibraryScreenModel.Settings(
            filterPrefs = LibraryScreenModel.FilterPrefs(tracking = mapOf(1L to TriState.ENABLED_IS, 2L to TriState.ENABLED_IS)),
        )
        input(both, tracks).shown() shouldContainExactly listOf(1L, 2L, 3L)
        val notTwo = LibraryScreenModel.Settings(
            filterPrefs = LibraryScreenModel.FilterPrefs(tracking = mapOf(1L to TriState.ENABLED_IS, 2L to TriState.ENABLED_NOT)),
        )
        input(notTwo, tracks).shown() shouldContainExactly listOf(1L)
    }

    @Test
    fun `search results narrow the library once they arrive`() {
        val searching = input(LibraryScreenModel.Settings()).copy(query = "x")
        searching.shown().size shouldBe 4
        searching.copy(searchResults = setOf(2L)).shown() shouldContainExactly listOf(2L)
    }

    @Test
    fun `downloaded filter keeps only downloaded entries and restores all when disabled`() {
        val counts = mapOf(2L to 1, 4L to 0)
        input(
            LibraryScreenModel.Settings(
                filterPrefs = LibraryScreenModel.FilterPrefs(downloaded = TriState.ENABLED_IS),
            ),
            downloadCounts = counts,
        ).shown() shouldContainExactly listOf(2L)
        input(
            LibraryScreenModel.Settings(
                filterPrefs = LibraryScreenModel.FilterPrefs(downloaded = TriState.DISABLED),
            ),
            downloadCounts = counts,
        ).shown() shouldContainExactly listOf(1L, 2L, 3L, 4L)
    }

    @Test
    fun `language flags load`() {
        listOf("en", "ja", "all", "zh-Hant", "es-419", "eo", "ko").forEach { LanguageIcons.iconName(it).shouldNotBeNull() }
        // Every flag the build copies, gradients and all, reads as a Compose vector
        val flags = java.io.File("../flagkit/src/main/res/drawable").listFiles().orEmpty().map { it.nameWithoutExtension }
        (flags.size > 200) shouldBe true
        (flags + listOf("globe", "ic_flag_un", "ic_flag_esperanto")).forEach { LanguageIcons.load(it, Density(1f)).shouldNotBeNull() }
    }
}
