@file:OptIn(ExperimentalTestApi::class)

package zoukan.desktop.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onAllNodesWithTag
import java.awt.Rectangle

/**
 * The screens and dialogs the screenshot check compares: their coverage-matrix keys, and for each
 * the parts of it that change from run to run and so are not compared.
 */
object ScreenshotKeys {
    /** About twenty representative screens: the tabs, the Browse pages, the main screens, three settings pages and five dialogs. */
    val curated: Set<String> = linkedSetOf(
        "tab:Library", "tab:Updates", "tab:History", "tab:Browse", "tab:More",
        "browse:SOURCES", "browse:FEED", "browse:EXTENSIONS", "browse:MIGRATE", "browse:STORES",
        "screen:manga.MangaScreen",
        "screen:reader.ReaderScreen",
        "settings:Appearance", "settings:Reader", "settings:Data",
        "dialog:manga.CoverDialog",
        "dialog:library.LibrarySettingsDialog",
        "dialog:reader.ReaderSettingsDialog",
        "dialog:manga.EditMangaDialog",
        "dialog:browse.OpenUrlDialog",
    )

    /** Text that holds a date or a time since one: it changes with the day the check runs. */
    private val DATE_TEXT = Regex(
        """(?is).*(\bago\b|\bjust now\b|\btoday\b|\byesterday\b|\d{1,4}[/.-]\d{1,2}[/.-]\d{1,4}|""" +
            """\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.? \d{1,2}\b).*""",
    )

    /** Mask text rules and test tags per key; keys not listed have no mask. */
    private val textMasks: Map<String, List<Regex>> = mapOf(
        "tab:Updates" to listOf(DATE_TEXT, Regex("""(?is).*last library update.*""")),
        "tab:History" to listOf(DATE_TEXT),
        "screen:manga.MangaScreen" to listOf(DATE_TEXT),
        "settings:Appearance" to listOf(DATE_TEXT),
    )

    /** The focused text field blinks its caret. */
    private val tagMasks: Map<String, List<String>> = mapOf(
        "dialog:browse.OpenUrlDialog" to listOf("open-url-field"),
    )

    /** Extra margin around a masked node, for its anti-aliased edge. */
    private const val MARGIN = 2

    /**
     * The window rectangles not to compare for [key], resolved on what is on screen now: nodes with
     * a masked test tag or text. Rectangles are in window pixels, as the screenshot is.
     */
    fun masks(key: String, ui: ComposeUiTest): List<Rectangle> {
        val rects = mutableListOf<Rectangle>()
        fun add(nodes: List<androidx.compose.ui.semantics.SemanticsNode>) = nodes.forEach { node ->
            val b = node.boundsInWindow
            rects += Rectangle(b.left.toInt() - MARGIN, b.top.toInt() - MARGIN, b.width.toInt() + 2 * MARGIN, b.height.toInt() + 2 * MARGIN)
        }
        tagMasks[key].orEmpty().forEach { tag -> add(ui.onAllNodesWithTag(tag).fetchSemanticsNodes()) }
        textMasks[key].orEmpty().forEach { regex ->
            val matcher = SemanticsMatcher("text matches $regex") { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { regex.matches(it.text) } == true
            }
            add(ui.onAllNodes(matcher).fetchSemanticsNodes())
        }
        return rects
    }
}
