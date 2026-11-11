package zoukan.desktop.ui.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.dp

/**
 * Settings search, as the Android app's SettingsSearchScreen: what is typed is matched against
 * every setting's title and summary, on every page. Android lists the matches and opens the page
 * of the one picked; here the matches are the settings themselves, so they can be changed in the
 * results, and each one's page and group above it opens that page at the setting.
 *
 * The pages stay as they are: [SearchScope] wraps a page's list, and each item shows only when a
 * setting row in it ([SettingRow], [SliderPreference], or [SearchTerms] for anything else) matches.
 */
object SettingsSearch {
    /** Whether [texts] match [query]: every word of the query is in one of them, ignoring case. */
    fun matches(query: String, texts: Collection<String>): Boolean {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        val haystack = texts.joinToString("\n").lowercase()
        return words.all { it in haystack }
    }

    /** A page's item: its key, or its position for an item without one, so search and the page agree. */
    internal fun itemId(key: Any?, index: Int): Any = key ?: "#$index"
}

/** The texts of the settings in one item, which its rows report while they compose. */
internal class SearchTexts {
    val texts = mutableSetOf<String>()

    fun add(vararg values: String?) {
        values.forEach { if (!it.isNullOrBlank()) texts += it }
    }
}

internal val LocalSearchTexts = staticCompositionLocalOf<SearchTexts?> { null }

/** The search in the results being shown, so rows that do not match it hide; null on a page. */
internal val LocalSearchQuery = compositionLocalOf<String?> { null }

/** Words that find an item in settings search, for an item without a [SettingRow]. */
@Composable
fun SearchTerms(vararg terms: String) {
    LocalSearchTexts.current?.add(*terms)
}

/**
 * A page's list as search results: [settingsGroup] headers are left out (each match shows its
 * page and group instead), and every item is hidden unless it matches [query].
 */
internal class SearchScope(
    private val delegate: LazyListScope,
    private val page: SettingsCategory,
    private val query: String,
    private val onOpen: (SettingsCategory, Any) -> Unit,
) : LazyListScope by delegate {
    private var index = 0
    private var group: String? = null

    override fun item(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) {
        val id = SettingsSearch.itemId(key, index++)
        if (key is String && key.startsWith(GROUP_KEY_PREFIX)) {
            group = key.removePrefix(GROUP_KEY_PREFIX).trim()
            return
        }
        val shownGroup = group
        delegate.item(key = "${page.name}/$id", contentType = contentType) {
            SearchResult(page, shownGroup, query, onOpen = { onOpen(page, id) }) { content() }
        }
    }

    override fun items(
        count: Int,
        key: ((index: Int) -> Any)?,
        contentType: (index: Int) -> Any?,
        itemContent: @Composable LazyItemScope.(index: Int) -> Unit,
    ) {
        val first = index
        index += count
        val shownGroup = group
        val id = { i: Int -> SettingsSearch.itemId(key?.invoke(i), first + i) }
        delegate.items(count, key = { i -> "${page.name}/${id(i)}" }, contentType = contentType) { i ->
            SearchResult(page, shownGroup, query, onOpen = { onOpen(page, id(i)) }) { itemContent(i) }
        }
    }
}

/**
 * A page's list as it is, with the item [highlight] names flashed, as Android highlights the
 * setting a search opened. [ids] gets each item's id in order, to scroll to it.
 */
internal class HighlightScope(
    private val delegate: LazyListScope,
    private val highlight: Any?,
    private val ids: MutableList<Any>,
) : LazyListScope by delegate {
    override fun item(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) {
        val id = SettingsSearch.itemId(key, ids.size)
        ids += id
        if (id != highlight) {
            delegate.item(key, contentType, content)
        } else {
            delegate.item(key, contentType) { Highlighted { content() } }
        }
    }

    override fun items(
        count: Int,
        key: ((index: Int) -> Any)?,
        contentType: (index: Int) -> Any?,
        itemContent: @Composable LazyItemScope.(index: Int) -> Unit,
    ) {
        val first = ids.size
        repeat(count) { ids += SettingsSearch.itemId(key?.invoke(it), first + it) }
        delegate.items(count, key, contentType) { i ->
            if (ids.getOrNull(first + i) == highlight) Highlighted { itemContent(i) } else itemContent(i)
        }
    }
}

/** The key prefix of [settingsGroup] headers. */
internal const val GROUP_KEY_PREFIX = "group-"

/** Fades a highlight behind [content] in and out, twice. */
@Composable
private fun Highlighted(content: @Composable () -> Unit) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        repeat(2) {
            alpha.animateTo(0.35f, tween(300))
            alpha.animateTo(0f, tween(500))
        }
    }
    val color = MaterialTheme.colorScheme.primary
    Box(Modifier.fillMaxWidth().background(color.copy(alpha = alpha.value))) { content() }
}

/**
 * One item in the search results: where it is (page and group, which opens the page at it) over
 * the item itself, or nothing at all when the item does not match.
 */
@Composable
private fun SearchResult(
    page: SettingsCategory,
    group: String?,
    query: String,
    onOpen: () -> Unit,
    content: @Composable () -> Unit,
) {
    val texts = remember { SearchTexts() }
    var opened by remember { mutableStateOf(false) }
    Layout(
        content = {
            Column {
                Text(
                    listOfNotNull(page.title, group?.takeIf { it != page.title }).joinToString("  ›  "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().clickable {
                        if (!opened) {
                            opened = true
                            onOpen()
                        }
                    }.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 0.dp),
                )
                CompositionLocalProvider(LocalSearchTexts provides texts, LocalSearchQuery provides query) { content() }
            }
        },
    ) { measurables, constraints ->
        // The rows reported their texts while composing, which comes before this
        if (!SettingsSearch.matches(query, texts.texts)) {
            layout(0, 0) {}
        } else {
            val placeable = measurables.first().measure(constraints)
            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
    }
}
