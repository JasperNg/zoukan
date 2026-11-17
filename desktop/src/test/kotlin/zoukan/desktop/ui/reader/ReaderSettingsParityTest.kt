package zoukan.desktop.ui.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.app.ReadingModes
import zoukan.desktop.ui.settings.ReaderSettingRow
import zoukan.desktop.ui.settings.ReaderSettingRows

/**
 * The reader's settings sheet and Settings > Reader are built from one list of rows. A setting
 * that only one of them shows has to be named here with its reason, so a setting added to one
 * side and forgotten on the other fails this test.
 */
class ReaderSettingsParityTest {

    /** The in-memory store has no string sets, which the bottom bar's buttons are. */
    private class SetCapableStore(base: PreferenceStore = InMemoryPreferenceStore()) : PreferenceStore by base {
        override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> =
            InMemoryPreferenceStore.InMemoryPreference(key, null, defaultValue)
    }

    private val prefs = DesktopPreferences(SetCapableStore())

    private fun keys(rows: List<ReaderSettingRow>): Set<String> =
        rows.filterIsInstance<ReaderSettingRow.Keyed>().map { it.preference.key() }.toSet()

    private fun keysOf(vararg preferences: Preference<*>): Set<String> = preferences.map { it.key() }.toSet()

    /** The rows of the Settings group [id], up to the next group. */
    private fun group(rows: List<ReaderSettingRow>, id: String): List<ReaderSettingRow> =
        rows.dropWhile { !(it is ReaderSettingRow.Group && it.id == id) }.drop(1).takeWhile { it !is ReaderSettingRow.Group }

    private fun isWebtoon(mode: Int) = mode == ReadingModes.WEBTOON || mode == ReadingModes.CONTINUOUS_VERTICAL

    // Only the sheet: the Filter tab, the theme and the menu's own options, which are not reading behaviour
    private val sheetOnlyCommon = keysOf(
        prefs.readerTheme(), // the sheet's General tab; Settings has the theme under Appearance
        prefs.showPageNumber(),
        prefs.skipRead(), // chapters to skip: the sheet's General tab
        prefs.skipFiltered(),
        prefs.skipDupe(),
        prefs.folderPerManga(),
        prefs.forceHorizontalSeekbar(), // the menu's slider placement
        prefs.landscapeVerticalSeekbar(),
        prefs.leftVerticalSeekbar(),
        prefs.readerBottomButtons(), // the menu's bottom bar
        prefs.autoscrollInterval(), // auto scroll is started from the reader
        prefs.customBrightness(), // the Filter tab: screen filters belong to the reading session
        prefs.customBrightnessValue(),
        prefs.colorFilter(),
        prefs.colorFilterValue(),
        prefs.colorFilterMode(),
        prefs.grayscale(),
        prefs.invertedColors(),
    )

    // Only the sheet, and only for the viewer in use
    private val sheetOnlyPaged = keysOf(
        prefs.imageScaleType(), // how a page fits the window
        prefs.invertDoublePages(),
    )
    private val sheetOnlyWebtoon = keysOf(
        prefs.webtoonSidePadding(),
    )

    // Only Settings: options that apply before a chapter opens or to every session
    private val settingsOnlyCommon = keysOf(
        prefs.showNavigationOverlayOnStart(),
        prefs.flashOnPageChange(), // e-ink flash: a device setting
        prefs.flashColor(),
        prefs.flashDurationMillis(),
        prefs.flashPageInterval(),
        prefs.useAutoWebtoon(), // decided when the entry opens
        prefs.preserveReadingPosition(),
        prefs.preloadSize(), // page loading: needs no reader open
        prefs.readerThreads(),
        prefs.aggressivePageLoading(),
        prefs.readerInstantRetry(),
    )

    // The sheet reads this as a condition for the webtoon scale type and does not list it
    private val settingsOnlyWebtoonMode = keysOf(prefs.longStripGapSmartScale())

    @Test
    fun `the reader sheet and Settings show the same reader settings except an allowed list`() {
        val settings = ReaderSettingRows.settings(prefs)
        val settingsKeys = keys(settings)
        val pagerKeys = keys(group(settings, "pager"))
        val webtoonKeys = keys(group(settings, "webtoon"))
        for (mode in ReadingModes.all) {
            val sheetKeys = keys(ReaderSettingRows.sheet(prefs, mode))
            val webtoon = isWebtoon(mode)
            val sheetOnly = sheetOnlyCommon + if (webtoon) sheetOnlyWebtoon else sheetOnlyPaged
            // The other viewer's section is not on the sheet for this mode
            val settingsOnly = settingsOnlyCommon +
                if (webtoon) pagerKeys + settingsOnlyWebtoonMode else webtoonKeys
            (sheetKeys - settingsKeys) shouldBe sheetOnly
            (settingsKeys - sheetKeys) shouldBe settingsOnly
        }
    }
}
