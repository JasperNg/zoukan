package zoukan.desktop.ui.reader

import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.calculateChapterGap

// ZOUKAN -->
/**
 * When the viewers show the page between two chapters, as Android's PagerViewerAdapter and
 * WebtoonAdapter with "Always show chapter transition".
 *
 * Android skips the transition between back-to-back chapters when the setting is off, and keeps it
 * when chapters are missing in between (its warning), when there is no chapter on that side, and
 * when the current chapter has no pages. Android also shows it while the other chapter is still
 * loading; the desktop viewers hold one chapter at a time, so a skipped transition goes straight to
 * the other chapter instead of showing its pages inline.
 */
object ChapterTransitions {

    /** Chapters missing between [current] and [other], the count Android's transition warns about. */
    fun gap(current: Chapter?, other: Chapter?, next: Boolean): Int =
        if (next) calculateChapterGap(other, current) else calculateChapterGap(current, other)

    /**
     * Whether the transition on the [next] (or previous) side of [current] is shown.
     *
     * @param always the "Always show chapter transition" setting
     * @param hasPages the current chapter has pages
     */
    fun shown(always: Boolean, current: Chapter?, other: Chapter?, next: Boolean, hasPages: Boolean): Boolean =
        always || !hasPages || other == null || gap(current, other, next) > 0
}
// ZOUKAN <--
