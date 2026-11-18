package zoukan.desktop.browser.blocking

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/** Desktop only: the built-in browser's content blocker (Android's WebView has none). */
class ContentBlockerPreferences(private val store: PreferenceStore) {
    /** Whether ads, trackers and pop-unders are blocked in the built-in browser and extensions' pages. */
    fun enabled() = store.getBoolean("zoukan_browser_content_blocker", true)

    /** Extra block lists, one https address per line (see [UserBlockLists.parseUrls]); none by default. */
    fun listUrls() = store.getString("zoukan_browser_block_lists", "")

    /** When the extra lists were last downloaded, in epoch milliseconds. */
    fun listsCheckedAt() = store.getLong(Preference.appStateKey("zoukan_browser_block_lists_checked"), 0L)
}
