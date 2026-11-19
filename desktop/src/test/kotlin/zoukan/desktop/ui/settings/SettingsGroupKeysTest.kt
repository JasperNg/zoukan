package zoukan.desktop.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** A list scope that records the keys the page builders give their items, and never runs their content. */
private class KeyRecorder : LazyListScope {
    val keys = mutableListOf<Any?>()

    override fun item(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) {
        keys += key
    }

    override fun items(count: Int, key: ((index: Int) -> Any)?, contentType: (index: Int) -> Any?, itemContent: @Composable LazyItemScope.(index: Int) -> Unit) {
        repeat(count) { keys += key?.invoke(it) }
    }

    @ExperimentalFoundationApi
    override fun stickyHeader(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) {
        keys += key
    }
}

private fun page(build: LazyListScope.() -> Unit): List<Any> = KeyRecorder().apply(build).keys.filterNotNull()

class SettingsGroupKeysTest {
    // The pages as SettingsTab puts them together. MangaDex and Tracking are left out: they read
    // Injekt while they build the list, not inside an item.
    private val pages: Map<String, List<Any>> = mapOf(
        "Appearance" to page { appearanceSettings() },
        "Library" to page { librarySettings(emptyList()) },
        "Downloads" to page { downloadBehaviorSettings(emptyList()) },
        "Browse" to page { browseSettings() },
        "Data" to page {
            dataSettings()
            syncSettings()
            dataSaverSettings()
        },
        "Security" to page { securitySettings() },
        "EHentai" to page { ehSettings() },
        "Advanced" to page {
            shortcutSettings()
            advancedSettings()
        },
        "About" to page { aboutSettings() },
    )

    @Test
    fun `settings group headers are keyed without padding`() {
        for ((name, keys) in pages) {
            val padded = keys.filterIsInstance<String>().filter { it.startsWith(GROUP_KEY_PREFIX) && it != it.trim() }
            check(padded.isEmpty()) { "$name has padded group keys: $padded" }
        }
    }

    @Test
    fun `every settings page has unique list keys`() {
        for ((name, keys) in pages) {
            val duplicates = keys.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            check(duplicates.isEmpty()) { "$name has duplicate list keys: $duplicates" }
        }
        pages.size shouldBe 9
    }
}
